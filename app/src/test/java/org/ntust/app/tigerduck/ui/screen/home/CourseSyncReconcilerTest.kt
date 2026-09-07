package org.ntust.app.tigerduck.ui.screen.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.push.CourseOverrideResult
import org.ntust.app.tigerduck.push.CourseTombstone
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

    private fun server(no: String, semester: String = "1141", moodleId: String? = null) =
        ServerCourse(
            courseNo = no,
            courseName = "Course $no",
            semester = semester,
            moodleId = moodleId,
        )

    private fun tombstone(no: String, semester: String) =
        CourseTombstone(courseKey = "client:$semester:$no", courseNo = no, semester = semester, deletedAt = "")

    // ---- deletions, per semester ----
    //
    // These carry over the pre-semester rules unchanged; what is new is that
    // each one now holds for one term without reaching into the others.

    private fun reconcile(
        semester: String = "1141",
        local: List<Course> = emptyList(),
        rows: List<ServerCourse> = emptyList(),
        tombstoneNos: Set<String> = emptySet(),
        tombstones: Set<String> = emptySet(),
        grace: Set<String> = emptySet(),
    ) = CourseSyncReconciler.reconcileSemester(
        semester = semester,
        localCourses = local,
        serverRows = rows,
        tombstoneNos = tombstoneNos,
        tombstones = tombstones,
        graceCourseNos = grace,
    )

    @Test
    fun `a synced course the server no longer lists is tombstoned`() {
        val out = reconcile(local = listOf(local("A"), local("B")), rows = listOf(server("A")))
        assertEquals(setOf("1141:B"), out.tombstones)
    }

    @Test
    fun `a manual course absent from the server is NOT tombstoned`() {
        val out = reconcile(
            local = listOf(local("A"), local("M", manual = true)),
            rows = listOf(server("A")),
        )
        assertTrue("a hand-typed course must survive server silence", out.tombstones.isEmpty())
    }

    @Test
    fun `an explicit server tombstone deletes even a course we never had`() {
        val out = reconcile(rows = listOf(server("A")), tombstoneNos = setOf("Z"))
        assertEquals(setOf("1141:Z"), out.tombstones)
    }

    @Test
    fun `reappearing on the server un-deletes, beating both tombstone rules`() {
        val out = reconcile(
            local = listOf(local("A")),
            rows = listOf(server("A")),
            tombstoneNos = setOf("A"),
            tombstones = setOf("1141:A"),
        )
        assertTrue("a course back on the server must come back", out.tombstones.isEmpty())
    }

    @Test
    fun `a delete still in flight is not un-deleted by the row it is racing`() {
        val out = reconcile(
            rows = listOf(server("A")),
            tombstones = setOf("1141:A"),
            grace = setOf("A"),
        )
        assertEquals(setOf("1141:A"), out.tombstones)
    }

    @Test
    fun `previous deletions are carried forward when the server still omits them`() {
        val out = reconcile(
            local = listOf(local("A")),
            rows = listOf(server("A")),
            tombstones = setOf("1132:OLD"),
        )
        assertEquals(setOf("1132:OLD"), out.tombstones)
    }

    // ---- the reason any of this is per-semester ----

    @Test
    fun `a retaken course hidden in one term stays hidden when another term lists it`() {
        // The bug the flattened reconcile had: 1151 carries CS101 because the
        // student retook it, which un-hid the 1142 CS101 they had deleted.
        val out = reconcile(
            semester = "1142",
            local = listOf(local("CS101")),
            rows = listOf(server("CS101", semester = "1151")).filter { it.semester == "1142" },
            tombstones = setOf("1142:CS101"),
        )
        assertEquals("1142's deletion must survive 1151 listing the number", setOf("1142:CS101"), out.tombstones)
    }

    @Test
    fun `a term the server has nothing for is uploaded, not treated as deleted`() {
        val out = reconcile(local = listOf(local("A"), local("B")), rows = emptyList())
        assertTrue("an empty term must not tombstone the roster", out.tombstones.isEmpty())
        assertTrue("it is a first sync for this term — push what we have", out.uploadLocal)
    }

    @Test
    fun `a misfiled row neither merges nor counts as the course being present`() {
        // A 1151 Moodle id filed under 1142 — the 2026-08 attribution bug.
        val out = reconcile(
            semester = "1142",
            local = listOf(local("CS101")),
            rows = listOf(server("CS101", moodleId = "1151CS101")),
        )
        assertTrue("a misfiled row is not a roster, so this term is empty", out.uploadLocal)
        assertTrue(out.merged.isEmpty())
    }

    @Test
    fun `isFiled matches only a term code followed by the row's own course number`() {
        // The shape the 2026-08 bug produced, and the only one that may be
        // dropped: 1151's id on a row filed under 1142.
        assertFalse(CourseSyncReconciler.isFiled("1151CS101", "CS101", "1142"))
        assertTrue(CourseSyncReconciler.isFiled("1142CS101", "CS101", "1142"))

        // False positives the looser iOS predicate produces. A plain numeric
        // id is not a term code, so the row is real and must be kept.
        assertTrue("a plain numeric id carries no term", CourseSyncReconciler.isFiled("123456", "CS101", "1142"))
        assertTrue(CourseSyncReconciler.isFiled("777", "CS101", "1142"))
        assertTrue(CourseSyncReconciler.isFiled(null, "CS101", "1142"))
        assertTrue("an id that does not end in the course number is not this bug",
            CourseSyncReconciler.isFiled("1151OTHER", "CS101", "1142"))
    }

    // ---- merging down from the server ----

    @Test
    fun `server rows missing locally are merged as manual`() {
        val out = reconcile(local = listOf(local("A")), rows = listOf(server("A"), server("B")))
        assertEquals(listOf("B"), out.merged.map { it.courseNo })
        assertTrue("merged courses must survive a later refresh", out.merged.single().isManual)
    }

    @Test
    fun `a duplicated server course is merged once`() {
        val out = reconcile(rows = listOf(server("A"), server("A")))
        assertEquals(1, out.merged.size)
    }

    @Test
    fun `a course hidden in this term is not merged back in`() {
        val out = reconcile(
            rows = listOf(server("A"), server("B")),
            tombstones = setOf("1141:A"),
            grace = setOf("A"),
        )
        assertEquals(listOf("B"), out.merged.map { it.courseNo })
    }

    // ---- which terms get reconciled ----

    @Test
    fun `terms come from the server and the catalogue, deduplicated`() {
        val terms = CourseSyncReconciler.semestersToReconcile(
            serverCourses = listOf(server("A", semester = "1132"), server("B", semester = "")),
            tombstones = listOf(tombstone("Z", "1122")),
            catalogue = listOf("1141", "1132"),
        )
        assertEquals(listOf("1122", "1132", "1141"), terms)
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
