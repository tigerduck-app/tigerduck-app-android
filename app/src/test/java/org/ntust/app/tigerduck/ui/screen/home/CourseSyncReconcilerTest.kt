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
        selectionDropped: Set<String> = emptySet(),
        serverKnown: Set<String> = emptySet(),
    ) = CourseSyncReconciler.reconcileSemester(
        semester = semester,
        localCourses = local,
        serverRows = rows,
        tombstoneNos = tombstoneNos,
        tombstones = tombstones,
        serverKnownNos = serverKnown,
        graceCourseNos = grace,
        selectionDroppedNos = selectionDropped,
    )

    /**
     * The 加退選 case, end to end. The student dropped "B"; the backend still
     * carries it, because an upload only ever upserts and no explicit DELETE
     * was sent. Merging it back would put it on the timetable as a manual
     * course — the one shape a later refresh is required to preserve.
     */
    @Test
    fun `a course 選課 dropped is not merged back from the server`() {
        val out = reconcile(
            local = listOf(local("A")),
            rows = listOf(server("A"), server("B")),
            selectionDropped = setOf("B"),
        )
        assertTrue(out.merged.none { it.courseNo == "B" })
    }

    /**
     * And it must not count as evidence of presence either: a dropped row
     * left in scope would un-hide a course the user had deleted by hand.
     */
    @Test
    fun `a dropped row does not un-hide a course the user deleted`() {
        val out = reconcile(
            local = listOf(local("A")),
            rows = listOf(server("A"), server("B")),
            tombstones = setOf("1141:B"),
            selectionDropped = setOf("B"),
        )
        assertTrue("1141:B" in out.tombstones)
    }

    /** Without the drop, the same row merges — the guard is what changes it. */
    @Test
    fun `the same server row still merges when 選課 has not dropped it`() {
        val out = reconcile(local = listOf(local("A")), rows = listOf(server("A"), server("B")))
        assertEquals(listOf("B"), out.merged.map { it.courseNo })
    }

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
    fun `a manual course the server HAS listed is tombstoned once it drops off`() {
        // The reported sync bug. Every row merged down from the cloud is
        // stamped isManual by `toCourse`, so blanket immunity made it
        // immortal: no roster could retire it and this device uploaded it
        // back on every refresh, undoing the other device's reset.
        val out = reconcile(
            local = listOf(local("A"), local("M", manual = true)),
            rows = listOf(server("A")),
            serverKnown = setOf("M"),
        )
        assertEquals(setOf("1141:M"), out.tombstones)
    }

    @Test
    fun `the server-known set grows with every term the snapshot lists`() {
        val out = reconcile(rows = listOf(server("A"), server("B")), serverKnown = setOf("Z"))
        assertEquals(setOf("A", "B", "Z"), out.serverKnownNos)
    }

    @Test
    fun `a term the server has nothing for does not forget what it knew`() {
        // The empty-server branch returns early. Dropping the set there
        // would re-arm immunity for every course on the next sync.
        val out = reconcile(local = listOf(local("A")), serverKnown = setOf("A"))
        assertEquals(setOf("A"), out.serverKnownNos)
    }

    @Test
    fun `a misfiled row does not count as the server knowing that course`() {
        // A row filed under the wrong term is not a roster, so it must not
        // strip a hand-typed course of its immunity either.
        val out = reconcile(
            semester = "1142",
            local = listOf(local("M", manual = true)),
            rows = listOf(server("A", semester = "1142"), server("M", semester = "1142", moodleId = "1151M")),
        )
        assertFalse("M" in out.serverKnownNos)
        assertTrue(out.tombstones.isEmpty())
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

    /**
     * A semester reset, seen from the other phone. The backend has no rows
     * for the term and a tombstone for every course it used to hold. Reading
     * the empty term as "first sync, push what we have" kept the old roster
     * on screen and re-uploaded it on every refresh — which the backend
     * refuses, silently — so the reset never arrived here.
     */
    @Test
    fun `a reset term hides every tombstoned course even though the server is empty`() {
        val out = reconcile(
            local = listOf(local("A"), local("B")),
            rows = emptyList(),
            tombstoneNos = setOf("A", "B"),
        )
        assertEquals(setOf("1141:A", "1141:B"), out.tombstones)
        assertFalse("the reset roster must not be pushed back", out.uploadLocal)
    }

    @Test
    fun `an empty term still uploads the courses its tombstones do not name`() {
        // One course deleted elsewhere, one this device added and has never
        // managed to sync: only the second is a first-sync upload.
        val out = reconcile(
            local = listOf(local("A"), local("M", manual = true)),
            rows = emptyList(),
            tombstoneNos = setOf("A"),
        )
        assertEquals(setOf("1141:A"), out.tombstones)
        assertEquals(listOf("M"), out.toUpload.map { it.courseNo })
    }

    @Test
    fun `an empty term does not re-upload a course this device already hid`() {
        // Hidden here earlier (a delete by hand, or a tombstone from a
        // previous sync) — silence from the server is no reason to push it
        // back up, in either the scoped or the legacy bare shape.
        val scoped = reconcile(local = listOf(local("A"), local("B")), tombstones = setOf("1141:A"))
        assertEquals(listOf("B"), scoped.toUpload.map { it.courseNo })
        val bare = reconcile(local = listOf(local("A"), local("B")), tombstones = setOf("A"))
        assertEquals(listOf("B"), bare.toUpload.map { it.courseNo })
    }

    /**
     * The other half of the manual-course rule. A tombstone can only name a
     * course some device once uploaded; a hand-typed course no snapshot has
     * carried is not that course, even when the numbers match — say the
     * term was reset elsewhere and the student typed it back in here before
     * the re-add upload landed.
     */
    @Test
    fun `a tombstone does not delete a hand-typed course the server has never seen`() {
        val out = reconcile(
            local = listOf(local("A", manual = true)),
            rows = emptyList(),
            tombstoneNos = setOf("A"),
        )
        assertTrue(out.tombstones.isEmpty())
        assertEquals(listOf("A"), out.toUpload.map { it.courseNo })
    }

    @Test
    fun `a tombstone still deletes a manual course once a snapshot has carried it`() {
        // Rows merged down from the cloud are stamped manual by toCourse;
        // that must not shield them from the reset that removed them.
        val out = reconcile(
            local = listOf(local("A", manual = true)),
            rows = emptyList(),
            tombstoneNos = setOf("A"),
            serverKnown = setOf("A"),
        )
        assertEquals(setOf("1141:A"), out.tombstones)
        assertTrue(out.toUpload.isEmpty())
    }

    // ---- keeping the cache honest ----

    @Test
    fun `pruneHidden drops what the tombstones hide and is null when nothing is`() {
        val courses = listOf(local("A"), local("B"), local("C"))
        assertEquals(
            listOf("B"),
            CourseSyncReconciler.pruneHidden("1141", courses, setOf("1141:A", "C"))!!.map { it.courseNo },
        )
        assertNull(CourseSyncReconciler.pruneHidden("1141", courses, setOf("1132:A")))
    }

    /**
     * A snapshot is fetched, then reconciled after the assignment overrides
     * and their PATCHes. One fetched before a reset still carries the
     * pre-reset roster; merged into the freshly cleared cache it put the
     * whole roster back, for the reset's own refetch to upload.
     */
    @Test
    fun `a snapshot fetched before this device reset a term leaves that term alone`() {
        val resetAt = mapOf("1141" to 1_000L, "1132" to 500L)
        assertEquals(setOf("1141"), CourseSyncReconciler.termsResetAfter(fetchedAtMs = 700L, resetAt = resetAt))
        assertTrue(CourseSyncReconciler.termsResetAfter(fetchedAtMs = 1_000L, resetAt = resetAt).isEmpty())
        // And a term whose DELETE is in flight right now, whatever the stamp says.
        assertEquals(
            setOf("1151"),
            CourseSyncReconciler.termsResetAfter(fetchedAtMs = 1_000L, resetAt = resetAt, resetting = setOf("1151")),
        )
        // A stamp is dropped once a snapshot clearly newer than it has been
        // reconciled, so an overlapping sync seconds behind is still caught
        // but a clock that steps backwards cannot mute the term for good.
        assertEquals(setOf("1132"), CourseSyncReconciler.resetStampsOutlived(fetchedAtMs = 61_000L, resetAt = resetAt))
        assertTrue(CourseSyncReconciler.resetStampsOutlived(fetchedAtMs = 30_000L, resetAt = resetAt).isEmpty())
        val terms = CourseSyncReconciler.semestersToReconcile(
            serverCourses = listOf(server("A", semester = "1141")),
            tombstones = emptyList(),
            catalogue = listOf("1141", "1151"),
            excluding = setOf("1141"),
        )
        assertEquals(listOf("1151"), terms)
    }

    @Test
    fun `pruneHidden returns an empty list, not null, when every course is hidden`() {
        // The reset-elsewhere shape: the caller must write the empty cache.
        val pruned = CourseSyncReconciler.pruneHidden("1141", listOf(local("A")), setOf("1141:A"))
        assertEquals(emptyList<Course>(), pruned)
    }

    @Test
    fun `pruneHidden without legacy entries only honours this term's scoped keys`() {
        // The worker has not pinned bare entries to terms, so a bare "C" —
        // a deletion made in some other term — must not delete C here.
        val courses = listOf(local("A"), local("C"))
        val pruned = CourseSyncReconciler.pruneHidden("1141", courses, setOf("1141:A", "C"), legacyBare = false)
        assertEquals(listOf("C"), pruned!!.map { it.courseNo })
    }

    /**
     * The backend's rule, mirrored: a reset tombstone does not bind the
     * device that wrote it, whose next upload releases it. Between that
     * DELETE and the upload, this device must not read its own tombstones
     * as "hide everything" — the refetch filters by the tombstone store and
     * would then upload nothing, leaving the term hidden for good.
     */
    @Test
    fun `this device's own reset tombstones do not count`() {
        val own = tombstone("A", "1141").copy(deletedByReset = true, deletedByThisDevice = true)
        val elsewhere = tombstone("B", "1141").copy(deletedByReset = true)
        val ownSingleDelete = tombstone("C", "1141").copy(deletedByThisDevice = true)
        assertEquals(
            setOf("B", "C"),
            CourseSyncReconciler.tombstoneNosFor("1141", listOf(own, elsewhere, ownSingleDelete)),
        )
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
