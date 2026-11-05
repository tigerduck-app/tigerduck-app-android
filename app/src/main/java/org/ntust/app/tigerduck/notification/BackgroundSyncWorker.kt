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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.academic.SemesterTerm
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.auth.session.StartsAccountWork
import org.ntust.app.tigerduck.auth.StoredPasswordLatch
import org.ntust.app.tigerduck.data.CourseRosterMerge
import org.ntust.app.tigerduck.data.RefreshPolicy
import org.ntust.app.tigerduck.sync.SchoolUploads
import org.ntust.app.tigerduck.sync.SyncMode
import org.ntust.app.tigerduck.sync.Synchronizer
import org.ntust.app.tigerduck.ui.RefreshPolicies
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.network.MoodleService
import org.ntust.app.tigerduck.network.SemesterCatalog
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.clock.AppClock
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import org.ntust.app.tigerduck.util.logSummary

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
    pushApiClient: org.ntust.app.tigerduck.push.PushApiClient,
    private val authTokenManager: org.ntust.app.tigerduck.auth.AuthTokenManager,
    private val fcmBootstrap: org.ntust.app.tigerduck.push.FcmBootstrap,
    private val notificationSettingsSync: org.ntust.app.tigerduck.push.NotificationSettingsSync,
    private val storedPasswordLatch: StoredPasswordLatch,
    private val academicCalendar: AcademicCalendarStore,
    private val termCourseSync: TermCourseSync,
    private val synchronizer: Synchronizer,
    /** Where the round's assignments go up: after this process's first pull in the app. */
    private val schoolUploads: SchoolUploads = SchoolUploads.atOnce(dataCache, pushApiClient),
) : CoroutineWorker(context, params) {

    @Deprecated("Use prefs.lastSyncSource instead", level = DeprecationLevel.HIDDEN)
    var lastSyncSource: SyncSource = SyncSource.NONE
        private set

    @OptIn(StartsAccountWork::class)
    override suspend fun doWork(): Result {
        // Before the credentials check, so every run can retry a device
        // registration that failed while the process stayed warm. Whether one
        // is due, and the consent and flavor gates, are decided inside; a
        // no-op on fdroid.
        fcmBootstrap.retryRegistrationIfDue()
        // Before the student: a sign-out between the two reads ends the run.
        val signIn = authTokenManager.session.current
        val studentId = authService.storedStudentId
        val password = authService.storedPassword
        if (studentId.isNullOrBlank() || password.isNullOrBlank()) return Result.success()

        // A triggered run fetches everything: the server asked for it because something changed.
        // What it announced may postdate an answer the services are still sharing from a foreground
        // fetch a moment ago.
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
            RefreshPolicy.hasPassed(prefs.school.backgroundOverridesSyncedAtMs.get(), now, OVERRIDES_EVERY)
        if (overridesDue) {
            syncWithBackend(signIn)
            // Signed out meanwhile: the stamp went with the account, and a sign-out cannot clear
            // it between the check and the write.
            if (!prefs.school.backgroundOverridesSyncedAtMs.set(now, signIn)) return Result.success()
        }

        // The school servers only for data a page asks the worker to keep fresh, and only once it
        // is older than that page allows — see RefreshPolicies. For assignments the age is the
        // shared stamp, so a fetch made in the foreground minutes ago is not repeated here; the run
        // used to redo the whole pipeline every hour regardless, which over a day made it the app's
        // largest source of traffic.
        //
        // Except on a retry. Only the school data fails a run, and the fetch dates the data before
        // the run stores it, so a run that failed after its fetch left a fresh stamp over nothing
        // stored; its retry would otherwise skip the fetch and report success.
        val retry = runAttemptCount > 0
        // And once a term, whatever the pages ask: see firstRunOfTerm.
        val today = AppClock.localDateTime().toLocalDate()
        val term = academicCalendar.current().currentTerm(today)
        val coursesDue = triggered || retry ||
            firstRunOfTerm(prefs.school.backgroundCoursesTerm.get(), term, today) ||
            RefreshPolicies.coursesEvery?.let {
                RefreshPolicy.hasPassed(prefs.school.backgroundCoursesSyncedAtMs.get(), now, it)
            } == true
        val assignmentsDue = triggered || retry || RefreshPolicies.assignmentsEvery?.let {
            RefreshPolicy.hasPassed(prefs.schoolDataSyncedAtMs.value, now, it)
        } == true

        val coursesOk = !coursesDue || syncCourses(studentId, password, signIn)
        if (!signIn.isCurrent) return Result.success()
        if (coursesDue && coursesOk) {
            // Both or neither: a sign-out waits for the step, or comes first.
            signIn.ifCurrent {
                prefs.school.backgroundCoursesSyncedAtMs.set(now, signIn)
                // The term the fetch covered, by the app's clock: see firstRunOfTerm.
                if (term != null && !today.isBefore(term.start)) prefs.school.backgroundCoursesTerm.set(term.code, signIn)
            } ?: return Result.success()
        }
        val assignmentsOk = when {
            !assignmentsDue -> true
            // What the server announced may be a submission made elsewhere.
            triggered -> moodleService.recheckingSubmissions { syncAssignments() }
            else -> syncAssignments()
        }
        if (!signIn.isCurrent) return Result.success()

        // Only after something was fetched: with nothing new in the cache
        // there is nothing for the Live Update, the widgets or the open
        // screens to pick up.
        if (overridesDue || coursesDue || assignmentsDue) {
            refreshAfterRun(signIn, { liveActivityManager.refreshAndWait() }, { widgetUpdater.updateAll() }) {
                dataCache.notifyBackgroundSyncComplete()
            }
        }

        if (coursesOk && assignmentsOk) return Result.success()
        return resultForFailedSync(triggered, runAttemptCount, passwordRejected = storedPasswordLatch.postMustWait())
    }

    /**
     * The sync with TigerDuck's backend: the one synchronizer's pass, in background mode (C2).
     * Then, as part of the pass, the worker's own steps after it, which start work on its session:
     * the settings pull, and the reminders of homework ignored or marked done on another device.
     */
    private suspend fun syncWithBackend(signIn: SignIn) {
        synchronizer.sync(SyncMode.BACKGROUND, signIn, afterSync = { pass ->
            pullSettings(pass.result.currentRevision, signIn)
            // Homework ignored or marked done on another device takes its reminders here with it,
            // whether or not this run fetches assignments and arms them again.
            if (pass.marksChanged && prefs.notifyAssignments && signIn.isCurrent) {
                notificationScheduler.scheduleAll(
                    dataCache.loadOutstandingAssignments(),
                    prefs.notifyAssignmentOffsets,
                )
            }
        })
    }

    /**
     * How another device's change to the reminder settings reaches this phone with the app closed:
     * syncAssignments() arms from the local values, so they go first, or a switch turned off on the
     * iPhone keeps firing here. Awaited for that order, on the session so a stopped worker cannot
     * cut the apply short and a sign-out does, on Main as pullNow() requires. It catches its own
     * transport failures; the guard keeps anything else from failing this sync.
     */
    @OptIn(StartsAccountWork::class)
    private suspend fun pullSettings(knownRevision: Long?, signIn: SignIn) {
        authTokenManager.session.launch(signIn) {
            withContext(Dispatchers.Main) {
                runCatching { notificationSettingsSync.pullNow(knownRevision = knownRevision) }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        Log.w(TAG, "notification settings pull failed: ${e.logSummary()}")
                    }
            }
        }.join()
    }

    /** As [signIn], the sign-in the run began under: a sync that outlived it fetches and writes nothing. */
    private suspend fun syncCourses(studentId: String, password: String, signIn: SignIn): Boolean {
        return try {
            // TTL-throttled; resolved before the term, which depends on it.
            semesterCatalog.refreshIfStale()
            termCourseSync.sync(studentId, password, courseService.currentSemesterCode(), signIn = signIn)
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
            // After this process's first pull: a run that does not pull leaves it waiting for one.
            schoolUploads.assignments(written, answer.signIn) {
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
        // the sign-out (SignOutPlans) was clearing.
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
            // refusal's wait is over, which StoredPasswordRetry keeps.
            if (passwordRejected || (triggered && runAttemptCount >= TRIGGERED_RETRIES)) {
                ListenableWorker.Result.success()
            } else {
                ListenableWorker.Result.retry()
            }
    }
}
