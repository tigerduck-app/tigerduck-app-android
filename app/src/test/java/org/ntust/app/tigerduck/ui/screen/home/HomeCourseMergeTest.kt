package org.ntust.app.tigerduck.ui.screen.home

import org.junit.Assert.assertEquals
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
            deletedNos = emptySet(),
        )
        assertEquals("#FF0000", merged.single().customColorHex)
    }

    @Test
    fun `a manually added course the roster never heard of is kept`() {
        val merged = HomeCourseMerge.mergeRemote(
            remote = listOf(course("A")),
            cached = listOf(course("A"), course("MINE", manual = true)),
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
            deletedNos = emptySet(),
        )
        assertEquals(listOf("A"), merged.map { it.courseNo })
    }

    @Test
    fun `a deleted course stays deleted even when the roster returns it`() {
        val merged = HomeCourseMerge.mergeRemote(
            remote = listOf(course("A"), course("B")),
            cached = emptyList(),
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
            deletedNos = setOf("MINE"),
        )
        assertEquals(listOf("A"), merged.map { it.courseNo })
    }

    @Test
    fun `a manual course the roster now lists is not duplicated`() {
        val merged = HomeCourseMerge.mergeRemote(
            remote = listOf(course("A")),
            cached = listOf(course("A", manual = true)),
            deletedNos = emptySet(),
        )
        assertEquals(listOf("A"), merged.map { it.courseNo })
    }
}
