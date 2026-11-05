// Pure course-sync reconciliation, extracted from HomeViewModel: the school
// data's own rules (a misfiled row, a roster's drops), how a term's reconcile
// is committed, and which terms a pull reconciles. A term's membership is
// CourseMembership's, from the snapshot's rows and tombstones (LWW D10).
//
// Deciding which courses the server has dropped is the part of sync that
// can destroy user data, and it has done so before: manual courses were
// once tombstoned merely for being absent from the server, which deleted
// courses the user had typed in by hand.
//
// It has no reason to be inside a ViewModel. Here it is a function of its
// inputs, and CourseSyncReconcilerTest holds the rules in place.

package org.ntust.app.tigerduck.ui.screen.home

import org.ntust.app.tigerduck.data.CourseTombstoneKeys
import org.ntust.app.tigerduck.data.SelectionAnswer
import org.ntust.app.tigerduck.data.TranscriptTerms
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.push.CourseTombstone
import org.ntust.app.tigerduck.push.ServerCourse
import org.ntust.app.tigerduck.shared.Course
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

object CourseSyncReconciler {

    /**
     * Whether a server row really belongs to [semester].
     *
     * Between 2026-08-20 and the SemesterCatalog fix, 115-1 course-selection
     * enrolments were uploaded under the heuristic's 114-2 while carrying a
     * `1151…` Moodle id. Those rows are not a roster for the term they are
     * filed under: merging them puts next term's courses in this term's grid,
     * and counting them as present makes the reconcile think a course the
     * user deleted is back on the server.
     *
     * Matches only the exact shape the bug produced: a Moodle id that is a
     * four-character term code followed by the row's own course number, where
     * that term differs from the one the row is filed under. Anything else —
     * a plain numeric id, a shorter id, a term that agrees — is left alone.
     *
     * This mirrors the backend's `MISFILED_CLIENT_COURSES_DELETE` predicate
     * (migration `9c4d1e2f3a5b`), which is deliberately stricter than iOS's
     * `AppState.isFiled`. iOS judges on "first three characters are digits
     * and the leading four differ", so a legitimate plain-numeric Moodle id
     * such as `123456` is read as misfiled and its row silently ignored.
     * Requiring the id to actually end in the course number cannot produce
     * that false positive, and still catches every `1151…` row filed as 1142.
     */
    fun isFiled(moodleId: String?, courseNo: String, semester: String): Boolean {
        if (semester.length != 4) return true
        if (moodleId == null || moodleId.length <= 4) return true
        if (moodleId != moodleId.take(4) + courseNo) return true
        // Case-insensitive: Moodle spells a summer term "114h" and NTUST
        // "114H", and a raw compare filed every summer row under another term.
        return moodleId.take(4).equals(semester, ignoreCase = true)
    }

    /**
     * The courses of [rows] a roster upload dropped on the server, each by
     * when, in epoch millis. A row without `enrollment_status`, from a
     * backend before 3.10.1, counts as enrolled.
     *
     * The time is the row's `updated_at`, which the drop sets. A later upload
     * that lists the course moves it on too, which only makes the drop read
     * newer, so an answer exempts less ([SelectionAnswer.listsSince]); the
     * next roster from here settles it. A row without one, as no backend
     * sends, counts from [fetchedAtMs], when the full sync that read it went
     * out: the drop came no later than that, within the clock margin. Null,
     * which no answer is newer than, when neither is known. As the iPhone's
     * `droppedOnServer` (tigerduck-app#914).
     */
    fun serverDropTimes(rows: List<ServerCourse>, fetchedAtMs: Long): Map<String, Long?> =
        rows.filter { it.isDropped }.associate { row ->
            row.courseNo to (backendInstant(row.updatedAt)?.toEpochMilli() ?: fetchedAtMs.takeIf { it > 0 })
        }

    /**
     * Whether [course] leaves its term with no hide, its row dropped: never a
     * course added here. A merged copy and a course fetched here alike go
     * when the drop list here ([droppedHere]) or a roster on the server
     * ([droppedOnServer], but a course course selection here listed since)
     * dropped it, as the iPhone's `readTerm` takes a dropped course out of the
     * courses fetched there (tigerduck-app#914). A fetch that only gains
     * leaves it out from then on (`DataCache.fetchDropsIn`).
     */
    private fun leaves(course: Course, droppedHere: Set<String>, droppedOnServer: Set<String>): Boolean =
        !TranscriptTerms.isAddedHere(course) && (course.courseNo in droppedHere || course.courseNo in droppedOnServer)

    /**
     * The term's courses as a sync commits them: [current], the list as it
     * stands now, without the courses the sync pruned from [read], its own
     * earlier load, as [kept] says, that [hidden], the course numbers hidden
     * in the term now, still names, and with the rows it merged down
     * ([merged]) that [current] still lacks and [hidden] does not name. A
     * course a delete or a fetch took out or put in since the read stays as
     * that left it, where writing [kept] back would undo it: one pruned for a
     * hide a course selection answer has lifted since is back in the term.
     * [newlyHidden], the course numbers whose hide the sync's commit added
     * (`DataCache.commitSyncHides`), go as the pruned do, so the term holds
     * none that such an answer saved back into it since the read.
     *
     * [left], the dropped courses ([SemesterOutcome.left]), go with no hide,
     * each only while it is still dropped as the term stands: on the drop
     * list now ([droppedHere]) or dropped in the snapshot ([droppedOnServer],
     * by when) and not listed since that drop by the term's latest course
     * selection answer as the commit reads it ([answer],
     * [SelectionAnswer.listsSince]), and not added back by hand since the
     * read. A roster that listed it again since clears its drop, and the
     * fetch keeps a merged copy's Moodle ID, so it would otherwise go
     * regardless. An answer older than the drop keeps nothing: it held off a
     * newer drop for good.
     *
     * Nor does a row of [merged] that [droppedHere] names come down, but one
     * added by hand ([handAdded], [SemesterOutcome.handAddedNos]): the
     * reconcile read the drop list before a past term's rebuild recorded its
     * drops and saved the term, which took that course out.
     */
    fun committedCourses(
        current: List<Course>,
        read: List<Course>,
        kept: List<Course>,
        merged: List<Course>,
        hidden: Set<String>,
        newlyHidden: Set<String> = emptySet(),
        left: Set<String> = emptySet(),
        droppedHere: Set<String> = emptySet(),
        droppedOnServer: Map<String, Long?> = emptyMap(),
        handAdded: Set<String> = emptySet(),
        answer: SelectionAnswer? = null,
    ): List<Course> {
        val pruned = read.mapTo(mutableSetOf()) { it.courseNo } - kept.mapTo(mutableSetOf()) { it.courseNo } + newlyHidden
        val serverDrops = droppedOnServer.filterNot { (courseNo, at) -> answer?.listsSince(courseNo, at) == true }.keys
        val held = current.filter {
            (it.courseNo !in pruned || it.courseNo !in hidden) &&
                !(it.courseNo in left && leaves(it, droppedHere, serverDrops))
        }
        val heldNos = held.mapTo(mutableSetOf()) { it.courseNo }
        return held + merged.filter {
            it.courseNo !in heldNos && it.courseNo !in hidden &&
                (it.courseNo !in droppedHere || it.courseNo in handAdded)
        }
    }

    data class SemesterOutcome(
        val tombstones: Set<String>,
        val merged: List<Course>,
        /**
         * The server had nothing for this term; push these up. Empty when
         * there is nothing to push — including a term whose whole roster the
         * tombstones hide, which is what a reset elsewhere looks like here.
         */
        val toUpload: List<Course> = emptyList(),
        /** Course numbers a snapshot of this term has ever carried. */
        val serverKnownNos: Set<String> = emptySet(),
        /**
         * The dropped courses, to take out of the term with no hide (see
         * [committedCourses]): those but the courses added here that course
         * selection here or a roster on the server has dropped, merged copies
         * and courses fetched here alike.
         */
        val left: Set<String> = emptySet(),
        /**
         * The snapshot's rows of the term a roster dropped, by when ([serverDropTimes]), for the
         * commit, which reads the term's answer again, but those the drop list here named: the
         * drop list as the commit finds it decides those, as a roster that took one off it since
         * is newer than the snapshot.
         */
        val droppedOnServer: Map<String, Long?> = emptyMap(),
        /** Every course of the term's rows a roster dropped on the server, by when, for `DataCache.recordServerDrops`. */
        val serverDrops: Map<String, Long?> = emptyMap(),
        /**
         * The courses added by hand, which no drop takes: added here, or a
         * `user_added` row from another device. For the commit, which checks
         * [merged] against the drop list again (see [committedCourses]).
         */
        val handAddedNos: Set<String> = emptySet(),
    ) {
        val uploadLocal: Boolean get() = toUpload.isNotEmpty()
    }

    /**
     * This term's tombstone course numbers, from the full-sync payload, whose marks the term's
     * commit lets go of ([commitReconciledTerm]). Every one counts, whatever wrote it.
     */
    fun tombstoneNosFor(semester: String, tombstones: List<CourseTombstone>): Set<String> =
        tombstones
            .filter { it.semester == semester }
            .mapNotNull { it.courseNo }
            .toSet()

    /**
     * [localCourses] with everything [tombstones] hide in [semester] taken
     * out, or null when nothing is hidden.
     *
     * The course cache must never carry a hidden course: the class table,
     * the widget and the Live Activity read it as-is and only Home filters
     * by the tombstone store, so a course hidden by the reconcile but left
     * in the cache stays on screen everywhere but Home. Deleting by hand
     * prunes the cache; so must a deletion that arrived from the server.
     *
     * [legacyBare] says whether a bare pre-scoping entry counts. It hides
     * in every term until [CourseTombstoneKeys.migrateLegacyEntries] pins
     * it, and only the foreground sync runs that migration; a caller that
     * has not must not delete a retaken course's cache on the strength of
     * a deletion made in another term.
     */
    fun pruneHidden(
        semester: String,
        localCourses: List<Course>,
        tombstones: Set<String>,
        legacyBare: Boolean = true,
    ): List<Course>? {
        val hidden = if (legacyBare) {
            CourseTombstoneKeys.hiddenIn(semester, tombstones)
        } else {
            localCourses.map { it.courseNo }
                .filterTo(mutableSetOf()) { CourseTombstoneKeys.key(semester, it) in tombstones }
        }
        val kept = localCourses.filter { it.courseNo !in hidden }
        return if (kept.size == localCourses.size) null else kept
    }

    /**
     * The terms a snapshot fetched at [fetchedAtMs] must not touch: those
     * mid-reset right now ([resetting], [DataCache.resettingSemesters]) and
     * those this device reset after the snapshot was fetched ([resetAt],
     * [DataCache.loadSemesterResetAt]).
     *
     * Such a snapshot still carries the pre-reset roster. Reconciled into
     * the freshly cleared cache it merged the whole roster back, and the
     * refetch the reset had started then uploaded it — from the resetting
     * device, whose upload releases its own reset tombstones — so the reset
     * undid itself. A snapshot fetched after the reset landed is fine.
     */
    fun termsResetAfter(
        fetchedAtMs: Long,
        resetAt: Map<String, Long>,
        resetting: Set<String> = emptySet(),
    ): Set<String> = resetAt.filterValues { it > fetchedAtMs }.keys + resetting

    /**
     * The reset stamps a snapshot fetched at [fetchedAtMs] has outlived:
     * those more than [RESET_STAMP_GRACE_MS] older than it.
     *
     * A stamp has done its job once a snapshot clearly newer than the
     * reset has been reconciled — an overlapping sync's older snapshot is
     * at most seconds behind, never a minute. Dropping it then is what
     * keeps a wall clock that later steps backwards from muting the term
     * for good: the stamp would otherwise sit ahead of every fetch time
     * until the clock caught up with it.
     */
    fun resetStampsOutlived(fetchedAtMs: Long, resetAt: Map<String, Long>): Set<String> =
        resetAt.filterValues { fetchedAtMs - it > RESET_STAMP_GRACE_MS }.keys

    private const val RESET_STAMP_GRACE_MS = 60_000L

    /**
     * The terms to reconcile: everything the server knows plus the picker's,
     * less [excluding] — see [termsResetAfter].
     */
    fun semestersToReconcile(
        serverCourses: List<ServerCourse>,
        tombstones: List<CourseTombstone>,
        catalogue: List<String>,
        excluding: Set<String> = emptySet(),
    ): List<String> = buildSet {
        serverCourses.mapNotNullTo(this) { it.semester.takeIf(String::isNotBlank) }
        tombstones.mapNotNullTo(this) { it.semester.takeIf(String::isNotBlank) }
        addAll(catalogue.filter(String::isNotBlank))
        removeAll(excluding)
    }.sorted()

    /**
     * [courses] with the server's colours applied, and the courses that had
     * to move off a colour a later pick took ([moved], by course number, with
     * the colour each moved to), for the caller to send to the server.
     */
    data class ColorOutcome(
        val courses: List<Course>,
        val moved: Map<String, String>,
    )
}

/**
 * A backend timestamp: Python's `isoformat()`, which carries microseconds
 * unless they are zero, and an offset. Null for anything unparseable, and
 * for a time with no offset read as UTC, which is what the backend writes.
 */
internal fun backendInstant(value: String?): Instant? {
    if (value.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(value).toInstant()
    } catch (_: Exception) {
        try {
            LocalDateTime.parse(value).toInstant(ZoneOffset.UTC)
        } catch (_: Exception) {
            null
        }
    }
}
