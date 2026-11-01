package org.ntust.app.tigerduck.ui.screen.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course

/**
 * A refresh overwrites the user's course list with whatever the network
 * returned. These pin what is allowed to survive that. The rules the class
 * table shares are covered by CourseRosterMergeTest.
 */
class HomeCourseMergeTest {

    private fun course(
        no: String,
        name: String = "Course $no",
        color: String? = null,
        manual: Boolean = false,
    ) = Course(courseNo = no, courseName = name, customColorHex = color, isManual = manual)

    // --- mergeRemote -------------------------------------------------------

    @Test
    fun `a colour picked locally survives a refresh that does not know it`() {
        val merged = HomeCourseMerge.mergeRemote(
            remote = listOf(course("A")),
            cached = listOf(course("A", color = "#FF0000")),
            names = emptyMap(),
            locale = "en",
            deletedNos = emptySet(),
        )
        assertEquals("#FF0000", merged.single().customColorHex)
    }

    /**
     * Home writes the file the widgets, the Live Update and the watch read, and none of them
     * looks the rename up again: dropped here, the school's name came back everywhere but the
     * class table. Looked up as the class table does, in the stored names for the locale.
     */
    @Test
    fun `a rename survives a refresh that does not know it`() {
        val merged = HomeCourseMerge.mergeRemote(
            remote = listOf(course("A", name = "Calculus (I)")),
            cached = listOf(course("A", name = "Calculus (I)")),
            names = mapOf("A" to mapOf("en" to "Calc")),
            locale = "en",
            deletedNos = emptySet(),
        )
        assertEquals("Calc", merged.single().displayName)
    }

    /** "Use Server" in Settings takes a rename out of the stored names; the cached copy goes too. */
    @Test
    fun `a rename gone from the stored names goes from the refreshed course`() {
        val merged = HomeCourseMerge.mergeRemote(
            remote = listOf(course("A", name = "Calculus (I)")),
            cached = listOf(course("A", name = "Calculus (I)").copy(customCourseName = "Calc")),
            names = emptyMap(),
            locale = "en",
            deletedNos = emptySet(),
        )
        assertEquals("Calculus (I)", merged.single().displayName)
    }

    @Test
    fun `a manually added course the roster never heard of is kept`() {
        val merged = HomeCourseMerge.mergeRemote(
            remote = listOf(course("A")),
            cached = listOf(course("A"), course("MINE", manual = true)),
            names = emptyMap(),
            locale = "en",
            deletedNos = emptySet(),
        )
        assertEquals(setOf("A", "MINE"), merged.map { it.courseNo }.toSet())
    }

    @Test
    fun `a cached non-manual course absent from the roster is dropped`() {
        // Dropping an enrolment the roster no longer lists is the whole point
        // of a refresh; only manual courses are exempt.
        val merged = HomeCourseMerge.mergeRemote(
            remote = listOf(course("A")),
            cached = listOf(course("A"), course("OLD")),
            names = emptyMap(),
            locale = "en",
            deletedNos = emptySet(),
        )
        assertEquals(listOf("A"), merged.map { it.courseNo })
    }

    @Test
    fun `a deleted course stays deleted even when the roster returns it`() {
        val merged = HomeCourseMerge.mergeRemote(
            remote = listOf(course("A"), course("B")),
            cached = emptyList(),
            names = emptyMap(),
            locale = "en",
            deletedNos = setOf("B"),
        )
        assertEquals(listOf("A"), merged.map { it.courseNo })
    }

    @Test
    fun `deletion also applies to a manual leftover`() {
        // The filter runs on the merged list, not on the fetched half, so a
        // manual course the user deleted cannot come back through the
        // leftover path.
        val merged = HomeCourseMerge.mergeRemote(
            remote = listOf(course("A")),
            cached = listOf(course("MINE", manual = true)),
            names = emptyMap(),
            locale = "en",
            deletedNos = setOf("MINE"),
        )
        assertEquals(listOf("A"), merged.map { it.courseNo })
    }

    @Test
    fun `a manual course the roster now lists is not duplicated`() {
        val merged = HomeCourseMerge.mergeRemote(
            remote = listOf(course("A")),
            cached = listOf(course("A", manual = true)),
            names = emptyMap(),
            locale = "en",
            deletedNos = emptySet(),
        )
        assertEquals(listOf("A"), merged.map { it.courseNo })
    }

    // Home and the class table write the same cache file, and
    // CourseSyncReconciler.reconcileDeletions keys its "never tombstone a
    // manual course on server-absence" guard on isManual. If only one of the
    // two merges restores the flag, whether a hand-added course survives a
    // later sync depends on which screen refreshed last.
    @Test
    fun `a manual course the roster now lists keeps its manual flag`() {
        val merged = HomeCourseMerge.mergeRemote(
            remote = listOf(course("A")),
            cached = listOf(course("A", manual = true)),
            names = emptyMap(),
            locale = "en",
            deletedNos = emptySet(),
        )
        assertEquals(true, merged.single().isManual)
    }

    @Test
    fun `a course that was never manual does not become manual`() {
        val merged = HomeCourseMerge.mergeRemote(
            remote = listOf(course("A")),
            cached = listOf(course("A")),
            names = emptyMap(),
            locale = "en",
            deletedNos = emptySet(),
        )
        assertEquals(false, merged.single().isManual)
    }

    @Test
    fun `a course the cache has never seen is not manual`() {
        val merged = HomeCourseMerge.mergeRemote(
            remote = listOf(course("A")),
            cached = emptyList(),
            names = emptyMap(),
            locale = "en",
            deletedNos = emptySet(),
        )
        assertEquals(false, merged.single().isManual)
    }

    /**
     * Home looks a transcript class up even when Moodle does not list it, and the lookup gives
     * it the school's Moodle ID. Taken onto a course added here, that ID makes it read as merged
     * from the server, which a past term's rebuild drops (tigerduck-app#890). It keeps the
     * identity it was added with, none, as the class table's merge keeps it.
     */
    @Test
    fun `a course added here keeps no Moodle ID when the school also lists it`() {
        val merged = HomeCourseMerge.mergeRemote(
            remote = listOf(course("A").copy(moodleIdNumber = "1151A", moodleNumericCourseId = 7)),
            cached = listOf(course("A", manual = true)),
            names = emptyMap(),
            locale = "en",
            deletedNos = emptySet(),
        ).single()
        assertEquals(true, merged.isManual)
        assertNull(merged.moodleIdNumber)
        assertNull(merged.moodleNumericCourseId)
    }

    /**
     * A term course selection gave nothing for only gains the transcript's classes. The class
     * table can save the term while Home's lookups run, and its courses are not in Home's
     * answers; only gaining, Home removes none of them, but a course deleted stays deleted.
     */
    @Test
    fun `a term only gaining keeps every cached course the fetch does not list, but a deleted one`() {
        val merged = HomeCourseMerge.mergeRemote(
            remote = listOf(course("A")),
            cached = listOf(course("A"), course("B"), course("GONE")),
            names = emptyMap(),
            locale = "en",
            deletedNos = setOf("GONE"),
            onlyGains = true,
        )
        assertEquals(listOf("A", "B"), merged.map { it.courseNo })
    }
}
