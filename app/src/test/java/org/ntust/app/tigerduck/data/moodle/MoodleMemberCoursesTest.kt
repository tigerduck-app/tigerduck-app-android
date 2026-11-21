package org.ntust.app.tigerduck.data.moodle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.network.model.MoodleEnrolledCourse
import org.ntust.app.tigerduck.network.model.moodle.MoodleMember
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberList
import org.ntust.app.tigerduck.network.moodle.MoodleCourseApi
import org.ntust.app.tigerduck.shared.Course
import java.io.IOException
import java.lang.reflect.Type
import java.util.Locale

/**
 * A member's courses in common with the account (#701, as the iPhone's tigerduck-app#984): every
 * course Moodle lists both in, of every term, by term newest first, without the course on screen;
 * none on the account's own page, and none where the call is not allowed or fails. Made-up
 * student IDs, names and course numbers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MoodleMemberCoursesTest {

    private class MemoryCache : MoodleCachePort {
        val files = mutableMapOf<String, Pair<Any, Long>>()

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> load(type: Type, name: String): Pair<T, Long>? = files[name] as Pair<T, Long>?

        override suspend fun save(value: Any, fetchedAtMs: Long, name: String) {
            files[name] = value to fetchedAtMs
        }
    }

    private class Site : StubMoodleCourseApi() {
        var allowed = setOf(MoodleCourseApi.MEMBERS_FUNCTION, MoodleCourseApi.USERS_COURSES_FUNCTION)
        var ownId: Int? = OWN_ID
        var shared = listOf<MoodleEnrolledCourse>()
        var before: suspend () -> Unit = {}
        val calls = mutableListOf<Int>()

        override suspend fun functions() = allowed
        override suspend fun members(courseId: Int) = MoodleMemberList(ownId, listOf(me, classmate))
        override suspend fun sharedCourses(userId: Int): List<MoodleEnrolledCourse> {
            calls += userId
            before()
            return shared
        }
    }

    private val site = Site()
    private val cache = MemoryCache()
    private var enrolled = setOf<Int>()
    private val classTables = mutableMapOf<String, List<Course>>()
    private val classTableChanges = MutableStateFlow(0)
    private var signedIn = true

    private fun TestScope.store(cache: MoodleCachePort = this@MoodleMemberCoursesTest.cache, api: MoodleCourseApi = site) =
        MoodleCourseStore(
            courseId = COURSE_ID,
            termCourseIds = listOf(COURSE_ID),
            courseCodes = emptyList(),
            api = api,
            context = MoodleCacheContext("B11399001", cache, isCurrent = { signedIn }),
            scope = backgroundScope,
            ownCourses = MoodleOwnCourses({ enrolled }, { classTables[it].orEmpty() }, classTableChanges),
        )

    private suspend fun MoodleCourseStore.shown(memberId: Int = classmate.id) = memberCourses.terms(memberId).first()

    private fun kept(memberId: Int = classmate.id): List<MoodleEnrolledCourse>? {
        @Suppress("UNCHECKED_CAST")
        return cache.files["members/$memberId/courses"]?.first as List<MoodleEnrolledCourse>?
    }

    private fun terms(shared: List<MoodleEnrolledCourse>, classTable: Map<String, List<Course>> = emptyMap()) =
        MoodleCommonTerm.terms(shared, besides = COURSE_ID, locale = Locale.ENGLISH) { classTable[it].orEmpty() }

    // ---- grouping ----

    @Test
    fun `courses in common list by term, newest first, under the class table's name, and leave out the page's course`() {
        val classTable = mapOf(
            "1141" to listOf(course("CS2001301", "Algorithms", "Wang", "1141"), course("CS3001302", "Operating Systems", "", "1141")),
            "1132" to listOf(course("CS1002301", "Introduction to Computers", "Chen", "1132")),
            "1131" to listOf(course("MA1001301", "微積分（一）", "Lin", "1131").copy(customCourseName = "Calculus")),
        )
        val shared = listOf(
            thisCourse,
            moodle(10001, "1131MA1001301", "1131 MA1001301 微積分（一）"),
            moodle(30002, "1141GE3105701", "1141 GE3105701 Ethics"),
            moodle(20001, "1132CS1002301", "1132 CS1002301 計算機概論"),
            moodle(30003, "1141CS3001301", "1141 CS3001301 CS3001302 作業系統"),
            moodle(20500, "113hPE1001301", "113H PE1001301 Swimming"),
            moodle(30001, "1141CS2001301", "1141 CS2001301 演算法"),
        )

        assertEquals(
            listOf(
                MoodleCommonTerm(
                    "1141",
                    listOf(
                        MoodleCommonCourse(30001, "Algorithms", listOf("CS2001301", "Wang"), "CS2001301"),
                        MoodleCommonCourse(30002, "Ethics", listOf("GE3105701"), "GE3105701"),
                        MoodleCommonCourse(30003, "Operating Systems", listOf("CS3001302"), "CS3001302"),
                    ),
                ),
                MoodleCommonTerm("113H", listOf(MoodleCommonCourse(20500, "Swimming", listOf("PE1001301"), "PE1001301"))),
                MoodleCommonTerm(
                    "1132",
                    listOf(MoodleCommonCourse(20001, "Introduction to Computers", listOf("CS1002301", "Chen"), "CS1002301")),
                ),
                MoodleCommonTerm("1131", listOf(MoodleCommonCourse(10001, "Calculus", listOf("MA1001301", "Lin"), "MA1001301"))),
            ),
            terms(shared, classTable),
        )
    }

    @Test
    fun `a member who shares only the page's course, or whom Moodle lists in nothing, has no courses in common`() {
        assertEquals(emptyList<MoodleCommonTerm>(), terms(listOf(thisCourse)))
        assertEquals(emptyList<MoodleCommonTerm>(), terms(emptyList()))
    }

    @Test
    fun `a course whose Moodle id names no term lists after every term, under Moodle's name and with no number`() {
        val shared = listOf(
            moodle(900, "", "Department Notices"),
            moodle(901, "2025ORIENT", "Orientation 2025"),
            moodle(902, "DEPTCS", "CS Department News"),
            moodle(903, null, "Club &amp; Society News"),
            algorithms,
        )

        val terms = terms(shared)

        assertEquals(listOf("1141", null), terms.map { it.term })
        assertEquals(
            listOf(
                MoodleCommonCourse(903, "Club & Society News", emptyList(), "903"),
                MoodleCommonCourse(902, "CS Department News", emptyList(), "902"),
                MoodleCommonCourse(900, "Department Notices", emptyList(), "900"),
                MoodleCommonCourse(901, "Orientation 2025", emptyList(), "901"),
            ),
            terms.last().courses,
        )
    }

    // ---- the store ----

    @Test
    fun `the account's own page asks Moodle for nothing and lists no courses in common`() = runTest(UnconfinedTestDispatcher()) {
        enrolled = setOf(COURSE_ID, algorithms.id)
        site.shared = listOf(thisCourse, algorithms)
        val store = store()
        store.refreshMembers()

        store.memberCourses.refresh(me.id)

        assertEquals(emptyList<Int>(), site.calls)
        assertEquals(emptyList<MoodleCommonTerm>(), store.shown(me.id))
    }

    @Test
    fun `until the members list names the account, nothing is asked or listed, not even a kept copy`() = runTest(UnconfinedTestDispatcher()) {
        enrolled = setOf(COURSE_ID, algorithms.id)
        cache.files["members/${classmate.id}/courses"] = listOf(algorithms) to 1L
        site.ownId = null
        val unlisted = store()
        unlisted.memberCourses.refresh(classmate.id)
        assertEquals(emptyList<MoodleCommonTerm>(), unlisted.shown())

        val store = store()
        store.refreshMembers()
        store.memberCourses.refresh(classmate.id)
        store.memberCourses.refresh(me.id)

        assertEquals(emptyList<Int>(), site.calls)
        assertEquals(emptyList<MoodleCommonTerm>(), store.shown())
        assertEquals(emptyList<MoodleCommonTerm>(), store.shown(me.id))
    }

    @Test
    fun `a member's course the account is not enrolled in, such as an open one, is neither listed nor kept`() = runTest(UnconfinedTestDispatcher()) {
        enrolled = setOf(COURSE_ID, algorithms.id)
        site.shared = listOf(thisCourse, algorithms, moodle(777, "1141GE0000001", "1141 GE0000001 Open Lectures"))
        val store = store()
        store.refreshMembers()

        store.memberCourses.refresh(classmate.id)

        assertEquals(listOf(classmate.id), site.calls)
        assertEquals(
            listOf(MoodleCommonTerm("1141", listOf(MoodleCommonCourse(30001, "演算法", listOf("CS2001301"), "CS2001301")))),
            store.shown(),
        )
        assertEquals(listOf(COURSE_ID, 30001), kept()?.map { it.id })
    }

    @Test
    fun `a course both are in that has no Moodle id number lists last, and one only the member is in stays out`() = runTest(UnconfinedTestDispatcher()) {
        val notices = moodle(900, "", "Department Notices")
        enrolled = setOf(COURSE_ID, algorithms.id, notices.id)
        site.shared = listOf(thisCourse, algorithms, notices, moodle(901, "", "Club Notices"))
        classTables["1141"] = listOf(course("CS2001301", "Algorithms", "Wang", "1141"))
        val store = store()
        store.refreshMembers()

        store.memberCourses.refresh(classmate.id)

        assertEquals(
            listOf(
                MoodleCommonTerm("1141", listOf(MoodleCommonCourse(30001, "Algorithms", listOf("CS2001301", "Wang"), "CS2001301"))),
                MoodleCommonTerm(null, listOf(MoodleCommonCourse(900, "Department Notices", emptyList(), "900"))),
            ),
            store.shown(),
        )
        assertEquals(listOf(COURSE_ID, 30001, 900), kept()?.map { it.id })
    }

    @Test
    fun `without the account's own enrolled courses nothing is asked, kept or listed until they are there`() = runTest(UnconfinedTestDispatcher()) {
        site.shared = listOf(thisCourse, algorithms)
        val store = store()
        store.refreshMembers()

        store.memberCourses.refresh(classmate.id)

        assertEquals(emptyList<Int>(), site.calls)
        assertNull(kept())
        assertEquals(emptyList<MoodleCommonTerm>(), store.shown())

        enrolled = setOf(COURSE_ID, algorithms.id)
        store.memberCourses.refresh(classmate.id)
        assertEquals(listOf(classmate.id), site.calls)
        assertEquals(listOf(30001), store.shown().flatMap { it.courses }.map { it.id })
    }

    @Test
    fun `a member's courses in common are kept for the account, shown from its copy next time, and not from another's`() = runTest(UnconfinedTestDispatcher()) {
        enrolled = setOf(COURSE_ID, algorithms.id)
        site.shared = listOf(thisCourse, algorithms)
        val store = store()
        store.refreshMembers()
        store.memberCourses.refresh(classmate.id)
        val expected = listOf(MoodleCommonTerm("1141", listOf(MoodleCommonCourse(30001, "演算法", listOf("CS2001301"), "CS2001301"))))
        assertEquals(expected, store.shown())

        // The next time, with the members and the member's courses read from the account's copies.
        val quiet = Site().apply { allowed = emptySet() }
        val reopened = store(api = quiet)
        reopened.memberCourses.feed(classmate.id).awaitCache()
        assertEquals(expected, reopened.shown())
        assertEquals(emptyList<Int>(), quiet.calls)

        val otherAccount = store(cache = MemoryCache(), api = quiet)
        otherAccount.refreshMembers()
        assertEquals(emptyList<MoodleCommonTerm>(), otherAccount.shown())
    }

    @Test
    fun `the courses are named again when the class table changes`() = runTest(UnconfinedTestDispatcher()) {
        enrolled = setOf(COURSE_ID, algorithms.id)
        site.shared = listOf(thisCourse, algorithms)
        classTables["1141"] = listOf(course("CS2001301", "Algorithms", "Wang", "1141"))
        val store = store()
        store.refreshMembers()
        store.memberCourses.refresh(classmate.id)
        val names = mutableListOf<List<String>>()
        backgroundScope.async { store.memberCourses.terms(classmate.id).collect { t -> names += t.flatMap { it.courses }.map { it.name } } }
        runCurrent()

        classTables["1141"] = listOf(course("CS2001301", "Advanced Algorithms", "Wang", "1141"))
        classTableChanges.value++
        runCurrent()

        assertEquals(listOf(listOf("Algorithms"), listOf("Advanced Algorithms")), names)
    }

    @Test
    fun `courses in common that land after a sign-out are neither shown nor kept`() = runTest(UnconfinedTestDispatcher()) {
        enrolled = setOf(COURSE_ID, algorithms.id)
        site.shared = listOf(algorithms)
        val answer = CompletableDeferred<Unit>()
        site.before = { answer.await() }
        val store = store()
        store.refreshMembers()

        val refresh = async { store.memberCourses.refresh(classmate.id) }
        runCurrent()
        signedIn = false
        answer.complete(Unit)
        refresh.await()

        assertEquals(emptyList<MoodleCommonTerm>(), store.shown())
        assertNull(kept())
    }

    @Test
    fun `a site that does not allow the call is asked nothing and keeps nothing, so the next open asks again`() = runTest(UnconfinedTestDispatcher()) {
        enrolled = setOf(COURSE_ID, algorithms.id)
        site.shared = listOf(algorithms)
        site.allowed = setOf(MoodleCourseApi.MEMBERS_FUNCTION)
        val store = store()
        store.refreshMembers()

        store.memberCourses.refresh(classmate.id)

        assertEquals(emptyList<Int>(), site.calls)
        assertNull(store.memberCourses.feed(classmate.id).state.value.fetchedAtMs)
        assertNull(kept())
        assertEquals(emptyList<MoodleCommonTerm>(), store.shown())

        site.allowed = setOf(MoodleCourseApi.MEMBERS_FUNCTION, MoodleCourseApi.USERS_COURSES_FUNCTION)
        store.memberCourses.refresh(classmate.id)
        assertEquals(listOf(classmate.id), site.calls)
    }

    @Test
    fun `a kept copy goes from the page once the site no longer allows the call, and comes back with it`() = runTest(UnconfinedTestDispatcher()) {
        enrolled = setOf(COURSE_ID, algorithms.id)
        cache.files["members/${classmate.id}/courses"] = listOf(algorithms) to 1L
        site.allowed = setOf(MoodleCourseApi.MEMBERS_FUNCTION)
        val store = store()
        store.refreshMembers()
        assertEquals(listOf(30001), store.shown().flatMap { it.courses }.map { it.id })

        store.memberCourses.refresh(classmate.id)
        assertEquals(emptyList<MoodleCommonTerm>(), store.shown())

        site.allowed = setOf(MoodleCourseApi.MEMBERS_FUNCTION, MoodleCourseApi.USERS_COURSES_FUNCTION)
        site.shared = listOf(algorithms)
        store.memberCourses.refresh(classmate.id)
        assertEquals(listOf(30001), store.shown().flatMap { it.courses }.map { it.id })
    }

    @Test
    fun `a site whose functions cannot be read is asked nothing, and the page lists nothing`() = runTest(UnconfinedTestDispatcher()) {
        enrolled = setOf(COURSE_ID, algorithms.id)
        site.shared = listOf(algorithms)
        val store = store()
        store.refreshMembers()
        val offline = object : StubMoodleCourseApi() {
            override suspend fun functions(): Set<String> = throw IOException("offline")
        }
        val failing = MoodleMemberCourses(COURSE_ID, store.members, offline, MoodleCacheContext("B11399001", cache, { true }), backgroundScope, MoodleOwnCourses({ enrolled }, { emptyList() }))

        failing.refresh(classmate.id)

        assertNull(kept())
        assertEquals(emptyList<MoodleCommonTerm>(), failing.terms(classmate.id).first())

        // Offline, a kept copy stays: the site did not say it refuses the call.
        cache.files["members/${classmate.id}/courses"] = listOf(algorithms) to 1L
        val offlineAgain = MoodleMemberCourses(COURSE_ID, store.members, offline, MoodleCacheContext("B11399001", cache, { true }), backgroundScope, MoodleOwnCourses({ enrolled }, { emptyList() }))
        offlineAgain.refresh(classmate.id)
        assertEquals(listOf(30001), offlineAgain.terms(classmate.id).first().flatMap { it.courses }.map { it.id })
    }

    @Test
    fun `an ask that fails keeps nothing, so the next open asks again, and the page lists no courses in common`() = runTest(UnconfinedTestDispatcher()) {
        enrolled = setOf(COURSE_ID, algorithms.id)
        site.shared = listOf(algorithms)
        site.before = { throw IOException("offline") }
        val store = store()
        store.refreshMembers()

        store.memberCourses.refresh(classmate.id)

        assertEquals(listOf(classmate.id), site.calls)
        assertNull(store.memberCourses.feed(classmate.id).state.value.fetchedAtMs)
        assertTrue(store.memberCourses.feed(classmate.id).state.value.failure is IOException)
        assertNull(kept())
        assertEquals(emptyList<MoodleCommonTerm>(), store.shown())
    }

    private companion object {
        const val COURSE_ID = 20119
        const val OWN_ID = 42

        fun moodle(id: Int, idnumber: String?, fullname: String) =
            MoodleEnrolledCourse(id = id, fullname = fullname, shortname = "", idnumber = idnumber, startdate = null, enddate = null)

        fun member(id: Int, fullName: String) = MoodleMember(
            id = id, fullName = fullName, idNumber = null, email = null, groups = emptyList(), roles = emptyList(),
            pictureUrl = null, description = "", interests = emptyList(), fields = emptyList(),
        )

        fun course(courseNo: String, name: String, instructor: String, semester: String) =
            Course(courseNo = courseNo, courseName = name, instructor = instructor, moodleIdNumber = semester + courseNo)

        val me = member(OWN_ID, "B11399001 @ Tiger Duck")
        val classmate = member(43, "B11399002 @ Duck Tiger")
        val thisCourse = moodle(COURSE_ID, "1141CS1001301", "1141 CS1001301 Data Structures")
        val algorithms = moodle(30001, "1141CS2001301", "1141 CS2001301 演算法")
    }
}
