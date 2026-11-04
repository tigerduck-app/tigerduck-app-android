package org.ntust.app.tigerduck.ui.screen.home

import org.ntust.app.tigerduck.data.CourseTombstoneKeys
import org.ntust.app.tigerduck.data.SelectionAnswer
import org.ntust.app.tigerduck.data.TranscriptTerms
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.push.CourseTombstone
import org.ntust.app.tigerduck.push.ServerCourse
import org.ntust.app.tigerduck.push.TombstoneKind
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.sync.SyncField

/**
 * One term's course membership from a `/v4` snapshot (LWW D10, D12): pure, for the course list's
 * reconcile, which commits it as it commits `/v3`'s ([commitReconciledTerm]).
 *
 * - **The term's courses are the snapshot's rows,** school roster and hand-added alike. A course
 *   whose key holds a `hide` tombstone and no later add is hidden, here as everywhere: its hide is
 *   kept and the course leaves the term. A row listed with no later hide lifts this phone's hide
 *   and comes down when this phone lacks it.
 * - **A removal or a reset is no hide.** A course a `remove` or `reset` tombstone took off the
 *   backend with no row left leaves the term with no hide when it was added by hand (a merged
 *   copy or one added here), so a roster that lists it brings it back. A course this phone has
 *   from the school stays: school data is the base layer, and a roster upload lists it again
 *   (`tigersync-refresh-order`). The backend has already applied `term_resets` and
 *   `courses_reset_hlc` to its rows and tombstones, so they need no rule of their own here.
 * - **A course the snapshot does not mention** keeps this phone's: a school course its roster
 *   upload has yet to list, or a hand-add that upload will name. A hide of this term the backend
 *   does not hold is lifted, as D12 takes a field's absence.
 * - **What this phone holds** ([keeps], by course number: its add or delete waiting, or
 *   acknowledged since the snapshot began) stays as this phone has it, hide and course alike.
 * - **School data's own rules** stay as `/v3`'s: a misfiled row is no roster row, and a row a
 *   roster dropped leaves the term with no hide but a course added by hand
 *   ([CourseSyncReconciler.reconcileSemester]).
 * - **A copy merged down keeps what its row is** ([Course.isUserAddedOnServer]): added by hand on
 *   some device or the school's, which its manual flag cannot tell. A copy this phone holds
 *   follows its row at each pull that lists it ([followingRows]).
 *
 * The `/v3` rules (`deleted_by_reset`, `deleted_by_this_device`, `courses_reset_at`, the server's
 * known numbers as the evidence of a delete) stay until the `/v3` rules go (Z2).
 */
internal object CourseMembership {

    /**
     * The term's [outcome], committed as `/v3`'s is, and the courses that leave the term with no
     * hide ([gone]): taken back by a removal or a reset elsewhere.
     */
    data class TermOutcome(val outcome: CourseSyncReconciler.SemesterOutcome, val gone: Set<String>)

    fun reconcileTerm(
        semester: String,
        localCourses: List<Course>,
        serverRows: List<ServerCourse>,
        tombstones: List<CourseTombstone>,
        hides: Set<String>,
        keeps: (courseNo: String) -> Boolean,
        serverKnownNos: Set<String> = emptySet(),
        selectionDroppedNos: Set<String> = emptySet(),
        answer: SelectionAnswer? = null,
        fetchedAtMs: Long = 0L,
    ): TermOutcome {
        val filed = serverRows.filter { CourseSyncReconciler.isFiled(it.moodleId, it.courseNo, semester) }
        val dropTimes = CourseSyncReconciler.serverDropTimes(filed, fetchedAtMs)
        val droppedOnServer = dropTimes.filterNot { (courseNo, at) -> answer?.listsSince(courseNo, at) == true }.keys
        val handAddedNos = localCourses.filter(TranscriptTerms::isAddedHere).mapTo(mutableSetOf()) { it.courseNo } +
            filed.filter { it.isUserAdded }.map { it.courseNo }
        val rows = filed.filter { it.courseNo !in selectionDroppedNos + droppedOnServer || it.courseNo in handAddedNos }
            .associateBy { it.courseNo }
        val marks = tombstones.filter { it.semester == semester }
            .mapNotNull { t -> (t.courseNo ?: SyncField.courseNoOf(t.courseKey))?.let { it to t } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, all) -> all.maxBy { it.hlc ?: Long.MIN_VALUE } }
        fun hidden(no: String): Boolean {
            val mark = marks[no]?.takeIf { it.kind == TombstoneKind.HIDE } ?: return false
            val added = rows[no]?.addedHlc ?: return true
            return (mark.hlc ?: Long.MIN_VALUE) >= added
        }
        val local = localCourses.associateBy { it.courseNo }
        val left = localCourses.filter { course ->
            course.courseNo !in rows && !keeps(course.courseNo) && !TranscriptTerms.isAddedHere(course) &&
                (course.courseNo in selectionDroppedNos || course.courseNo in droppedOnServer)
        }.mapTo(mutableSetOf()) { it.courseNo }

        var updated = hides
        val gone = mutableSetOf<String>()
        val scopedHides = hides.filter { it.startsWith(CourseTombstoneKeys.key(semester, "")) }
            .map { it.removePrefix(CourseTombstoneKeys.key(semester, "")) }
        for (no in (local.keys + rows.keys + marks.keys + scopedHides).sorted()) {
            if (keeps(no)) continue
            when {
                hidden(no) -> if (!CourseTombstoneKeys.isHidden(no, semester, updated)) updated = CourseTombstoneKeys.hide(no, semester, updated)
                no in rows -> updated = CourseTombstoneKeys.unhide(no, semester, updated)
                else -> {
                    // Absent: a hide of this term the backend does not hold follows it; a bare one, no.
                    updated = updated - CourseTombstoneKeys.key(semester, no)
                    if (no in marks && local[no]?.isManual == true) gone += no
                }
            }
        }
        val hiddenNow = CourseTombstoneKeys.hiddenIn(semester, updated)
        val merged = rows.values
            .filter { it.courseNo !in local && it.courseNo !in hiddenNow && !keeps(it.courseNo) }
            .map { row -> CourseSyncReconciler.toCourse(row).copy(isUserAddedOnServer = row.isUserAdded) }
        // Nothing uploaded for this term yet: what this phone has goes up, as on /v3, but what it hides.
        val toUpload = if (rows.isNotEmpty() || dropTimes.isNotEmpty()) emptyList() else {
            localCourses.filter { it.courseNo !in hiddenNow && it.courseNo !in gone && it.courseNo !in left }
        }
        val outcome = CourseSyncReconciler.SemesterOutcome(
            tombstones = updated,
            merged = merged,
            toUpload = toUpload,
            serverKnownNos = serverKnownNos + rows.keys,
            left = left,
            droppedOnServer = dropTimes - selectionDroppedNos,
            serverDrops = dropTimes,
            handAddedNos = handAddedNos,
        )
        return TermOutcome(outcome, gone)
    }

    /**
     * [courses], a term's list, with each copy merged down from the server ([Course.isManual], with
     * a Moodle id) saying what [rows], the snapshot's of [semester], say of its row: whether it was
     * added by hand on some device ([Course.isUserAddedOnServer]), for the term's uploads. A copy
     * no snapshot has told (null, as an older build left it) is told now, school row or not; a row
     * the school's roster lists since is the school's. A copy whose row the snapshot does not list
     * keeps what it has, and so does every course added here or fetched from the school.
     *
     * [held], given by a pull that covers the term ([heldIn]): every course number the full
     * snapshot holds a row or a tombstone of. A copy no snapshot has told, of a number it holds
     * nothing of, is told it was added by hand: the backend has nothing of it, and the school's
     * fetch has not listed it, which would have made it the school's. It then goes up as the
     * student's own add, at 0, which against no row makes a `user_added` one (Codex on round 5).
     *
     * A course [written] names, whose provenance a write changed since the pull began
     * (`DataCache.CourseSync.retoldSince`), keeps what that write told it: it is newer than the
     * snapshot (compare-and-set, Greptile on PR #687).
     */
    fun followingRows(
        courses: List<Course>,
        semester: String,
        rows: List<ServerCourse>,
        held: Set<String>? = null,
        written: (courseNo: String) -> Boolean = { false },
    ): List<Course> {
        val filed = rows.filter { CourseSyncReconciler.isFiled(it.moodleId, it.courseNo, semester) }
        val listed = filed.mapTo(mutableSetOf()) { it.courseNo }
        val byHand = filed.filter { it.isUserAdded }.mapTo(mutableSetOf()) { it.courseNo }
        return courses.map { course ->
            val copy = course.isManual && !TranscriptTerms.isAddedHere(course) && !written(course.courseNo)
            val told = when {
                !copy -> null
                course.courseNo in listed -> course.courseNo in byHand
                held != null && course.isUserAddedOnServer == null && course.courseNo !in held -> true
                else -> null
            }
            if (told == null || course.isUserAddedOnServer == told) course else course.copy(isUserAddedOnServer = told)
        }
    }

    /**
     * What a full `/v4` [snapshot] holds of [semester], for [followingRows]: the course number of
     * every row and tombstone of the term, the misfiled rows' included, as each holds its key. Null
     * when a reset covers the term (`term_resets`, `courses_reset_hlc`): an add at 0 loses to it,
     * so a number the snapshot holds nothing of tells nothing.
     */
    fun heldIn(snapshot: BackendSyncResult, semester: String): Set<String>? {
        if (snapshot.termResets?.containsKey(semester) == true || snapshot.coursesResetHlc != null) return null
        val tombstones = snapshot.tombstones.filter { it.semester == semester }.mapNotNull { it.courseNo ?: SyncField.courseNoOf(it.courseKey) }
        return (snapshot.serverCourses.filter { it.semester == semester }.map { it.courseNo } + tombstones).toSet()
    }
}
