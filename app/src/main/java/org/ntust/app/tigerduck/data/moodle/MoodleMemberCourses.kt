package org.ntust.app.tigerduck.data.moodle

import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.ntust.app.tigerduck.network.MoodleCourseIds
import org.ntust.app.tigerduck.network.MoodleService
import org.ntust.app.tigerduck.network.decodeHtmlEntities
import org.ntust.app.tigerduck.network.model.MoodleEnrolledCourse
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberList
import org.ntust.app.tigerduck.network.moodle.MoodleCourseApi
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.time.AcademicTerm
import java.text.Collator
import java.util.Locale

/**
 * The account's own courses, which another member's courses in common are found among and named
 * by.
 */
class MoodleOwnCourses(
    /** The ids of every Moodle course the account is enrolled in, of every term; empty until a fetch keeps them. */
    val enrolledIds: suspend () -> Set<Int>,
    /** The class table's courses of a term, by its code. */
    val classTable: suspend (term: String) -> List<Course>,
    /** Changes whenever the class table does. */
    val classTableChanges: Flow<Any> = flowOf(Unit),
) {
    companion object {
        /** No courses, so no member has any in common. */
        val NONE = MoodleOwnCourses({ emptySet() }, { emptyList() })
    }
}

/**
 * The courses another member of course [courseId] shares with the account, of every term, for
 * their page (#701): one `core_enrol_get_users_courses` call per member, kept for the account
 * with the course's other Moodle data for [MoodleRefreshInterval.MEMBERS_MS], and found among the
 * account's own enrolled courses. The members list's `enrolledcourses` cannot be used: NTUST's
 * Moodle hides it. Ported from iOS `MoodleMemberCourses` (tigerduck-app#984).
 */
class MoodleMemberCourses(
    private val courseId: Int,
    private val members: MoodleFeed<MoodleMemberList>,
    private val api: MoodleCourseApi,
    private val context: MoodleCacheContext,
    private val scope: CoroutineScope,
    private val own: MoodleOwnCourses,
) {
    private val feeds = mutableMapOf<Int, MoodleFeed<List<MoodleEnrolledCourse>>>()

    /** Whether the site last said it does not allow the call, which hides a kept copy too; not when it could not be asked. */
    private val refused = MutableStateFlow(false)

    /** Member [memberId]'s courses as last asked, those the account is in too. */
    @Synchronized
    fun feed(memberId: Int): MoodleFeed<List<MoodleEnrolledCourse>> = feeds.getOrPut(memberId) {
        MoodleFeed(
            "members/$memberId/courses",
            object : TypeToken<List<MoodleEnrolledCourse>>() {}.type,
            MoodleRefreshInterval.MEMBERS_MS,
            context,
            scope,
        )
    }

    /**
     * Asks Moodle for member [memberId]'s courses and keeps those the account is enrolled in too:
     * for another user Moodle also lists the courses the account can merely see, such as open
     * ones. Nothing is asked, or kept, on the account's own page, before the members list names
     * the account, without the account's own courses, or where the site does not allow the call
     * or its functions cannot be read, so the next open asks again.
     */
    suspend fun refresh(memberId: Int) {
        if (!isSomeoneElse(memberId, members.value)) return
        val enrolled = own.enrolledIds()
        if (enrolled.isEmpty() || !allowsCall()) return
        feed(memberId).refresh { api.sharedCourses(memberId).filter { it.id in enrolled } }
    }

    private suspend fun allowsCall(): Boolean = try {
        (MoodleCourseApi.USERS_COURSES_FUNCTION in api.functions()).also { refused.value = !it }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /**
     * Member [memberId]'s courses in common, as [MoodleCommonTerm.terms] lists them, again each
     * time the answer or the class table changes. None on the account's own page, before the
     * members list names the account, or once the site refuses the call, not even a kept copy.
     */
    fun terms(memberId: Int): Flow<List<MoodleCommonTerm>> =
        combine(members.state, feed(memberId).state, own.classTableChanges, refused) { list, shared, change, refusing ->
            shared.value?.takeIf { !refusing && isSomeoneElse(memberId, list.value) } to change
        }
            .distinctUntilChanged()
            .map { (shared, _) -> shared?.let { listing(it) }.orEmpty() }

    private suspend fun listing(shared: List<MoodleEnrolledCourse>): List<MoodleCommonTerm> {
        val tables = shared.mapNotNullTo(mutableSetOf()) { AcademicTerm.parse(it.semesterCode)?.code }
            .associateWith { code ->
                try {
                    own.classTable(code)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
            }
        return MoodleCommonTerm.terms(shared, besides = courseId) { tables[it].orEmpty() }
    }

    private fun isSomeoneElse(memberId: Int, list: MoodleMemberList?): Boolean {
        val ownId = list?.ownId ?: return false
        return memberId != ownId
    }
}

/** A term's courses in common, [term] its code, such as `1141`; null for the courses of no term. */
data class MoodleCommonTerm(val term: String?, val courses: List<MoodleCommonCourse>) {
    companion object {
        private val newestFirst: Comparator<AcademicTerm?> = nullsLast(reverseOrder())

        /**
         * [shared] by term, newest first, the courses of no term last; each term's courses by
         * name, leaving out course [besides], the one on screen. A course of a term is named as
         * [classTable] names it for that term, with its number and teacher.
         */
        fun terms(
            shared: List<MoodleEnrolledCourse>,
            besides: Int,
            locale: Locale = Locale.getDefault(),
            classTable: (term: String) -> List<Course>,
        ): List<MoodleCommonTerm> {
            val collator = Collator.getInstance(locale)
            val byName = Comparator<MoodleCommonCourse> { a, b ->
                collator.compare(a.name, b.name).takeIf { it != 0 } ?: a.id.compareTo(b.id)
            }
            return shared.filter { it.id != besides }
                .groupBy { AcademicTerm.parse(it.semesterCode) }
                .entries.sortedWith(compareBy(newestFirst) { it.key })
                .map { (key, courses) ->
                    val table = key?.let { classTable(it.code) }
                    MoodleCommonTerm(key?.code, courses.map { MoodleCommonCourse.of(it, table) }.sortedWith(byName))
                }
        }
    }
}

/** A course in common as its row shows it, colored as [colorKey] is. */
data class MoodleCommonCourse(val id: Int, val name: String, val details: List<String>, val colorKey: String) {
    companion object {
        /**
         * [course] as [table], the class table of its term, names it, with its number and teacher;
         * one the class table lacks under Moodle's name with its number, and one of no term
         * ([table] null) under Moodle's name alone, colored by its Moodle id.
         */
        fun of(course: MoodleEnrolledCourse, table: List<Course>?): MoodleCommonCourse {
            val moodleName = MoodleService.courseName(course.fullname).decodeHtmlEntities()
            if (table == null) return MoodleCommonCourse(course.id, moodleName, emptyList(), course.id.toString())
            val courseNo = MoodleCourseIds.assignmentCourseNo(course, table.mapTo(mutableSetOf()) { it.courseNo })
            val known = table.firstOrNull { it.courseNo == courseNo }
                ?: return MoodleCommonCourse(course.id, moodleName, listOf(courseNo), courseNo)
            val details = listOf(known.courseNo, known.instructor).filter { it.isNotBlank() }
            return MoodleCommonCourse(course.id, known.displayName, details, known.courseNo)
        }
    }
}
