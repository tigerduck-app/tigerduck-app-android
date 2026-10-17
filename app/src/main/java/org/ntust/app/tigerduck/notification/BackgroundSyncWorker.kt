package org.ntust.app.tigerduck.notification

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.academic.SemesterTerm
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.CourseIgnoreSync
import org.ntust.app.tigerduck.data.CourseTombstoneKeys
import org.ntust.app.tigerduck.auth.StoredPasswordLatch
import org.ntust.app.tigerduck.data.PendingCourseAdds
import org.ntust.app.tigerduck.data.PendingCourseOverrides
import org.ntust.app.tigerduck.data.UnsentMarks
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.ui.screen.home.BackgroundMarkStep
import org.ntust.app.tigerduck.ui.screen.home.CourseSyncReconciler
import org.ntust.app.tigerduck.ui.screen.home.sendMovedColors
import org.ntust.app.tigerduck.data.CourseRosterMerge
import org.ntust.app.tigerduck.data.RefreshPolicy
import org.ntust.app.tigerduck.ui.RefreshPolicies
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.network.MoodleService
import org.ntust.app.tigerduck.network.SemesterCatalog
import org.ntust.app.tigerduck.shared.clock.AppClock
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import org.ntust.app.tigerduck.util.logSummary
import org.ntust.app.tigerduck.util.toCreditsOrZero

enum class SyncSource { NONE, BACKEND, LOCAL }

@HiltWorker
class BackgroundSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val authService: AuthService,
    private val moodleService: MoodleService,
    private val courseService: CourseService,
    private val semesterCatalog: SemesterCatalog,
    private val dataCache: DataCache,
    private val notificationScheduler: AssignmentNotificationScheduler,
    private val liveActivityManager: org.ntust.app.tigerduck.liveactivity.LiveActivityManager,
    private val prefs: org.ntust.app.tigerduck.data.preferences.AppPreferences,
    private val widgetUpdater: org.ntust.app.tigerduck.widget.WidgetUpdater,
    private val syncApiClient: org.ntust.app.tigerduck.push.SyncApiClient,
    private val pushApiClient: org.ntust.app.tigerduck.push.PushApiClient,
    private val authTokenManager: org.ntust.app.tigerduck.auth.AuthTokenManager,
    private val fcmBootstrap: org.ntust.app.tigerduck.push.FcmBootstrap,
    private val notificationSettingsSync: org.ntust.app.tigerduck.push.NotificationSettingsSync,
    private val courseIgnoreSync: CourseIgnoreSync,
    private val storedPasswordLatch: StoredPasswordLatch,
    private val unsentMarks: UnsentMarks,
    private val pendingCourseAdds: PendingCourseAdds,
    private val pendingCourseOverrides: PendingCourseOverrides,
    private val academicCalendar: AcademicCalendarStore,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : CoroutineWorker(context, params) {

    @Deprecated("Use prefs.lastSyncSource instead", level = DeprecationLevel.HIDDEN)
    var lastSyncSource: SyncSource = SyncSource.NONE
        private set

    override suspend fun doWork(): Result {
        // Before the credentials check, so every run can retry a device
        // registration that failed while the process stayed warm. Whether one
        // is due, and the consent and flavor gates, are decided inside; a
        // no-op on fdroid.
        fcmBootstrap.retryRegistrationIfDue()
        val studentId = authService.storedStudentId
        val password = authService.storedPassword
        if (studentId.isNullOrBlank() || password.isNullOrBlank()) return Result.success()

        // A triggered run fetches everything: the server asked for it because
        // something changed. What it announced may postdate an answer the
        // services are still sharing from a foreground fetch a moment ago.
        val triggered = inputData.getBoolean(KEY_TRIGGERED, false)
        if (triggered) {
            moodleService.expireSharedResults()
            courseService.expireSharedResults()
        }
        val now = System.currentTimeMillis()

        // The backend sync stays hourly, as the whole run was before it grew
        // more frequent for assignments: it is TigerDuck's own server, and
        // the foreground and pushes keep it current in between.
        val overridesDue = triggered ||
            RefreshPolicy.hasPassed(prefs.backgroundOverridesSyncedAtMs, now, OVERRIDES_EVERY)
        if (overridesDue) {
            syncOverridesFromBackend()
            // Signed out meanwhile: the stamp went with the account.
            if (authService.storedStudentId != studentId) return Result.success()
            prefs.backgroundOverridesSyncedAtMs = now
        }

        // The school servers only for data a page asks the worker to keep
        // fresh, and only once it is older than that page allows — see
        // RefreshPolicies. For assignments the age is the shared stamp, so a
        // fetch made in the foreground minutes ago is not repeated here; the
        // run used to redo the whole pipeline every hour regardless, which
        // over a day made it the app's largest source of traffic.
        //
        // Except on a retry. Only the school data fails a run, and the fetch
        // dates the data before the run stores it, so a run that failed after
        // its fetch left a fresh stamp over nothing stored; its retry would
        // otherwise skip the fetch and report success.
        val retry = runAttemptCount > 0
        // And once a term, whatever the pages ask: see firstRunOfTerm.
        val today = AppClock.localDateTime().toLocalDate()
        val term = academicCalendar.current().currentTerm(today)
        val coursesDue = triggered || retry ||
            firstRunOfTerm(prefs.backgroundCoursesTerm, term, today) ||
            RefreshPolicies.coursesEvery?.let {
                RefreshPolicy.hasPassed(prefs.backgroundCoursesSyncedAtMs, now, it)
            } == true
        val assignmentsDue = triggered || retry || RefreshPolicies.assignmentsEvery?.let {
            RefreshPolicy.hasPassed(prefs.schoolDataSyncedAtMs.value, now, it)
        } == true

        val coursesOk = !coursesDue || syncCourses(studentId, password)
        if (authService.storedStudentId != studentId) return Result.success()
        if (coursesDue && coursesOk) {
            prefs.backgroundCoursesSyncedAtMs = now
            // The term the fetch covered, by the app's clock: see firstRunOfTerm.
            if (term != null && !today.isBefore(term.start)) prefs.backgroundCoursesTerm = term.code
        }
        val assignmentsOk = when {
            !assignmentsDue -> true
            // What the server announced may be a submission made elsewhere.
            triggered -> moodleService.recheckingSubmissions { syncAssignments() }
            else -> syncAssignments()
        }
        if (authService.storedStudentId != studentId) return Result.success()

        // Only after something was fetched: with nothing new in the cache
        // there is nothing for the Live Update, the widgets or the open
        // screens to pick up.
        if (overridesDue || coursesDue || assignmentsDue) {
            liveActivityManager.refreshAndWait()
            widgetUpdater.updateAll()
            dataCache.notifyBackgroundSyncComplete()
        }

        if (coursesOk && assignmentsOk) return Result.success()
        return resultForFailedSync(triggered, runAttemptCount, passwordRejected = storedPasswordLatch.isRejected)
    }

    private suspend fun syncOverridesFromBackend() {
        // cloudSyncEnabled already reads false on fdroid at its source
        // (AppPreferences.cloudSyncEnabled), so no separate flavor check
        // is needed here.
        if (!prefs.cloudSyncEnabled) {
            prefs.setLastSyncSource(SyncSource.NONE)
            return
        }
        // No v3 JWT, and signing in got none: NONE, not LOCAL —
        // LOCAL would light the "sync from local only" indicator even though no
        // sync was ever attempted. Mirrors HomeBackendSync.pull.
        if (!authTokenManager.signInIfSignedOut()) {
            prefs.setLastSyncSource(SyncSource.NONE)
            return
        }
        try {
            val account = authService.storedStudentId
            val generation = courseIgnoreSync.currentGeneration()
            courseIgnoreSync.retryPending()
            // Marks and course colours and names whose PATCH failed, and
            // course adds whose upload did, go first, so the snapshot has them.
            unsentMarks.retry()
            pendingCourseAdds.retry()
            pendingCourseOverrides.retry()
            val result = syncApiClient.fetchFullSync()
            pendingCourseAdds.listed(result.serverCourses)
            // With the Assignments switch off, the re-enable check pending,
            // or marks the server lacks on the account's first sync here
            // (Home's sync sends those up), the marks stay as they are.
            val marks = BackgroundMarkStep.over(prefs, dataCache, unsentMarks).pull(result)
            if (marks.taken && authService.isStillSignedInAs(account)) prefs.assignmentMarksSyncedOnce = true
            val marksChanged = marks.changed
            courseIgnoreSync.applySnapshot(result, generation)

            // Not over a colours re-enable check still waiting for the user.
            val syncColors = CourseSyncReconciler.syncsCourseColors(
                prefs.syncCourseColors, prefs.pendingConflictCategories,
            )
            if (syncColors && result.courseOverrides.isNotEmpty()) {
                // Not over a colour this phone has waiting to go up, or sent
                // since the fetch: the snapshot's is the older.
                applyCourseOverridesBackground(result.courseOverrides, pendingCourseOverrides.held(result.fetchedAtMs))
            }
            if (CourseSyncReconciler.syncsCourseList(prefs.syncCourses, prefs.pendingConflictCategories)) {
                reconcileCurrentSemester(result)
            }
            prefs.setLastSyncSource(SyncSource.BACKEND)
            // Where a change another device made to the reminder settings
            // reaches this phone while the app stays closed: syncAssignments()
            // below arms reminders from the local values, so they have to be
            // current first — otherwise a switch turned off on the iPhone
            // keeps firing here until the app is opened. Awaited for that
            // ordering, but run on the application scope so a stopped worker
            // cannot cut the apply short, and on Main because pullNow()'s
            // check-and-apply requires it. It catches its own transport
            // failures; the guard keeps anything else from failing this sync.
            appScope.async(Dispatchers.Main) {
                runCatching { notificationSettingsSync.pullNow() }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        Log.w(TAG, "notification settings pull failed: ${e.logSummary()}")
                    }
            }.await()
            // Homework ignored or marked done on another device takes its
            // reminders here with it, whether or not this run fetches
            // assignments and arms them again.
            if (marksChanged && prefs.notifyAssignments) {
                notificationScheduler.scheduleAll(
                    dataCache.loadOutstandingAssignments(),
                    prefs.notifyAssignmentOffsets,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            prefs.setLastSyncSource(SyncSource.LOCAL)
            Log.w(TAG, "override sync failed: ${e.logSummary()}")
        }
    }

    /**
     * The current term's slice of the foreground sync's per-semester
     * reconcile, through the same [CourseSyncReconciler] so the rules
     * cannot drift.
     *
     * This used to carry its own copy of the rules, and it had drifted: it
     * never read `course_tombstones` at all, and when the server had nothing
     * for this term it fell back to the roster flattened across every term
     * — so a semester another device had just reset looked populated from
     * here, and nothing was hidden.
     *
     * Deliberately narrower than the foreground path: only the current
     * term, no merge of rows this device lacks (that is left to the
     * foreground sync), and no pinning of legacy bare tombstones to terms
     * (`HomeBackendSync.migratedTombstones`) — so an un-hide here can lift
     * a bare entry for every term at once, as the code it replaced also
     * did, and the cache prune below ignores bare entries rather than
     * delete a retaken course on the strength of one.
     */
    private suspend fun reconcileCurrentSemester(result: BackendSyncResult) {
        val semester = courseService.currentSemesterCode()
        // Mid-reset, or a snapshot fetched before this device reset the
        // term — see CourseSyncReconciler.termsResetAfter.
        val resetAt = dataCache.loadSemesterResetAt()
        dataCache.clearSemesterResetAt(CourseSyncReconciler.resetStampsOutlived(result.fetchedAtMs, resetAt))
        val stale = CourseSyncReconciler.termsResetAfter(
            result.fetchedAtMs, resetAt, dataCache.resettingSemesters(),
        )
        if (semester in stale) return
        val localCourses = dataCache.loadCourses(semester)
        val stored = dataCache.loadDeletedCourseNos()
        val storedKnown = dataCache.loadServerKnownNos()
        val knownHere = storedKnown[semester].orEmpty().toSet()
        val outcome = CourseSyncReconciler.reconcileSemester(
            semester = semester,
            localCourses = localCourses,
            serverRows = result.serverCourses.filter { it.semester == semester },
            tombstoneNos = CourseSyncReconciler.tombstoneNosFor(semester, result.tombstones),
            tombstones = stored,
            serverKnownNos = knownHere,
            selectionDroppedNos = dataCache.loadSelectionDroppedNos()[semester].orEmpty().toSet(),
        )
        if (outcome.tombstones != stored) {
            dataCache.saveDeletedCourseNos(outcome.tombstones)
        }
        // Unconditional: a course hidden by an earlier build and left in
        // the cache is pruned on the first run, not the next change.
        CourseSyncReconciler.pruneHidden(semester, localCourses, outcome.tombstones, legacyBare = false)
            ?.let { kept ->
                dataCache.saveCourses(kept, semester)
                // A course hidden since takes its waiting colour and name
                // with it; see PendingCourseOverrides.forget.
                pendingCourseOverrides.forget(localCourses.filter { it !in kept }.mapNotNull { it.moodleIdNumber })
            }
        var known = outcome.serverKnownNos
        if (outcome.uploadLocal) {
            runCatching { pushApiClient.uploadCourses(outcome.toUpload, semester) }
                .onSuccess { accepted -> if (!accepted.isNullOrEmpty()) known = known + accepted }
                .onFailure { e -> Log.w(TAG, "[Sync] auto-upload failed: ${e.logSummary()}") }
            Log.i(TAG, "[Sync] backend empty, auto-uploaded ${outcome.toUpload.size} courses")
        }
        if (known != knownHere) {
            dataCache.saveServerKnownNos(storedKnown + (semester to known.sorted()))
        }
    }

    /**
     * Home's colour pass ([CourseSyncReconciler.applyColorOverrides]) over the
     * cached term, [held] being what the snapshot must not overwrite.
     */
    private suspend fun applyCourseOverridesBackground(
        overrides: List<org.ntust.app.tigerduck.push.CourseOverrideResult>,
        held: Set<String>,
    ) {
        val outcome = CourseSyncReconciler.applyColorOverrides(
            courses = dataCache.loadCourses(),
            overrides = overrides,
            syncColors = true,
            held = held,
        ) ?: return
        dataCache.saveCourses(outcome.courses)
        widgetUpdater.requestUpdate()
        sendMovedColors(outcome.courses, outcome.moved) { moodleId, hex ->
            pendingCourseOverrides.send(moodleId, colorHex = hex)
        }
    }

    private suspend fun syncCourses(studentId: String, password: String): Boolean {
        return try {
            // TTL-throttled; resolved before the gate below, which depends
            // on it.
            semesterCatalog.refreshIfStale()
            val semester = courseService.currentSemesterCode()
            // 選課 serves exactly one term and its 選課清單 page carries no
            // term marker, so its course numbers belong to whichever term the
            // catalogue reports as open. That runs ahead of the term in
            // session, and importing them here would file the *next* term's
            // enrolments into this term's cache.
            val servesSelectionSemester = semester == semesterCatalog.selectionSemesterCode()
            val (selectionNos, moodleAll) = coroutineScope {
                val selectionDef = async {
                    if (!servesSelectionSemester) return@async emptyList()
                    try {
                        courseService.fetchEnrolledCourseNos(studentId, password)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to fetch enrolled course numbers: ${e.logSummary()}")
                        null
                    }
                }
                val moodleDef = async {
                    try {
                        moodleService.fetchEnrolledCourses()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to fetch Moodle enrolled courses: ${e.logSummary()}")
                        null
                    }
                }
                selectionDef.await() to moodleDef.await()
            }

            // Before the merge below rewrites the cache — see
            // DataCache.recordSelectionRoster.
            selectionNos?.let { dataCache.recordSelectionRoster(semester, it) }

            if (selectionNos == null && moodleAll == null) return false
            // With 選課 gated off, Moodle is the only source — its failure is
            // a total failure, so retry rather than report success on an
            // empty roster.
            if (!servesSelectionSemester && moodleAll == null) return false
            val moodleForSemester = moodleAll
                .orEmpty()
                .filter { it.semesterCode == semester && it.courseNo.isNotEmpty() }
            val moodleByNo = moodleForSemester.associateBy { it.courseNo }

            val orderedCourseNos =
                CourseRosterMerge.rosterOrder(selectionNos, moodleForSemester)

            if (orderedCourseNos.isEmpty()) return true

            val fetched = coroutineScope {
                orderedCourseNos.map { courseNo ->
                    async {
                        try {
                            val results = courseService.lookupCourse(semester, courseNo)
                            if (results.isNotEmpty()) {
                                val r = results.first()
                                val schedule = courseService.mergeSchedules(
                                    *results.map { it.node }.toTypedArray()
                                )
                                val classroomMap = courseService.buildClassroomMap(results)
                                val allRooms = LinkedHashSet<String>().apply {
                                    for (row in results) {
                                        Course.splitRooms(row.classRoomNo ?: "")
                                            .forEach { add(it) }
                                    }
                                }
                                Course.fromSchedule(
                                    courseNo = r.courseNo,
                                    courseName = r.courseName,
                                    instructor = r.courseTeacher,
                                    credits = r.creditPoint.toCreditsOrZero(),
                                    classroom = allRooms.joinToString(", "),
                                    enrolledCount = r.chooseStudent ?: 0,
                                    maxCount = r.maxEnrollment,
                                    schedule = schedule,
                                    classroomMap = classroomMap,
                                    moodleIdNumber = moodleByNo[courseNo]?.idnumber
                                        ?: "${r.semester}${r.courseNo}",
                                    moodleNumericCourseId = moodleByNo[courseNo]?.id,
                                    // Carried here too: the next foreground
                                    // merge takes this row as-is, so leaving
                                    // them out would erase them.
                                    dimension = CourseService.firstNonEmpty(results.map { it.dimension }),
                                    allYear = CourseService.firstNonEmpty(results.map { it.allYear }),
                                )
                            } else {
                                CourseService.fallbackCourseFromMoodle(
                                    courseNo,
                                    moodleByNo[courseNo]
                                )
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Course lookup failed: ${e.logSummary()}")
                            CourseService.fallbackCourseFromMoodle(courseNo, moodleByNo[courseNo])
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            if (fetched.isNotEmpty()) {
                // Preserve user-picked tile colors and manually-added courses
                // across the background refresh.
                val cached = dataCache.loadCourses()
                val deletedNos = CourseTombstoneKeys.hiddenIn(
                    courseService.currentSemesterCode(),
                    dataCache.loadDeletedCourseNos(),
                )
                val cachedByNo = cached.associateBy { it.courseNo }
                val fetchedWithState = fetched.map { c ->
                    val prior = cachedByNo[c.courseNo]
                    c.copy(
                        customColorHex = prior?.customColorHex,
                        isManual = prior?.isManual == true,
                    )
                }
                val fetchedNos = fetchedWithState.map { it.courseNo }.toSet()
                val rosterNos = orderedCourseNos.toSet()
                val unresolvedNos = rosterNos - fetchedNos
                val manualLeftovers = cached.filter { it.isManual && it.courseNo !in fetchedNos }
                // Keep stale non-manual cache entries only for courses still in
                // this cycle's roster but unresolved due to transient lookup failures.
                val cachedRemoteFallbacks =
                    cached.filter { !it.isManual && it.courseNo in unresolvedNos }
                val merged = (fetchedWithState + manualLeftovers + cachedRemoteFallbacks)
                    .filter { it.courseNo !in deletedNos }
                dataCache.saveCourses(merged)
                runCatching { pushApiClient.uploadCourses(merged, semester) }
                    .onFailure {
                        prefs.setLastSyncSource(SyncSource.LOCAL)
                        Log.w(TAG, "uploadCourses failed (non-fatal): ${it.logSummary()}")
                    }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Course refresh failed: ${e.logSummary()}")
            false
        }
    }


    private suspend fun syncAssignments(): Boolean {
        return try {
            val enrolled = moodleService.fetchEnrolledCourses()
            // Null, which keeps the cache, when no course was left to ask
            // about; an empty answer empties it, and the
            // reminders below go with it — see assignmentsToStore.
            val answer = moodleService.fetchAssignments(enrolled) ?: return true
            val merged = CourseRosterMerge.assignmentsToStore(
                remote = answer.assignments,
                cached = dataCache.loadAssignments(),
            ) ?: return true
            // Uploaded as written: the write reconciles the list against
            // newer answers, and nothing is uploaded when it was refused.
            val written = moodleService.storeAssignments(merged, answer) ?: return true
            runCatching { pushApiClient.uploadAssignments(written) }
                .onFailure {
                    prefs.setLastSyncSource(SyncSource.LOCAL)
                    Log.w(TAG, "uploadAssignments failed (non-fatal): ${it.logSummary()}")
                }

            if (prefs.notifyAssignments) {
                notificationScheduler.scheduleAll(
                    dataCache.loadOutstandingAssignments(),
                    prefs.notifyAssignmentOffsets,
                )
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Assignment refresh failed: ${e.logSummary()}")
            false
        }
    }

    companion object {
        private const val TAG = "BackgroundSyncWorker"

        // Keep the old unique name so existing users' enqueued 6h work is
        // replaced in place via ExistingPeriodicWorkPolicy.UPDATE instead of
        // leaving an orphaned entry behind.
        internal const val UNIQUE_NAME = "homework_refresh_periodic"

        /** How often the run syncs with TigerDuck's backend, whatever the pages ask. */
        private val OVERRIDES_EVERY = 1.hours

        /**
         * The run's period: the most frequent of what the pages ask the
         * worker for (RefreshPolicies) and the hourly backend sync. Each run
         * then fetches only what is due.
         */
        internal val period: Duration = listOfNotNull(
            RefreshPolicies.assignmentsEvery,
            RefreshPolicies.coursesEvery,
            OVERRIDES_EVERY,
        ).min()

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<BackgroundSyncWorker>(
                period.inWholeMinutes, TimeUnit.MINUTES,
            ).setConstraints(constraints).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        // Called on sign-out, so it stops the triggered syncs as well as the
        // periodic one: a triggered sync left running went on writing the
        // departing account's courses and assignments over the cache
        // AuthService.logout() was clearing.
        fun cancel(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(UNIQUE_NAME)
            workManager.cancelUniqueWork(TRIGGER_UNIQUE_NAME)
        }

        // A server sync trigger asks for a sync rather than adding one: FCM
        // can deliver several in a burst, and each run syncs the backend and
        // Moodle in full. Network-bound, so a run never starts offline only
        // to fail.
        private const val TRIGGER_UNIQUE_NAME = "sync_trigger"
        private const val KEY_TRIGGERED = "triggered"

        // Under the backoff requestSync sets, a triggered run that keeps
        // failing tries again after 30 s, 1 min and 2 min, then gives up:
        // three and a half minutes of waiting in all, against the hours an
        // unbounded retry reaches.
        private const val TRIGGERED_RETRIES = 3
        private const val TRIGGERED_BACKOFF_SECONDS = 30L

        fun requestSync(context: Context) {
            val workManager = WorkManager.getInstance(context)
            // Called from FcmService, where a throw takes the process down,
            // and which hands over one message at a time, so a wait here
            // holds up every push behind this one; the read gives up after
            // two seconds.
            val states = runCatching {
                workManager.getWorkInfosForUniqueWork(TRIGGER_UNIQUE_NAME)
                    .get(2, TimeUnit.SECONDS)
                    .map { it.state }
            }.getOrElse { e ->
                Log.w(TAG, "could not read queued syncs: ${e.logSummary()}")
                null
            }
            // Unread, the queue is appended to rather than kept: KEEP would
            // drop this trigger whenever a sync is running, though that sync
            // may have fetched before the change it announces. Appending
            // queues a sync when none is queued and a follow-up when one is
            // running, and with one already waiting it costs one extra sync,
            // but it never drops a trigger.
            val policy = if (states == null) {
                ExistingWorkPolicy.APPEND_OR_REPLACE
            } else {
                triggerPolicy(states) ?: return
            }
            val request = OneTimeWorkRequestBuilder<BackgroundSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInputData(workDataOf(KEY_TRIGGERED to true))
                // WorkManager's default today, set here because the bound
                // on a triggered run's retries is reasoned from it.
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    TRIGGERED_BACKOFF_SECONDS, TimeUnit.SECONDS,
                )
                .build()
            workManager.enqueueUniqueWork(TRIGGER_UNIQUE_NAME, policy, request)
        }

        /**
         * How a sync trigger joins the triggered syncs in [states], or null
         * when it need not queue one. A sync still waiting to start fetches
         * whatever the trigger announces. One already running may have
         * fetched before that change landed, so the trigger queues one
         * follow-up behind it — which the next trigger then finds waiting.
         */
        internal fun triggerPolicy(states: List<WorkInfo.State>): ExistingWorkPolicy? = when {
            states.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED } -> null
            WorkInfo.State.RUNNING in states -> ExistingWorkPolicy.APPEND_OR_REPLACE
            else -> ExistingWorkPolicy.KEEP
        }

        /**
         * Whether this is the first run in [term], by the app's clock ([today]), since courses were
         * last fetched in [fetchedTerm]: the new term's courses may never have been fetched, and
         * the reminders planned from them need them. Not for a term yet to begin, which the
         * calendar gives before its first term starts. Compared by term code, not by time, so a
         * debug clock moved into a later term still fetches it once and no more.
         */
        internal fun firstRunOfTerm(fetchedTerm: String?, term: SemesterTerm?, today: LocalDate): Boolean =
            term != null && !today.isBefore(term.start) && fetchedTerm != term.code

        /**
         * What a run that could not fetch courses or assignments reports.
         * The periodic run retries on its own schedule. A triggered run
         * retries [TRIGGERED_RETRIES] times and then gives up: given up at
         * once, a school server that was down for a minute left the change
         * the trigger announced missing until the next trigger or the
         * hourly run; retried without end, its backoff grows to hours, and
         * every trigger in the meantime is dropped behind it (requestSync
         * drops one that finds a sync queued).
         */
        internal fun resultForFailedSync(
            triggered: Boolean,
            runAttemptCount: Int,
            passwordRejected: Boolean = false,
        ): ListenableWorker.Result =
            // A retry could only fail the same way: once the school refused
            // the stored password, no automatic sign-in posts it until the
            // user signs in again (StoredPasswordLatch).
            if (passwordRejected || (triggered && runAttemptCount >= TRIGGERED_RETRIES)) {
                ListenableWorker.Result.success()
            } else {
                ListenableWorker.Result.retry()
            }
    }
}
