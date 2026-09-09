// Pure course-sync reconciliation, extracted from HomeViewModel.
//
// Deciding which courses the server has dropped is the part of sync that
// can destroy user data, and it has done so before: manual courses were
// once tombstoned merely for being absent from the server, which deleted
// courses the user had typed in by hand. The rule that prevents it —
// "server absence never tombstones a manual course" — is a single `if`
// that is easy to lose in a refactor and impossible to notice until a
// student's timetable is missing entries.
//
// It has no reason to be inside a ViewModel. Here it is a function of its
// inputs, and CourseSyncReconcilerTest holds the rules in place.

package org.ntust.app.tigerduck.ui.screen.home

import org.ntust.app.tigerduck.data.CourseTombstoneKeys
import org.ntust.app.tigerduck.push.CourseOverrideResult
import org.ntust.app.tigerduck.push.CourseTombstone
import org.ntust.app.tigerduck.push.ServerCourse
import org.ntust.app.tigerduck.shared.Course

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
        return moodleId.take(4) == semester
    }

    /**
     * One semester's reconcile against the backend snapshot.
     *
     * Mirrors iOS `AppState.reconcileCourses`, which runs this per term over
     * the server's semesters plus the catalogue rather than guessing a
     * "current" one. Doing it per term is what lets a retaken course number
     * be hidden in one semester and visible in another.
     *
     * [serverRows] is this term's slice of `/sync/full`'s `courses`;
     * [tombstoneNos] this term's `course_tombstones`. Returns the new
     * tombstone set, the rows to merge locally, and — when the server has
     * nothing for the term — the local courses still worth uploading.
     *
     * [selectionDroppedNos] is this term's entry from
     * [DataCache.loadSelectionDroppedNos] — courses 選課 stopped listing.
     * Their rows are dropped up front rather than merged, because an upload
     * never prunes: the backend still carries a course the student dropped in
     * 加退選 until someone deletes it explicitly, and merging it back would
     * put it on the timetable as a manual course, where a later refresh is
     * required to preserve it. Dropping the row before anything else also
     * keeps it from counting as evidence of presence further down, which
     * would otherwise un-hide it.
     *
     * [serverKnownNos] is every course number a snapshot of this term has
     * ever carried, from [DataCache.loadServerKnownNos]. It is what lets a
     * manual course lose its absence-immunity — see the loop below — and it
     * comes back in [SemesterOutcome.serverKnownNos] for the caller to
     * persist. It only ever grows; a number stays known after the course is
     * deleted, which is the point.
     */
    fun reconcileSemester(
        semester: String,
        localCourses: List<Course>,
        serverRows: List<ServerCourse>,
        tombstoneNos: Set<String>,
        tombstones: Set<String>,
        serverKnownNos: Set<String> = emptySet(),
        graceCourseNos: Set<String> = emptySet(),
        selectionDroppedNos: Set<String> = emptySet(),
    ): SemesterOutcome {
        // Misfiled rows are neither a roster nor evidence of presence, and
        // neither is a course 選課 has dropped.
        val rows = serverRows.filter {
            isFiled(it.moodleId, it.courseNo, semester) &&
                it.courseNo !in selectionDroppedNos
        }
        val serverNos = rows.map { it.courseNo }.toSet()

        var updated = tombstones

        // A hand-typed course no snapshot has carried is this device's alone
        // to delete: the server has never seen it, so nothing the server
        // says can be about it. A tombstone naming its number belongs to a
        // course some other device once held — the same rule that keeps
        // server absence from deleting it below.
        val unsyncedManualNos = localCourses
            .filter { it.isManual && it.courseNo !in serverKnownNos }
            .map { it.courseNo }
            .toSet()

        // Explicit tombstones from other devices, applied before anything
        // reads this term's emptiness. A semester reset is exactly the case
        // where the server has no rows for the term and a tombstone for
        // every course it used to hold. Reading the silence first kept the
        // pre-reset roster on this device and re-uploaded it on every
        // refresh — the backend refuses that upload, silently — which is
        // how a reset on one phone never reached the other.
        for (courseNo in tombstoneNos) {
            if (courseNo in serverNos || courseNo in unsyncedManualNos) continue
            if (CourseTombstoneKeys.isHidden(courseNo, semester, updated)) continue
            updated = CourseTombstoneKeys.hide(courseNo, semester, updated)
        }

        // Nothing uploaded for this term yet — first sync, or another device
        // mid-reset. Push what we have instead of reading the silence as
        // "every local course was deleted elsewhere". Only what the
        // tombstones leave visible counts: after a reset the whole roster is
        // tombstoned, and then there is nothing to push.
        if (serverNos.isEmpty()) {
            val hidden = CourseTombstoneKeys.hiddenIn(semester, updated)
            return SemesterOutcome(
                tombstones = updated,
                merged = emptyList(),
                toUpload = localCourses.filter { it.courseNo !in hidden },
                serverKnownNos = serverKnownNos,
            )
        }

        // A local course the server does not list was deleted on another
        // device — unless it is manual and the server has never carried it,
        // in which case the silence means our upload has not landed yet.
        // That is the narrow rule; the blanket one ("manual is never
        // tombstoned on absence") destroyed data in both directions.
        //
        // Dropping it entirely deletes hand-typed courses the server has
        // not heard of, which is the incident this file's header describes.
        // Keeping it made every row [toCourse] merges down from the cloud
        // immortal, because those are stamped manual so a portal refresh
        // cannot drop them: no roster could retire such a row, and this
        // device re-uploaded it on every refresh, refilling a term another
        // device had just reset. [serverKnownNos] is the difference — once
        // a snapshot has carried the number, absence is a deletion.
        for (course in localCourses) {
            if (course.courseNo in serverNos) continue
            if (course.isManual && course.courseNo !in serverKnownNos) continue
            if (CourseTombstoneKeys.isHidden(course.courseNo, semester, updated)) continue
            updated = CourseTombstoneKeys.hide(course.courseNo, semester, updated)
        }
        // Hidden here but back on the server → un-hide, unless our own delete
        // is still in flight and the backend has not caught up yet.
        for (courseNo in serverNos) {
            if (CourseTombstoneKeys.isHidden(courseNo, semester, updated) &&
                courseNo !in graceCourseNos
            ) {
                updated = CourseTombstoneKeys.unhide(courseNo, semester, updated)
            }
        }

        val localNos = localCourses.map { it.courseNo }.toSet()
        val hidden = CourseTombstoneKeys.hiddenIn(semester, updated)
        val merged = rows
            .filter { it.courseNo !in localNos && it.courseNo !in hidden }
            .distinctBy { it.courseNo }
            .map(::toCourse)

        return SemesterOutcome(
            tombstones = updated,
            merged = merged,
            serverKnownNos = serverKnownNos + serverNos,
        )
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
    ) {
        val uploadLocal: Boolean get() = toUpload.isNotEmpty()
    }

    /**
     * Marked `isManual` because from this device's point of view the row did
     * not come from an NTUST enrolment fetch, and a later refresh must not
     * wipe it.
     */
    private fun toCourse(it: ServerCourse) = Course(
        courseNo = it.courseNo,
        courseName = it.courseName,
        instructor = it.instructors.joinToString(", "),
        credits = it.credits,
        classroom = it.classroom,
        enrolledCount = it.enrolledCount,
        maxCount = it.maxCount,
        moodleIdNumber = it.moodleId,
        isManual = true,
        scheduleJson = it.scheduleJson,
        classroomMapJson = it.classroomMapJson,
    )

    /**
     * This term's tombstone course numbers, from the full-sync payload.
     *
     * A reset tombstone does not bind the device that wrote it — the same
     * rule the backend applies to that device's next upload, which
     * releases the tombstones for the keys it names. Between the reset's
     * DELETE and that upload the server has an empty term and a tombstone
     * per course, and a sync landing in the gap must not read them as
     * "hide everything here": the refetch filters by the tombstone store,
     * so it would then upload nothing and never release them. A single
     * delete binds its author like everyone else.
     */
    fun tombstoneNosFor(semester: String, tombstones: List<CourseTombstone>): Set<String> =
        tombstones
            .filter { it.semester == semester && !(it.deletedByReset && it.deletedByThisDevice) }
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
     * Courses with server colours applied, or null when nothing changed —
     * the caller uses null to skip a cache write and a widget reload.
     *
     * An override matches on courseNo first and Moodle id second, because
     * manually-added courses may have no courseNo the server recognises.
     */
    fun applyColorOverrides(
        courses: List<Course>,
        overrides: List<CourseOverrideResult>,
        syncColors: Boolean,
    ): List<Course>? {
        if (!syncColors || courses.isEmpty()) return null
        var changed = false
        val updated = courses.map { course ->
            val override = overrides.find { it.courseNo == course.courseNo }
                ?: overrides.find { it.moodleCourseId == course.moodleIdNumber }
            val newHex = override?.colorHex ?: return@map course
            if (newHex != course.customColorHex) {
                changed = true
                course.copy(customColorHex = newHex)
            } else {
                course
            }
        }
        return if (changed) updated else null
    }

    /**
     * Custom names merged per (courseNo, locale), or null when nothing
     * changed. An empty name from the server means "cleared", so it removes
     * that locale rather than storing a blank; a course left with no locales
     * drops out of the map entirely instead of leaving an empty entry behind.
     */
    fun mergeCustomNames(
        existing: Map<String, Map<String, String>>,
        overrides: List<CourseOverrideResult>,
        syncNames: Boolean,
    ): Map<String, Map<String, String>>? {
        if (!syncNames) return null
        val merged = existing.mapValues { it.value.toMutableMap() }.toMutableMap()
        var changed = false
        for (o in overrides) {
            val no = o.courseNo ?: continue
            if (o.customNames.isEmpty()) continue
            val entry = merged[no]?.toMutableMap() ?: mutableMapOf()
            for ((locale, name) in o.customNames) {
                if (name.isEmpty()) entry.remove(locale) else entry[locale] = name
            }
            if (entry.isEmpty()) merged.remove(no) else merged[no] = entry
            changed = true
        }
        return if (changed) merged.mapValues { it.value.toMap() } else null
    }
}
