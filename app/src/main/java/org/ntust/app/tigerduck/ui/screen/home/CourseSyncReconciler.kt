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
import org.ntust.app.tigerduck.data.TranscriptTerms
import org.ntust.app.tigerduck.data.model.PendingCourseOverride
import org.ntust.app.tigerduck.push.CourseOverrideResult
import org.ntust.app.tigerduck.push.CourseTombstone
import org.ntust.app.tigerduck.push.ServerCourse
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.screen.classtable.CourseColorPick
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
     * would otherwise un-hide it. So are the rows another device's roster
     * upload marked `dropped` ([droppedOnServer]).
     *
     * A drop never takes a course added by hand: its row stays when the
     * course was added here ([TranscriptTerms.isAddedHere], or its add is in
     * [pendingAddNos]) or is a `user_added` row from another device. Nor is a
     * dropped course hidden: the server still holds it, and a hide would keep
     * it out once it is enrolled again. Its merged copy leaves the term
     * instead, through [SemesterOutcome.left]. As the iPhone's `keptRows`,
     * `unlistedCourses` and `droppedMerges` (tigerduck-app#890, #914). So
     * does a course fetched here that the drop list names: the fetch that
     * dropped it was cut short before it saved the term.
     *
     * [serverKnownNos] is every course number a snapshot of this term has
     * ever carried, from [DataCache.loadServerKnownNos]. It is what lets a
     * manual course lose its absence-immunity — see the loop below — and it
     * comes back in [SemesterOutcome.serverKnownNos] for the caller to
     * persist. It only ever grows; a number stays known after the course is
     * deleted, which is the point.
     *
     * [graceCourseNos] are the courses deleted here whose backend DELETE has
     * not landed, from `PendingCourseDeletes.waitingIn`. The server may still
     * list them: they are neither un-hidden nor merged back until it does.
     *
     * [pendingAddNos] are the courses added here by hand whose upload has not
     * landed, from `PendingCourseAdds.waitingIn`. Kept whatever the server
     * says until it does, as the iPhone keeps them (tigerduck-app#727): one the
     * server once listed, then deleted and added back here, is known by then,
     * and a tombstone or the server's silence would hide it for good, its add
     * then let go unsent. The add forces its key past the tombstone once it
     * lands.
     *
     * [restoredNos] are the courses a course selection answer brought back
     * from a deleted hand-added course's hide that no snapshot has listed
     * since, from `DataCache.restoredCoursesIn`: the roster upload naming
     * them may not have landed, so the server's silence is no delete
     * elsewhere. A tombstone still hides them: the backend leaves the one
     * this device's delete wrote out of its snapshot, so one there is
     * another device's. [tombstones] holds none of their hides: see
     * [withoutRestored].
     */
    fun reconcileSemester(
        semester: String,
        localCourses: List<Course>,
        serverRows: List<ServerCourse>,
        tombstoneNos: Set<String>,
        tombstones: Set<String>,
        serverKnownNos: Set<String> = emptySet(),
        graceCourseNos: Set<String> = emptySet(),
        pendingAddNos: Set<String> = emptySet(),
        selectionDroppedNos: Set<String> = emptySet(),
        restoredNos: Set<String> = emptySet(),
    ): SemesterOutcome {
        // Misfiled rows are neither a roster nor evidence of presence, and
        // neither is a dropped course, unless it was added by hand.
        val filed = serverRows.filter { isFiled(it.moodleId, it.courseNo, semester) }
        val droppedOnServer = droppedOnServer(filed)
        val droppedNos = selectionDroppedNos + droppedOnServer
        val addedHereNos = localCourses.filter(TranscriptTerms::isAddedHere).mapTo(mutableSetOf()) { it.courseNo } +
            pendingAddNos
        val rows = filed.filter {
            it.courseNo !in droppedNos || it.courseNo in addedHereNos || it.isUserAdded
        }
        val serverNos = rows.map { it.courseNo }.toSet()
        // Dropped, not deleted: the server still holds these rows.
        val skippedNos = filed.mapTo(mutableSetOf()) { it.courseNo } - serverNos
        // A dropped course leaves the term with no hide, even when the drops
        // leave the term no row, so the course can come back.
        val left = localCourses
            .filter { it.courseNo !in serverNos && it.courseNo !in pendingAddNos }
            .filter { leaves(it, selectionDroppedNos, droppedOnServer) }
            .mapTo(mutableSetOf()) { it.courseNo }

        var updated = tombstones

        // A hand-typed course no snapshot has carried is this device's alone
        // to delete: the server has never seen it, so nothing the server
        // says can be about it. A tombstone naming its number belongs to a
        // course some other device once held — the same rule that keeps
        // server absence from deleting it below. Nor can anything the server
        // says be about a course whose add from here has not landed: the add
        // is newer.
        fun notOnServerYet(course: Course) = course.courseNo in pendingAddNos ||
            (course.isManual && course.courseNo !in serverKnownNos)
        val notOnServerYetNos = localCourses
            .filter(::notOnServerYet)
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
            if (courseNo in serverNos || courseNo in notOnServerYetNos) continue
            if (CourseTombstoneKeys.isHidden(courseNo, semester, updated)) continue
            updated = CourseTombstoneKeys.hide(courseNo, semester, updated)
        }

        // Nothing uploaded for this term yet — first sync, or another device
        // mid-reset. Push what we have instead of reading the silence as
        // "every local course was deleted elsewhere". Only what the
        // tombstones leave visible counts: after a reset the whole roster is
        // tombstoned, and then there is nothing to push.
        //
        // A term whose rows a roster dropped is not empty on the server: an
        // upload without a roster leaves a dropped row dropped, so pushing
        // the courses again would change nothing, at every sync.
        if (serverNos.isEmpty()) {
            val hidden = CourseTombstoneKeys.hiddenIn(semester, updated)
            return SemesterOutcome(
                tombstones = updated,
                merged = emptyList(),
                toUpload = if (droppedOnServer.isNotEmpty()) {
                    emptyList()
                } else {
                    localCourses.filter { it.courseNo !in hidden && it.courseNo !in left }
                },
                serverKnownNos = serverKnownNos,
                left = left,
                droppedOnServer = droppedOnServer,
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
            if (notOnServerYet(course) || course.courseNo in restoredNos) continue
            // Dropped, not deleted: a hide would outlast its next enrollment.
            if (course.courseNo in skippedNos || course.courseNo in left) continue
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
        // Nor a row whose delete from here has not landed: a course taken
        // back out of Add course writes no tombstone, so only the grace keeps
        // the server's stale row from coming back as a hand-added course
        // while PendingCourseDeletes retries the DELETE. As the iPhone's
        // `mergesListedCourse(... deleting)`.
        val merged = rows
            .filter { it.courseNo !in localNos && it.courseNo !in hidden && it.courseNo !in graceCourseNos }
            .distinctBy { it.courseNo }
            .map(::toCourse)

        return SemesterOutcome(
            tombstones = updated,
            merged = merged,
            serverKnownNos = serverKnownNos + serverNos,
            left = left,
            droppedOnServer = droppedOnServer,
        )
    }

    /**
     * The course numbers of [rows] a roster upload dropped on the server.
     * A row without `enrollment_status`, from a backend before 3.10.1,
     * counts as enrolled. As the iPhone's `droppedOnServer`
     * (tigerduck-app#914), less its exception for a course selection answer
     * that listed the course live since the drop, whose time this phone does
     * not keep.
     */
    fun droppedOnServer(rows: List<ServerCourse>): Set<String> =
        rows.filter { it.isDropped }.mapTo(mutableSetOf()) { it.courseNo }

    /**
     * Whether [course] leaves its term with no hide, its row dropped: never a
     * course added here. A merged copy goes when the drop list here
     * ([droppedHere]) or a roster on the server ([droppedOnServer]) dropped
     * it, and a course fetched here when the drop list names it. One fetched
     * here that only the server dropped stays for course selection to settle;
     * see [droppedOnServer].
     */
    private fun leaves(course: Course, droppedHere: Set<String>, droppedOnServer: Set<String>): Boolean =
        !TranscriptTerms.isAddedHere(course) &&
            (course.courseNo in droppedHere || (course.isManual && course.courseNo in droppedOnServer))

    /**
     * [tombstones], a sync's read of the hides, without those of
     * [restoredNos], [semester]'s courses being brought back
     * (`DataCache.restoredCoursesIn`), read after the term's courses: a
     * course selection answer may have lifted them since the read, and the
     * term's courses may hold them again. What the reconcile is handed, and
     * what its commit (`DataCache.updateDeletedCourseNos`) is measured
     * against, so such a course is neither pruned from the term by the older
     * read nor written back as a removal over a delete of it made since.
     */
    fun withoutRestored(semester: String, tombstones: Set<String>, restoredNos: Set<String>): Set<String> =
        tombstones - restoredNos.mapTo(mutableSetOf()) { CourseTombstoneKeys.key(semester, it) }

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
     * list now ([droppedHere]) or dropped in the snapshot
     * ([droppedOnServer]), and not added back by hand since the read. A
     * roster that listed it again since clears its drop, and the fetch keeps
     * a merged copy's Moodle ID, so it would otherwise go regardless.
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
        droppedOnServer: Set<String> = emptySet(),
    ): List<Course> {
        val pruned = read.mapTo(mutableSetOf()) { it.courseNo } - kept.mapTo(mutableSetOf()) { it.courseNo } + newlyHidden
        val held = current.filter {
            (it.courseNo !in pruned || it.courseNo !in hidden) &&
                !(it.courseNo in left && leaves(it, droppedHere, droppedOnServer))
        }
        val heldNos = held.mapTo(mutableSetOf()) { it.courseNo }
        return held + merged.filter { it.courseNo !in heldNos && it.courseNo !in hidden }
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
         * [committedCourses]): merged copies of courses another device added
         * by hand that course selection here or a roster on the server has
         * dropped since, and courses fetched here that the drop list names.
         */
        val left: Set<String> = emptySet(),
        /** The course numbers of the snapshot's rows of the term a roster dropped. */
        val droppedOnServer: Set<String> = emptySet(),
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
     * Whether a full sync reconciles the course list: with its switch on
     * ([switchOn]), and not from switching course sync back on until the
     * re-enable check finds no difference or the user has answered it. A
     * reconcile in that window would answer for the user — it un-hides a
     * course deleted here while sync was off, because the server still lists
     * it, and Use Local could then no longer keep it deleted. iOS holds the
     * course list the same way.
     */
    fun syncsCourseList(switchOn: Boolean, pendingConflictCategories: Set<String>): Boolean =
        switchOn && REENABLE_CATEGORY !in pendingConflictCategories

    private const val REENABLE_CATEGORY = "courses"

    /**
     * Whether a full sync applies the server's course colours: with their
     * switch on ([switchOn]), and not from switching colour sync back on
     * until the re-enable check finds no difference or the user has answered
     * it. Applied in that window, the server's colours would answer for the
     * user, and Use Local could no longer keep this phone's. iOS holds the
     * course overrides the same way.
     */
    fun syncsCourseColors(switchOn: Boolean, pendingConflictCategories: Set<String>): Boolean =
        switchOn && "course_colors" !in pendingConflictCategories

    /** [syncsCourseColors] for the custom names and their re-enable check. */
    fun syncsCourseNames(switchOn: Boolean, pendingConflictCategories: Set<String>): Boolean =
        switchOn && "course_names" !in pendingConflictCategories

    /**
     * What a full sync does with the server's `courses_reset_at`: whether to
     * [apply][CourseReset.apply] it, and what to [remember][CourseReset.remember]
     * for the next sync to compare. Both times are the server's, so a phone
     * whose clock runs ahead or behind can neither miss a reset nor apply one
     * twice.
     *
     * [lastSeen] is the value the last full sync remembered: null before the
     * account's first sync here, which has nothing from before a reset to drop
     * and so only remembers the server's, and empty once a sync found none, so
     * that the first reset after it counts as new. As iOS's
     * `AppState.coursesReset` (tigerduck-app/tigerduck-app#236).
     * [legacyLastSyncMs] is an older build's stamp; see the first branch.
     */
    fun coursesReset(server: String?, lastSeen: String?, legacyLastSyncMs: Long = 0L): CourseReset {
        val serverAt = backendInstant(server)
        if (lastSeen == null) {
            // The first sync since the update, on an install an older build
            // synced: it kept this phone's clock at its last sync, the old
            // rule, read this once so a reset made elsewhere just before the
            // update is not remembered unapplied.
            val applyLegacy = legacyLastSyncMs > 0 && serverAt != null && serverAt.toEpochMilli() > legacyLastSyncMs
            return CourseReset(apply = applyLegacy, remember = server.takeIf { serverAt != null } ?: "")
        }
        if (serverAt == null) return CourseReset(apply = false, remember = lastSeen)
        val seenAt = backendInstant(lastSeen)
        return CourseReset(apply = seenAt == null || serverAt > seenAt, remember = server!!)
    }

    data class CourseReset(val apply: Boolean, val remember: String)

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

    /**
     * Courses with server colours applied, or null when nothing changed —
     * the caller uses null to skip a cache write and a widget reload.
     *
     * An override matches on courseNo first and Moodle id second, because
     * manually-added courses may have no courseNo the server recognises.
     *
     * Applied in one pass, oldest pick first (`color_hex_updated_at`, with
     * a row that has none counted as the oldest and ties broken by course
     * number), each as a pick on the class table is ([CourseColorPick]): a
     * course pinned to the colour moves to a colour of its own. So where the
     * server holds two courses on one colour, as an older build left it, the
     * latest pick keeps it, the same way on every device and every sync, and
     * the course that moved is reported in [ColorOutcome.moved] for the
     * caller to send, so the server stops holding the duplicate. Applied row
     * by row, both kept the colour for good (tigerduck-app-android#295). The
     * iPhone app applies them in the same order
     * (`AppState.serverCourseColors`, tigerduck-app/tigerduck-app#252).
     *
     * [held] are the keys ([PendingCourseOverride.key]) of the colours this
     * phone has waiting to go up, or sent after the snapshot was fetched
     * (`PendingCourseOverrides.held`): the snapshot's value for them is the
     * older one. Such a course's own colour counts as the latest pick in
     * place of the server's, so a course the snapshot has on that colour
     * moves off it here, as it will everywhere once the PATCH lands.
     */
    fun applyColorOverrides(
        courses: List<Course>,
        overrides: List<CourseOverrideResult>,
        syncColors: Boolean,
        held: Set<String> = emptySet(),
    ): ColorOutcome? {
        if (!syncColors || courses.isEmpty()) return null
        val serverHex = mutableMapOf<String, String>()
        val picks = courses.mapNotNull { course ->
            // By the course's own Moodle id first: the snapshot carries every
            // term's rows, and a course taken again keeps its number.
            val override = course.moodleIdNumber?.let { id -> overrides.find { it.moodleCourseId == id } }
                ?: overrides.find { it.courseNo == course.courseNo }
            // By the row's id and by the course's own: a row made before the
            // course had an idnumber is keyed by its term and number instead.
            // A held course counts with no row of its own too: a row's colour
            // on another course would move it off the one it has waiting.
            val pending = override?.let { PendingCourseOverride.colorKey(it.moodleCourseId) in held } == true ||
                course.moodleIdNumber?.let { PendingCourseOverride.colorKey(it) in held } == true
            val hex = (if (pending) course.customColorHex else override?.colorHex) ?: return@mapNotNull null
            serverHex[course.courseNo] = hex.uppercase()
            val pickedAt = if (pending) Instant.MAX else backendInstant(override?.colorHexUpdatedAt) ?: Instant.MIN
            Triple(course.courseNo, hex, pickedAt)
        }.sortedWith(compareBy({ it.third }, { it.first }))

        var updated = courses
        val displaced = mutableSetOf<String>()
        for ((courseNo, hex, _) in picks) {
            val pick = CourseColorPick.assign(updated, courseNo, hex, CourseColorPick.shownColors(updated))
            updated = pick.courses
            displaced += pick.changed.keys - courseNo
        }
        val moved = updated
            .filter { it.courseNo in displaced && it.customColorHex?.uppercase() != serverHex[it.courseNo] }
            .mapNotNull { course -> course.customColorHex?.let { course.courseNo to it } }
            .toMap()
        return if (updated == courses && moved.isEmpty()) null else ColorOutcome(updated, moved)
    }

    /**
     * Custom names merged per (courseNo, locale), or null when nothing
     * changed. An empty name from the server means "cleared", so it removes
     * that locale rather than storing a blank; a course left with no locales
     * drops out of the map entirely instead of leaving an empty entry behind.
     *
     * [held] are the keys ([PendingCourseOverride.key]) of the names this
     * phone has waiting to go up, or sent after the snapshot was fetched
     * (`PendingCourseOverrides.held`): the snapshot's value for them is the
     * older one, and is left out.
     */
    fun mergeCustomNames(
        existing: Map<String, Map<String, String>>,
        overrides: List<CourseOverrideResult>,
        syncNames: Boolean,
        held: Set<String> = emptySet(),
    ): Map<String, Map<String, String>>? {
        if (!syncNames) return null
        val merged = existing.mapValues { it.value.toMutableMap() }.toMutableMap()
        var changed = false
        for (o in overrides) {
            val no = o.courseNo ?: continue
            val names = o.customNames.filterKeys { PendingCourseOverride.nameKey(o.moodleCourseId, it) !in held }
            if (names.isEmpty()) continue
            val entry = merged[no]?.toMutableMap() ?: mutableMapOf()
            for ((locale, name) in names) {
                if (name.isEmpty()) entry.remove(locale) else entry[locale] = name
            }
            if (entry.isEmpty()) merged.remove(no) else merged[no] = entry
            changed = true
        }
        return if (changed) merged.mapValues { it.value.toMap() } else null
    }
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
