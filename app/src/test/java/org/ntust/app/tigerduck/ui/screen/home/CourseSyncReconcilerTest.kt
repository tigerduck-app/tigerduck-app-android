package org.ntust.app.tigerduck.ui.screen.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.push.CourseOverrideResult
import org.ntust.app.tigerduck.push.ServerCourse
import org.ntust.app.tigerduck.shared.Course

/**
 * The tombstone rules in particular are load-bearing: an earlier version of
 * this logic deleted manually-added courses simply because the server had
 * not heard of them, which destroyed data the user had typed in by hand.
 */
class CourseSyncReconcilerTest {

    private fun local(no: String, manual: Boolean = false) =
        Course(courseNo = no, courseName = "Course $no", isManual = manual)

    private fun server(no: String, semester: String = "1141") =
        ServerCourse(courseNo = no, courseName = "Course $no", semester = semester)

    // ---- deletions ----

    @Test
    fun `a synced course the server no longer lists is tombstoned`() {
        val deleted = CourseSyncReconciler.reconcileDeletions(
            localCourses = listOf(local("A"), local("B")),
            serverCourseNos = setOf("A"),
            tombstonedNos = emptySet(),
            previouslyDeleted = emptySet(),
        )
        assertEquals(setOf("B"), deleted)
    }

    @Test
    fun `a manual course absent from the server is NOT tombstoned`() {
        // The regression this whole class exists for. A manual course may be
        // absent only because its upload failed; deleting it loses user data.
        val deleted = CourseSyncReconciler.reconcileDeletions(
            localCourses = listOf(local("A"), local("MINE", manual = true)),
            serverCourseNos = setOf("A"),
            tombstonedNos = emptySet(),
            previouslyDeleted = emptySet(),
        )
        assertTrue("manual course must survive server absence", deleted.isEmpty())
    }

    @Test
    fun `an explicit server tombstone deletes even a course we never had`() {
        val deleted = CourseSyncReconciler.reconcileDeletions(
            localCourses = emptyList(),
            serverCourseNos = setOf("A"),
            tombstonedNos = setOf("GONE"),
            previouslyDeleted = emptySet(),
        )
        assertEquals(setOf("GONE"), deleted)
    }

    @Test
    fun `reappearing on the server un-deletes, beating both tombstone rules`() {
        val deleted = CourseSyncReconciler.reconcileDeletions(
            localCourses = listOf(local("B")),
            serverCourseNos = setOf("A", "B"),
            // The server contradicts itself: B is both listed and tombstoned.
            // Presence wins, so a re-added course comes back rather than
            // staying invisible forever.
            tombstonedNos = setOf("B"),
            previouslyDeleted = setOf("A", "B"),
        )
        assertTrue("A and B are both live on the server", deleted.isEmpty())
    }

    @Test
    fun `previous deletions are carried forward when the server still omits them`() {
        val deleted = CourseSyncReconciler.reconcileDeletions(
            localCourses = emptyList(),
            serverCourseNos = setOf("A"),
            tombstonedNos = emptySet(),
            previouslyDeleted = setOf("OLD"),
        )
        assertEquals(setOf("OLD"), deleted)
    }

    // ---- merging down from the server ----

    @Test
    fun `only wanted courses in the current semester are merged, as manual`() {
        val merged = CourseSyncReconciler.coursesToMerge(
            serverCourses = listOf(server("A"), server("B"), server("C", semester = "1132")),
            wanted = setOf("A", "C"),
            semester = "1141",
        )
        assertEquals(listOf("A"), merged.map { it.courseNo })
        assertTrue("merged courses must survive a later refresh", merged.single().isManual)
    }

    @Test
    fun `a duplicated server course is merged once`() {
        val merged = CourseSyncReconciler.coursesToMerge(
            serverCourses = listOf(server("A"), server("A")),
            wanted = setOf("A"),
            semester = "1141",
        )
        assertEquals(1, merged.size)
    }

    // ---- colour overrides ----

    @Test
    fun `a colour override matches by courseNo and reports the change`() {
        val updated = CourseSyncReconciler.applyColorOverrides(
            courses = listOf(local("A")),
            overrides = listOf(CourseOverrideResult("m1", "A", "#FF0000")),
            syncColors = true,
        )
        assertEquals("#FF0000", updated!!.single().customColorHex)
    }

    @Test
    fun `a colour override falls back to matching on Moodle id`() {
        val course = Course(courseNo = "A", courseName = "A", moodleIdNumber = "m9")
        val updated = CourseSyncReconciler.applyColorOverrides(
            courses = listOf(course),
            overrides = listOf(CourseOverrideResult("m9", courseNo = null, colorHex = "#00FF00")),
            syncColors = true,
        )
        assertEquals("#00FF00", updated!!.single().customColorHex)
    }

    @Test
    fun `null when the colour is unchanged, so no cache write or widget reload`() {
        val course = Course(courseNo = "A", courseName = "A", customColorHex = "#FF0000")
        assertNull(
            CourseSyncReconciler.applyColorOverrides(
                listOf(course), listOf(CourseOverrideResult("m1", "A", "#FF0000")), syncColors = true
            )
        )
    }

    @Test
    fun `null when colour sync is off, whatever the server says`() {
        assertNull(
            CourseSyncReconciler.applyColorOverrides(
                listOf(local("A")), listOf(CourseOverrideResult("m1", "A", "#FF0000")), syncColors = false
            )
        )
    }

    // ---- custom names ----

    @Test
    fun `names merge per locale without disturbing the others`() {
        val merged = CourseSyncReconciler.mergeCustomNames(
            existing = mapOf("A" to mapOf("zh-TW" to "微積分", "en" to "Calculus")),
            overrides = listOf(CourseOverrideResult("m1", "A", null, mapOf("en" to "Calc I"))),
            syncNames = true,
        )
        assertEquals(mapOf("zh-TW" to "微積分", "en" to "Calc I"), merged!!.getValue("A"))
    }

    @Test
    fun `an empty name clears that locale, and the last one drops the course`() {
        val merged = CourseSyncReconciler.mergeCustomNames(
            existing = mapOf("A" to mapOf("en" to "Calculus")),
            overrides = listOf(CourseOverrideResult("m1", "A", null, mapOf("en" to ""))),
            syncNames = true,
        )
        assertTrue("no empty leftover entry for A", "A" !in merged!!)
    }

    @Test
    fun `null when name sync is off or nothing was sent`() {
        val o = listOf(CourseOverrideResult("m1", "A", null, mapOf("en" to "X")))
        assertNull(CourseSyncReconciler.mergeCustomNames(emptyMap(), o, syncNames = false))
        assertNull(CourseSyncReconciler.mergeCustomNames(emptyMap(), emptyList(), syncNames = true))
    }
}
