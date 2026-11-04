package org.ntust.app.tigerduck.ui.screen.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.push.CourseTombstone
import org.ntust.app.tigerduck.push.ServerCourse
import org.ntust.app.tigerduck.push.TombstoneKind
import org.ntust.app.tigerduck.shared.Course

/**
 * One term's course membership from a `/v4` snapshot ([CourseMembership], LWW D10 and D12): a
 * case per `tigersync-merge` membership scenario as the snapshot shows it to this phone, and per
 * client-side item of tigerduck-app#689. Made-up course numbers.
 */
class CourseMembershipTest {

    private val term = "1151"

    private fun school(no: String) = Course(courseNo = no, courseName = "School $no", moodleIdNumber = "$term$no")

    // Added by hand on this phone: manual, with no Moodle id.
    private fun handAdded(no: String) = Course(courseNo = no, courseName = "Added $no", isManual = true)

    // Merged down from another device's hand-add: manual, with a Moodle id.
    private fun mergedCopy(no: String) = Course(courseNo = no, courseName = "Merged $no", moodleIdNumber = "$term$no", isManual = true)

    private fun row(no: String, userAdded: Boolean = false, addedHlc: Long? = null) = ServerCourse(
        courseNo = no, courseName = "Row $no", semester = term, moodleId = "$term$no",
        source = if (userAdded) "user_added" else "roster", addedHlc = addedHlc,
    )

    private fun tombstone(no: String, kind: TombstoneKind?, hlc: Long, withNo: Boolean = true) = CourseTombstone(
        courseKey = "client:$term:$no", courseNo = no.takeIf { withNo }, semester = term, deletedAt = "", hlc = hlc, kind = kind,
    )

    private fun hide(no: String) = "$term:$no"

    private fun reconcile(
        local: List<Course> = emptyList(),
        rows: List<ServerCourse> = emptyList(),
        tombstones: List<CourseTombstone> = emptyList(),
        hides: Set<String> = emptySet(),
        held: Set<String> = emptySet(),
        dropped: Set<String> = emptySet(),
    ) = CourseMembership.reconcileTerm(term, local, rows, tombstones, hides, keeps = { it in held }, selectionDroppedNos = dropped)

    private val CourseMembership.TermOutcome.merged get() = outcome.merged.map { it.courseNo }
    private val CourseMembership.TermOutcome.hides get() = outcome.tombstones

    /** "Roster lists a hidden course": a later roster upload never brings a hidden course back. */
    @Test
    fun `a hidden course stays hidden though the roster lists it`() {
        val out = reconcile(local = listOf(school("A")), rows = listOf(row("A")), tombstones = listOf(tombstone("A", TombstoneKind.HIDE, 100)))

        assertEquals(setOf(hide("A")), out.hides)
        assertEquals(emptyList<String>(), out.merged)
    }

    /** Nor does a hidden course this phone lacks come down from the roster that lists it. */
    @Test
    fun `a hidden course another device's roster lists is not merged down`() {
        val out = reconcile(rows = listOf(row("A")), tombstones = listOf(tombstone("A", TombstoneKind.HIDE, 100)))

        assertEquals(emptyList<String>(), out.merged)
        assertEquals(emptyList<String>(), out.outcome.toUpload.map { it.courseNo })
    }

    /** "Tombstone older than 30 days": every tombstone binds, however old. */
    @Test
    fun `a hide however old binds, its course number named by its key alone`() {
        val out = reconcile(tombstones = listOf(tombstone("A", TombstoneKind.HIDE, 1, withNo = false)))

        assertEquals(setOf(hide("A")), out.hides)
    }

    /** #689: a hide queued here, offline, stays this phone's until it lands, whatever the snapshot lists. */
    @Test
    fun `a hide waiting to go up stays hidden while another device's re-add is listed`() {
        val out = reconcile(rows = listOf(row("A", userAdded = true, addedHlc = 200)), hides = setOf(hide("A")), held = setOf("A"))

        assertEquals(setOf(hide("A")), out.hides)
        assertEquals(emptyList<String>(), out.merged)
    }

    /**
     * "Delete queued before a re-add elsewhere" and "Re-add after delete": once the hide has
     * landed, the add stamped after it keeps the course, which comes back here.
     */
    @Test
    fun `an add later than the hide shows the course again once this phone's hide has landed`() {
        val out = reconcile(
            rows = listOf(row("A", userAdded = true, addedHlc = 300)),
            tombstones = listOf(tombstone("A", TombstoneKind.HIDE, 200)),
            hides = setOf(hide("A")),
        )

        assertEquals(emptySet<String>(), out.hides)
        assertEquals(listOf("A"), out.merged)
    }

    /** "Removal arriving after the roster", "Enrolled later", "Older hide after the roster lists a removed course". */
    @Test
    fun `a removed hand-add the roster lists shows from the roster, with no hide`() {
        val out = reconcile(
            local = listOf(mergedCopy("A")),
            rows = listOf(row("A")),
            tombstones = listOf(tombstone("A", TombstoneKind.REMOVE, 100)),
        )

        assertEquals(emptySet<String>(), out.hides)
        assertEquals(emptySet<String>(), out.gone)
    }

    /** A removal elsewhere takes a hand-added course back, a merged copy alike, with no hide; a school course stays. */
    @Test
    fun `a removal with no row left takes a hand-added course out with no hide, and leaves a school course`() {
        val out = reconcile(
            local = listOf(mergedCopy("A"), handAdded("B"), school("C"), school("D")),
            rows = listOf(row("D")),
            tombstones = listOf(
                tombstone("A", TombstoneKind.REMOVE, 100), tombstone("B", TombstoneKind.REMOVE, 100), tombstone("C", TombstoneKind.REMOVE, 100),
            ),
        )

        assertEquals(setOf("A", "B"), out.gone)
        assertEquals(emptySet<String>(), out.hides)
    }

    /**
     * "Reset arriving last", "A reset clears the hand-added courses it covers": a reset elsewhere
     * takes the hand-added courses whose rows it cleared, its tombstones say so; a hide stamped after
     * it stays; a school course stays for the roster to list again ("Roster after a reset").
     */
    @Test
    fun `a reset takes the hand-added courses it cleared, keeps a later hide, and leaves the school's`() {
        val out = reconcile(
            local = listOf(handAdded("A"), school("B"), school("C")),
            tombstones = listOf(
                tombstone("A", TombstoneKind.RESET, 100), tombstone("B", TombstoneKind.RESET, 100), tombstone("C", TombstoneKind.HIDE, 150),
            ),
        )

        assertEquals(setOf("A"), out.gone)
        assertEquals(setOf(hide("C")), out.hides)
    }

    /** #689: an add waiting while a reset elsewhere tombstones the course keeps it until the backend answers. */
    @Test
    fun `an add waiting to go up keeps its course over a reset's tombstone`() {
        val out = reconcile(local = listOf(handAdded("A")), tombstones = listOf(tombstone("A", TombstoneKind.RESET, 100)), held = setOf("A"))

        assertEquals(emptySet<String>(), out.gone)
        assertEquals(emptySet<String>(), out.hides)
    }

    /** D12, absence included: a hide of this term the backend does not hold is lifted; a bare legacy one, in every term, is not. */
    @Test
    fun `a hide the backend does not hold is lifted, but a bare one`() {
        val out = reconcile(hides = setOf(hide("A"), "B", "1142:C"))

        assertEquals(setOf("B", "1142:C"), out.hides)
    }

    /** School data is the base layer, and a hand-add no tombstone names is this phone's until its upload: neither leaves. */
    @Test
    fun `a course the snapshot does not mention stays`() {
        val out = reconcile(local = listOf(school("A"), handAdded("B")), rows = listOf(row("C")))

        assertEquals(emptySet<String>(), out.gone)
        assertEquals(emptySet<String>(), out.hides)
        assertEquals(listOf("C"), out.merged)
        assertTrue(out.outcome.toUpload.isEmpty())
    }

    /** As on /v3: a term the backend holds nothing for goes up as this phone has it, but what it hides. */
    @Test
    fun `a term the backend holds nothing for goes up but its hidden courses`() {
        val out = reconcile(local = listOf(school("A"), school("B")), tombstones = listOf(tombstone("B", TombstoneKind.HIDE, 100)))

        assertEquals(listOf("A"), out.outcome.toUpload.map { it.courseNo })
    }

    /** School data's own rule, as /v3's: a course course selection dropped leaves with no hide, but one added by hand. */
    @Test
    fun `a dropped course leaves the term with no hide, but one added by hand`() {
        val out = reconcile(local = listOf(school("A"), handAdded("B")), rows = listOf(row("C")), dropped = setOf("A", "B"))

        assertEquals(setOf("A"), out.outcome.left)
        assertEquals(emptySet<String>(), out.hides)
    }

    /**
     * A copy merged down keeps what its row is, which its manual flag cannot tell: one of a row added
     * by hand on some device is the uploads' to name, one of a school row is not. A copy this phone
     * holds follows its row at each pull that lists it, both ways, and one no snapshot has told about
     * (null, as an older build left it) is told now, a school row's included; one the snapshot does
     * not list keeps what it has. A course added here, and one fetched from the school, follow no row.
     */
    @Test
    fun `a copy merged down says whether its row was added by hand, and a copy held follows its row`() {
        val out = reconcile(rows = listOf(row("A"), row("E", userAdded = true, addedHlc = 50)))
        assertEquals(mapOf("A" to false, "E" to true), out.outcome.merged.associate { it.courseNo to it.isUserAddedOnServer })

        val held = listOf(
            mergedCopy("A").copy(isUserAddedOnServer = true), mergedCopy("B"), mergedCopy("E"),
            mergedCopy("G").copy(isUserAddedOnServer = true), mergedCopy("U"), handAdded("H"), school("S"),
        )
        val rows = listOf(row("A"), row("B"), row("E", userAdded = true), row("H", userAdded = true), row("S", userAdded = true))
        val followed = CourseMembership.followingRows(held, term, rows)
        assertEquals(
            mapOf("A" to false, "B" to false, "E" to true, "G" to true, "U" to null, "H" to null, "S" to null),
            followed.associate { it.courseNo to it.isUserAddedOnServer },
        )
        assertEquals("followed again, nothing changes", followed, CourseMembership.followingRows(followed, term, rows))
    }

    /**
     * Codex on round 5 (P2 #2): a pull that covers the term tells a copy no snapshot has told about,
     * of a number its full snapshot holds neither a row nor a tombstone of, that it was added by
     * hand. One whose number it holds a tombstone of tells nothing, nor does a term a reset covers,
     * where an add at 0 would lose; a copy told already keeps what it was told.
     */
    @Test
    fun `a pull covering the term tells a copy it holds nothing of that it was added by hand`() {
        val held = listOf(mergedCopy("U"), mergedCopy("T"), mergedCopy("F").copy(isUserAddedOnServer = false), handAdded("H"))
        val snapshot = { termResets: Map<String, Long>? ->
            BackendSyncResult(
                ignoredIds = emptySet(), completedIds = emptySet(), courseOverrides = emptyList(), serverCourseNos = setOf("A"),
                serverCourses = listOf(row("A")), currentRevision = 1, tombstones = listOf(tombstone("T", TombstoneKind.HIDE, 100)),
                termResets = termResets,
            )
        }

        val covered = CourseMembership.followingRows(held, term, listOf(row("A")), CourseMembership.heldIn(snapshot(null), term))
        assertEquals(mapOf("U" to true, "T" to null, "F" to false, "H" to null), covered.associate { it.courseNo to it.isUserAddedOnServer })

        assertEquals(null, CourseMembership.heldIn(snapshot(mapOf(term to 50L)), term))
        assertEquals("not covered, nothing told", held, CourseMembership.followingRows(held, term, listOf(row("A"))))
    }
}
