package org.ntust.app.tigerduck.ui.screen.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.model.PendingCourseOverride
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
        pendingAdds: Set<String> = emptySet(),
    ) = CourseSyncReconciler.reconcileSemester(
        semester = semester,
        localCourses = local,
        serverRows = rows,
        tombstoneNos = tombstoneNos,
        tombstones = tombstones,
        serverKnownNos = serverKnown,
        graceCourseNos = grace,
        pendingAddNos = pendingAdds,
        selectionDroppedNos = selectionDropped,
    )

    /**
     * A course deleted here whose DELETE has not landed yet (the outbox,
     * [org.ntust.app.tigerduck.data.PendingCourseDeletes], still holds it):
     * the snapshot lists its row, and with no tombstone here — a course taken
     * back out of Add course writes none — the row would come back as a
     * hand-added course, which the DELETE then removes again, or never does.
     * As the iPhone's `mergesListedCourse(... deleting)`.
     */
    @Test
    fun `a server row whose delete from here is still queued is not merged back`() {
        val out = reconcile(
            local = listOf(local("A")),
            rows = listOf(server("A"), server("B")),
            grace = setOf("B"),
        )
        assertTrue(out.merged.none { it.courseNo == "B" })
        // Nor hidden on its account: the DELETE, once it lands, ends the matter.
        assertEquals(emptySet<String>(), out.tombstones)
    }

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

    /**
     * A course the server once listed, deleted, then added back here by hand
     * (the iPhone's tigerduck-app#727): its number is known, so neither rule
     * above keeps it, and an upload that fails twice used to end here. While
     * its add waits in PendingCourseAdds it is this phone's latest word: the
     * add forces its key past the tombstone once it lands.
     */
    @Test
    fun `a course added back here is kept while its add waits, past the server's tombstone`() {
        val out = reconcile(
            local = listOf(local("A"), local("M", manual = true)),
            rows = listOf(server("A")),
            tombstoneNos = setOf("M"),
            serverKnown = setOf("M"),
            pendingAdds = setOf("M"),
        )
        assertEquals(emptySet<String>(), out.tombstones)
    }

    @Test
    fun `a course added back here is kept while its add waits, past the server's silence`() {
        val out = reconcile(
            local = listOf(local("A"), local("M", manual = true)),
            rows = listOf(server("A")),
            serverKnown = setOf("M"),
            pendingAdds = setOf("M"),
        )
        assertEquals(emptySet<String>(), out.tombstones)
    }

    @Test
    fun `in a term the server has nothing for, a course whose add waits goes up with the rest`() {
        val out = reconcile(
            local = listOf(local("M", manual = true)),
            rows = emptyList(),
            tombstoneNos = setOf("M"),
            serverKnown = setOf("M"),
            pendingAdds = setOf("M"),
        )
        assertEquals(emptySet<String>(), out.tombstones)
        assertEquals(listOf("M"), out.toUpload.map { it.courseNo })
    }

    /** Only the waiting add keeps it: another known course the server dropped still goes. */
    @Test
    fun `a waiting add keeps only its own course`() {
        val out = reconcile(
            local = listOf(local("A"), local("M", manual = true), local("N", manual = true)),
            rows = listOf(server("A")),
            tombstoneNos = setOf("M"),
            serverKnown = setOf("M", "N"),
            pendingAdds = setOf("M"),
        )
        assertEquals(setOf("1141:N"), out.tombstones)
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

    /**
     * Moodle spells a summer term "114h", NTUST "114H". The raw compare filed
     * every summer row under another term, so the reconcile ignored them.
     */
    @Test
    fun `isFiled compares a summer term case-insensitively`() {
        assertTrue(CourseSyncReconciler.isFiled("114hCS101", "CS101", "114H"))
        assertTrue(CourseSyncReconciler.isFiled("114HCS101", "CS101", "114H"))
        assertFalse(CourseSyncReconciler.isFiled("113hCS101", "CS101", "114H"))
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
        assertEquals("#FF0000", updated!!.courses.single().customColorHex)
        assertEquals(emptyMap<String, String>(), updated.moved)
    }

    @Test
    fun `a colour override falls back to matching on Moodle id`() {
        val course = Course(courseNo = "A", courseName = "A", moodleIdNumber = "m9")
        val updated = CourseSyncReconciler.applyColorOverrides(
            courses = listOf(course),
            overrides = listOf(CourseOverrideResult("m9", courseNo = null, colorHex = "#00FF00")),
            syncColors = true,
        )
        assertEquals("#00FF00", updated!!.courses.single().customColorHex)
    }

    /**
     * A course taken again keeps its number, and the snapshot carries every term's rows: the
     * row of this offering, by its Moodle id, is the one whose colour it takes.
     */
    @Test
    fun `a course taken again takes its own term's colour, not an earlier term's`() {
        val course = Course(courseNo = "A", courseName = "A", moodleIdNumber = "m1141")
        val updated = CourseSyncReconciler.applyColorOverrides(
            courses = listOf(course),
            overrides = listOf(
                CourseOverrideResult("m1122", "A", "#FF0000", semester = "1122"),
                CourseOverrideResult("m1141", "A", "#00FF00", semester = "1141"),
            ),
            syncColors = true,
        )
        assertEquals("#00FF00", updated!!.courses.single().customColorHex)
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

    /** tigerduck-app-android#380: a snapshot fetched before a PATCH landed still has the old colour. */
    @Test
    fun `a colour this phone has waiting to send is kept over the server's`() {
        val a = Course(courseNo = "A", courseName = "A", moodleIdNumber = "mA", customColorHex = "#FF0000")
        val b = Course(courseNo = "B", courseName = "B", moodleIdNumber = "mB", customColorHex = "#00FF00")
        val rows = listOf(CourseOverrideResult("mA", "A", "#0000FF"), CourseOverrideResult("mB", "B", "#00FF00"))
        assertNull(
            CourseSyncReconciler.applyColorOverrides(
                listOf(a, b), rows, syncColors = true, held = setOf(PendingCourseOverride.colorKey("mA")),
            )
        )
    }

    @Test
    fun `a colour waiting to send is the latest pick, and a course the server has on it moves off`() {
        val a = Course(courseNo = "A", courseName = "A", moodleIdNumber = "1141A", customColorHex = "#FF0000")
        val b = Course(courseNo = "B", courseName = "B", moodleIdNumber = "1141B", customColorHex = "#00FF00")
        // Picked red for B on another device, while this phone's red for A waits to go up.
        val rows = listOf(colour("A", "#0000FF", "2026-03-01T00:00:00+00:00"), colour("B", "#FF0000", "2026-03-02T00:00:00+00:00"))
        val outcome = CourseSyncReconciler.applyColorOverrides(
            listOf(a, b), rows, syncColors = true, held = setOf(PendingCourseOverride.colorKey("1141A")),
        )!!
        assertEquals("#FF0000", outcome.courses.single { it.courseNo == "A" }.customColorHex)
        val movedTo = outcome.courses.single { it.courseNo == "B" }.customColorHex
        assertTrue("B left red: $movedTo", movedTo != null && movedTo.uppercase() != "#FF0000")
        assertEquals(mapOf("B" to movedTo), outcome.moved)
    }

    @Test
    fun `a colour waiting to send keeps its course even with no row of its own in the snapshot`() {
        val a = Course(courseNo = "A", courseName = "A", moodleIdNumber = "1141A", customColorHex = "#FF0000")
        val b = Course(courseNo = "B", courseName = "B", moodleIdNumber = "1141B", customColorHex = "#00FF00")
        // The server never had a colour for A; B's red there would move A off the red it has waiting.
        val outcome = CourseSyncReconciler.applyColorOverrides(
            listOf(a, b), listOf(colour("B", "#FF0000", null)), syncColors = true,
            held = setOf(PendingCourseOverride.colorKey("1141A")),
        )!!
        assertEquals("#FF0000", outcome.courses.single { it.courseNo == "A" }.customColorHex)
        val movedTo = outcome.courses.single { it.courseNo == "B" }.customColorHex
        assertTrue("B left red: $movedTo", movedTo != null && movedTo.uppercase() != "#FF0000")
        assertEquals(mapOf("B" to movedTo), outcome.moved)
    }

    private fun colour(no: String, hex: String, at: String?) =
        CourseOverrideResult("1141$no", no, hex, colorHexUpdatedAt = at)

    @Test
    fun `two courses the server holds on one colour settle apart, the latest pick keeping it`() {
        // An older build left A and B both red on the server. Applied row by
        // row both stayed red on every Android phone (tigerduck-app-android#295).
        val outcome = CourseSyncReconciler.applyColorOverrides(
            courses = listOf(local("A"), local("B"), local("C")),
            overrides = listOf(
                colour("B", "#FF6B6B", "2026-10-10T08:00:00.000002+00:00"),
                colour("A", "#FF6B6B", "2026-10-10T08:00:00.000001+00:00"),
            ),
            syncColors = true,
        )!!

        val byNo = outcome.courses.associateBy { it.courseNo }
        assertEquals("#FF6B6B", byNo.getValue("B").customColorHex)
        val moved = byNo.getValue("A").customColorHex
        assertTrue("A moved to a colour of its own", moved != null && moved != "#FF6B6B")
        assertEquals("the server is told where A went", mapOf("A" to moved), outcome.moved)
        assertNull(byNo.getValue("C").customColorHex)
    }

    @Test
    fun `a row with no pick time counts as the oldest, and ties go by course number`() {
        val outcome = CourseSyncReconciler.applyColorOverrides(
            courses = listOf(local("A"), local("B"), local("C"), local("D")),
            overrides = listOf(
                colour("A", "#FF6B6B", "2026-10-10T08:00:00+00:00"),
                colour("C", "#FF6B6B", null),
                colour("D", "#4ECDC4", "2026-10-10T09:00:00+00:00"),
                colour("B", "#4ECDC4", "2026-10-10T09:00:00+00:00"),
            ),
            syncColors = true,
        )!!

        val byNo = outcome.courses.associateBy { it.courseNo }
        assertEquals("#FF6B6B", byNo.getValue("A").customColorHex)
        assertEquals("#4ECDC4", byNo.getValue("D").customColorHex)
        assertEquals(setOf("B", "C"), outcome.moved.keys)
        for ((no, hex) in outcome.moved) assertEquals(hex, byNo.getValue(no).customColorHex)
    }

    @Test
    fun `the same rows in any order settle the same way`() {
        val rows = listOf(
            colour("A", "#FF6B6B", "2026-10-10T08:00:01+00:00"),
            colour("B", "#FF6B6B", "2026-10-10T08:00:02+00:00"),
            colour("C", "#FF6B6B", "2026-10-10T08:00:03+00:00"),
        )
        val courses = listOf(local("A"), local("B"), local("C"))

        val forward = CourseSyncReconciler.applyColorOverrides(courses, rows, syncColors = true)!!
        val backward = CourseSyncReconciler.applyColorOverrides(courses, rows.reversed(), syncColors = true)!!

        assertEquals(forward, backward)
        assertEquals(setOf("A", "B"), forward.moved.keys)
        assertEquals(3, forward.courses.map { it.customColorHex }.toSet().size)
    }

    @Test
    fun `a course pinned here to a colour the server gives another course moves off it`() {
        val outcome = CourseSyncReconciler.applyColorOverrides(
            courses = listOf(local("A"), Course(courseNo = "B", courseName = "B", customColorHex = "#FF6B6B")),
            overrides = listOf(colour("A", "#FF6B6B", "2026-10-10T08:00:00+00:00")),
            syncColors = true,
        )!!

        val byNo = outcome.courses.associateBy { it.courseNo }
        assertEquals("#FF6B6B", byNo.getValue("A").customColorHex)
        assertEquals(setOf("B"), outcome.moved.keys)
    }

    @Test
    fun `colours the server already holds apart move nothing`() {
        val courses = listOf(
            Course(courseNo = "A", courseName = "A", customColorHex = "#FF6B6B"),
            Course(courseNo = "B", courseName = "B", customColorHex = "#4ECDC4"),
        )
        assertNull(
            CourseSyncReconciler.applyColorOverrides(
                courses,
                listOf(colour("A", "#FF6B6B", null), colour("B", "#4ECDC4", null)),
                syncColors = true,
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

    /** tigerduck-app-android#380. */
    @Test
    fun `a name this phone has waiting to send is kept over the server's, the other locales merging still`() {
        val merged = CourseSyncReconciler.mergeCustomNames(
            existing = mapOf("A" to mapOf("en" to "Calc I")),
            overrides = listOf(CourseOverrideResult("mA", "A", null, mapOf("en" to "Calculus", "zh-TW" to "微積分"))),
            syncNames = true,
            held = setOf(PendingCourseOverride.nameKey("mA", "en")),
        )
        assertEquals(mapOf("en" to "Calc I", "zh-TW" to "微積分"), merged!!.getValue("A"))
    }

    @Test
    fun `null when every name the server sends is waiting to go up from here`() {
        val merged = CourseSyncReconciler.mergeCustomNames(
            existing = emptyMap(),
            overrides = listOf(CourseOverrideResult("mA", "A", null, mapOf("en" to "Calculus"))),
            syncNames = true,
            held = setOf(PendingCourseOverride.nameKey("mA", "en")),
        )
        assertNull(merged)
    }

    @Test
    fun `null when name sync is off or nothing was sent`() {
        val o = listOf(CourseOverrideResult("m1", "A", null, mapOf("en" to "X")))
        assertNull(CourseSyncReconciler.mergeCustomNames(emptyMap(), o, syncNames = false))
        assertNull(CourseSyncReconciler.mergeCustomNames(emptyMap(), emptyList(), syncNames = true))
    }

    // ---- the courses re-enable check ----

    @Test
    fun `the course list is held while the courses re-enable check waits for the user`() {
        // A reconcile meanwhile un-hides a course deleted here while sync was
        // off, since the server still lists it, and Use Local could then no
        // longer keep it deleted (tigerduck-app-android#153).
        assertFalse(CourseSyncReconciler.syncsCourseList(switchOn = true, pendingConflictCategories = setOf("courses")))
        assertFalse(
            CourseSyncReconciler.syncsCourseList(switchOn = true, pendingConflictCategories = setOf("assignments", "courses"))
        )
    }

    @Test
    fun `the course list syncs when its switch is on and no courses check is pending`() {
        assertTrue(CourseSyncReconciler.syncsCourseList(switchOn = true, pendingConflictCategories = emptySet()))
        assertTrue(
            CourseSyncReconciler.syncsCourseList(
                switchOn = true,
                pendingConflictCategories = setOf("course_colors", "course_names", "assignments"),
            )
        )
    }

    @Test
    fun `the course list does not sync with its switch off`() {
        assertFalse(CourseSyncReconciler.syncsCourseList(switchOn = false, pendingConflictCategories = emptySet()))
    }

    // ---- the colour and name re-enable checks ----

    @Test
    fun `the server's colours are held while the colours re-enable check waits for the user`() {
        // Applied meanwhile, they would answer the check for the user, and
        // Use Local could no longer keep this phone's colours
        // (tigerduck-app-android#296).
        assertFalse(CourseSyncReconciler.syncsCourseColors(switchOn = true, pendingConflictCategories = setOf("course_colors")))
        assertTrue(
            CourseSyncReconciler.syncsCourseColors(
                switchOn = true,
                pendingConflictCategories = setOf("courses", "course_names", "assignments"),
            )
        )
        assertFalse(CourseSyncReconciler.syncsCourseColors(switchOn = false, pendingConflictCategories = emptySet()))
    }

    @Test
    fun `the server's names are held while the names re-enable check waits for the user`() {
        assertFalse(CourseSyncReconciler.syncsCourseNames(switchOn = true, pendingConflictCategories = setOf("course_names")))
        assertTrue(
            CourseSyncReconciler.syncsCourseNames(
                switchOn = true,
                pendingConflictCategories = setOf("courses", "course_colors", "assignments"),
            )
        )
        assertFalse(CourseSyncReconciler.syncsCourseNames(switchOn = false, pendingConflictCategories = emptySet()))
    }

    // ---- a course reset made on another device ----

    @Test
    fun `the account's first sync here remembers the server's reset without applying it`() {
        assertEquals(
            CourseSyncReconciler.CourseReset(apply = false, remember = "2026-10-10T16:42:49.666640+00:00"),
            CourseSyncReconciler.coursesReset(server = "2026-10-10T16:42:49.666640+00:00", lastSeen = null),
        )
        assertEquals(
            CourseSyncReconciler.CourseReset(apply = false, remember = ""),
            CourseSyncReconciler.coursesReset(server = null, lastSeen = null),
        )
    }

    @Test
    fun `a reset newer than the one remembered is applied, whatever this phone's clock says`() {
        val reset = CourseSyncReconciler.coursesReset(
            server = "2026-10-10T16:42:49.666641+00:00",
            lastSeen = "2026-10-10T16:42:49.666640+00:00",
        )
        assertEquals(CourseSyncReconciler.CourseReset(apply = true, remember = "2026-10-10T16:42:49.666641+00:00"), reset)
    }

    @Test
    fun `the reset already remembered is not applied again`() {
        val at = "2026-10-10T16:42:49.666640+00:00"
        assertEquals(
            CourseSyncReconciler.CourseReset(apply = false, remember = at),
            CourseSyncReconciler.coursesReset(server = at, lastSeen = at),
        )
        // isoformat() drops a zero fraction; the same instant written either way is one reset.
        assertFalse(
            CourseSyncReconciler.coursesReset(
                server = "2026-10-10T16:42:49+00:00",
                lastSeen = "2026-10-10T16:42:49.000000+00:00",
            ).apply
        )
    }

    @Test
    fun `the first reset after a sync that found none counts as new`() {
        assertTrue(CourseSyncReconciler.coursesReset(server = "2026-10-10T16:42:49+00:00", lastSeen = "").apply)
    }

    @Test
    fun `no reset, or one that does not parse, keeps what was remembered`() {
        val at = "2026-10-10T16:42:49+00:00"
        assertEquals(CourseSyncReconciler.CourseReset(false, at), CourseSyncReconciler.coursesReset(null, at))
        assertEquals(CourseSyncReconciler.CourseReset(false, at), CourseSyncReconciler.coursesReset("garbage", at))
    }

    @Test
    fun `the first sync after the update applies a reset newer than the old build's last sync, once`() {
        // The old build kept this phone's clock at its last sync; a reset
        // made elsewhere since then would otherwise be remembered unapplied.
        val legacy = backendInstant("2026-10-10T16:00:00+00:00")!!.toEpochMilli()

        assertEquals(
            CourseSyncReconciler.CourseReset(apply = true, remember = "2026-10-10T16:42:49+00:00"),
            CourseSyncReconciler.coursesReset("2026-10-10T16:42:49+00:00", lastSeen = null, legacyLastSyncMs = legacy),
        )
        assertFalse(
            "a reset the old build had already seen",
            CourseSyncReconciler.coursesReset("2026-10-10T15:00:00+00:00", lastSeen = null, legacyLastSyncMs = legacy).apply,
        )
        assertFalse(
            "with what this build remembered, the old stamp is not read",
            CourseSyncReconciler.coursesReset("2026-10-10T16:42:49+00:00", lastSeen = "2026-10-10T16:42:49+00:00", legacyLastSyncMs = legacy).apply,
        )
    }
}
