package org.ntust.app.tigerduck.data

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.push.RecordingBackend
import org.ntust.app.tigerduck.push.pushApiClientAt
import org.ntust.app.tigerduck.push.syncFixture
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.sync.AckClock
import org.ntust.app.tigerduck.sync.DeviceClocks
import org.ntust.app.tigerduck.sync.SyncPolicies
import org.ntust.app.tigerduck.util.JvmContext

/**
 * The kind each course delete names (tigerduck-app-android#698), as the iPhone's has since
 * tigerduck-app#985. With none, the backend hides a key it holds no row of, so a course added by
 * hand whose add had not reached it came back hidden on every device, the school's copies included.
 */
class CourseDeleteKindTest {

    private val context = JvmContext()
    private val prefs = AppPreferences(context)
    private val backend = RecordingBackend()
    private val cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), prefs))
    private val signIn = SignInSeries().current

    private val term = "1151"
    private val school = "CS1010001"
    private val typed = "MA1010002"
    private val merged = "PE1010003"

    private val schoolCourse = Course(courseNo = school, courseName = "School")
    private val typedCourse = Course(courseNo = typed, courseName = "Typed", isManual = true)
    private val mergedCopy = Course(courseNo = merged, courseName = "Merged", isManual = true, moodleIdNumber = "1151PE1010003")

    private var nowMs = 1_790_000_000_000L
    private val deviceClocks = DeviceClocks.wallOnly { nowMs }

    @Before
    fun syncs() {
        // TigerSync is off for good on F-Droid, where none of these goes out at all.
        assumeTrue(prefs.cloudSyncEnabled)
    }

    /** The deletes as Hilt builds them, on `/v4`. */
    private fun TestScope.deletes(): PendingCourseDeletes {
        val client = pushApiClientAt("http://127.0.0.1:9/v4", backend.client, prefs, deviceClocks)
        val ack = AckClock()
        val adds = PendingCourseAdds(cache, client, SyncPolicies(prefs), ack, backgroundScope)
        return PendingCourseDeletes(cache, adds, client, deviceClocks, SyncPolicies(prefs), ack, backgroundScope)
    }

    private fun answers(courseNo: String) =
        backend.answer("/v4/sync/courses/client:$term:$courseNo", 200, syncFixture("v4/answer-delete.json"))

    /** The kind each DELETE that reached the backend named, null for none. */
    private fun kindsSent() = backend.sent.map { Regex("""[?&]kind=(\w+)""").find(it.url)?.groupValues?.get(1) }

    private suspend fun hidesHere(courseNo: String) =
        cache.saveDeletedCourseNos(CourseTombstoneKeys.hide(courseNo, term, cache.loadDeletedCourseNos()), signIn)

    // ---- the class table's choice, as the student deletes ----

    @Test
    fun `the class table's delete of a school course is a hide`() {
        assertEquals(CourseDeleteKind.HIDE, CourseDeleteKind.asked(schoolCourse, hides = true, listedBySchool = false))
    }

    @Test
    fun `the class table's delete of a course added here that the school does not list takes it back`() {
        assertEquals(CourseDeleteKind.REMOVE, CourseDeleteKind.asked(typedCourse, hides = true, listedBySchool = false))
    }

    @Test
    fun `a course added here that the school lists too is a hide`() {
        assertEquals(CourseDeleteKind.HIDE, CourseDeleteKind.asked(typedCourse, hides = true, listedBySchool = true))
    }

    @Test
    fun `a copy merged from another device's row names no kind, for its row to decide`() {
        assertNull(CourseDeleteKind.asked(mergedCopy, hides = true, listedBySchool = false))
    }

    @Test
    fun `a course taken back out of Add course is a removal`() {
        assertEquals(CourseDeleteKind.REMOVE, CourseDeleteKind.asked(schoolCourse, hides = false, listedBySchool = true))
    }

    // ---- the queue sends the kind chosen ----

    @Test
    fun `a delete goes as chosen though a sync sends it before the class table's writes`() = runTest {
        val deletes = deletes()
        answers(typed)
        answers(school)

        // Kept, then sent by a full sync before the hide, its mark and the save that takes the
        // school course off the term's rows.
        deletes.keep(term, typed, deletes.stamp(), signIn, ChosenKind(CourseDeleteKind.REMOVE))
        deletes.keep(term, school, deletes.stamp(), signIn, ChosenKind(CourseDeleteKind.HIDE))
        deletes.drain(signIn)

        assertEquals(listOf("remove", "hide"), kindsSent())
    }

    @Test
    fun `the kind chosen for an older delete of a course is not a newer one's`() = runTest {
        val deletes = deletes()
        answers(school)

        deletes.keep(term, school, deletes.stamp(), signIn, ChosenKind(CourseDeleteKind.HIDE))
        // Added back, which lets that delete go, then taken back out with no hide.
        deletes.forget(term, school, signIn)
        deletes.send(term, school, signIn)

        assertEquals(listOf("remove"), kindsSent())
    }

    // ---- a delete no choice was kept for, read as it goes ----

    @Test
    fun `the delete of a course this phone does not hide takes back a hand-add`() = runTest {
        val deletes = deletes()
        answers(typed)

        deletes.send(term, typed, signIn)

        assertEquals(listOf("remove"), kindsSent())
    }

    @Test
    fun `a course added here that course selection did not list is taken back, not hidden`() = runTest {
        cache.saveCourses(listOf(typedCourse), term, signIn)
        cache.recordSelectionRoster(term, listOf(school), signIn)
        cache.hideDeletedCourse(typed, term, typedCourse, signIn)
        val deletes = deletes()
        answers(typed)

        deletes.send(term, typed, signIn)

        assertEquals(listOf("remove"), kindsSent())
    }

    @Test
    fun `a hidden school course the term no longer caches is a hide, by course selection's answer`() = runTest {
        cache.recordSelectionRoster(term, listOf(school), signIn)
        hidesHere(school)
        val deletes = deletes()
        answers(school)

        deletes.send(term, school, signIn)

        assertEquals(listOf("hide"), kindsSent())
    }

    @Test
    fun `a hidden copy the school does not list names no kind`() = runTest {
        hidesHere(merged)
        val deletes = deletes()
        answers(merged)

        deletes.send(term, merged, signIn)

        assertEquals(listOf(null), kindsSent())
    }

    // ---- the school's lists, which Use Local's hides read too ----

    @Test
    fun `the school lists a course its cached rows or course selection's answer hold`() = runTest {
        assertFalse(cache.schoolLists(term, school))
        cache.saveCourses(listOf(schoolCourse), term, signIn)
        assertTrue(cache.schoolLists(term, school))

        assertFalse(cache.schoolLists(term, merged))
        cache.recordSelectionRoster(term, listOf(merged), signIn)
        assertTrue(cache.schoolLists(term, merged))
        assertFalse(cache.schoolLists(term, typed))
    }
}
