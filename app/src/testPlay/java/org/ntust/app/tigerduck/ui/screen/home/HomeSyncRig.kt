package org.ntust.app.tigerduck.ui.screen.home

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import org.ntust.app.tigerduck.auth.AccountWorld
import org.ntust.app.tigerduck.data.CourseIgnoreSync
import org.ntust.app.tigerduck.data.PendingCourseAdds
import org.ntust.app.tigerduck.data.PendingCourseDeletes
import org.ntust.app.tigerduck.data.PendingCourseOverrides
import org.ntust.app.tigerduck.data.PendingHolidayOverrides
import org.ntust.app.tigerduck.data.UnsentMarks
import org.ntust.app.tigerduck.notification.AssignmentNotificationScheduler
import org.ntust.app.tigerduck.notification.TermCourseSync
import org.ntust.app.tigerduck.push.SyncApiClient
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.sync.FirstPullGate
import org.ntust.app.tigerduck.sync.SyncPolicies
import org.ntust.app.tigerduck.sync.Synchronizer
import org.ntust.app.tigerduck.sync.reconcile.CourseResetReconciler
import org.ntust.app.tigerduck.sync.reconcile.HolidaysReconciler
import org.ntust.app.tigerduck.sync.reconcile.IgnoresReconciler
import org.ntust.app.tigerduck.sync.reconcile.MarksReconciler
import org.ntust.app.tigerduck.sync.reconcile.OverridesReconciler
import org.ntust.app.tigerduck.sync.reconcile.RosterReconciler
import org.ntust.app.tigerduck.ui.screen.settings.ReenableCheck

/** Home's full sync over [w]'s stores, with the queues it sends from, as Hilt builds them. */
internal class HomeSyncRig(
    val syncApi: SyncApiClient,
    val marks: UnsentMarks,
    val adds: PendingCourseAdds,
    val deletes: PendingCourseDeletes,
    val overrides: PendingCourseOverrides,
    val holidays: PendingHolidayOverrides,
    val ignores: CourseIgnoreSync,
    val gate: FirstPullGate,
    val sync: HomeBackendSync,
)

/**
 * Builds [HomeSyncRig] over [w], its backend requests answered by [backend]. What would run past the
 * sync, on the application scope, goes to [idleScope], which never runs it: the notification
 * settings pull, the roster requests, a re-enable check and the first-pull gate's sign-out watch. [ignores] is the course-ignore queue,
 * the real one unless a test needs its own.
 */
internal fun homeSyncRig(
    context: Context,
    w: AccountWorld,
    backend: OkHttpClient,
    idleScope: CoroutineScope,
    ignores: CourseIgnoreSync = CourseIgnoreSync(w.dataCache, w.pushApi, w.appPrefs, AssignmentNotificationScheduler(context)),
): HomeSyncRig {
    val syncApi = SyncApiClient(w.tokens, w.appPrefs, backend)
    val marks = UnsentMarks(w.dataCache, w.pushApi, w.appPrefs)
    val adds = PendingCourseAdds(w.dataCache, w.pushApi)
    val deletes = PendingCourseDeletes(w.dataCache, adds, w.pushApi)
    val overrides = PendingCourseOverrides(w.dataCache, w.pushApi, w.appPrefs)
    val holidays = PendingHolidayOverrides(w.appPrefs, w.pushApi)
    val policies = SyncPolicies(w.appPrefs)
    val gate = FirstPullGate(w.sessions, idleScope)
    val marksReconciler = MarksReconciler(w.appPrefs, w.dataCache, marks, w.pushApi)
    val overridesReconciler = OverridesReconciler(w.dataCache, w.widgetUpdater, overrides, policies)
    val synchronizer = Synchronizer(
        prefs = w.appPrefs,
        syncPolicies = policies,
        dataCache = w.dataCache,
        authTokenManager = w.tokens,
        syncApiClient = syncApi,
        courseIgnoreSync = ignores,
        unsentMarks = marks,
        pendingCourseAdds = adds,
        pendingCourseDeletes = deletes,
        pendingCourseOverrides = overrides,
        pendingHolidayOverrides = holidays,
        firstPullGate = gate,
        widgetUpdater = w.widgetUpdater,
        reenableCheck = ReenableCheck(w.appPrefs, syncApi, w.dataCache, w.semesterCatalog, ignores, w.auth, w.sessions, idleScope, context),
        notificationSettingsSync = w.notificationSettingsSync,
        appScope = idleScope,
        marks = marksReconciler,
        ignores = IgnoresReconciler(ignores),
        overrides = overridesReconciler,
        reset = CourseResetReconciler(w.appPrefs, w.dataCache, adds, deletes, overrides),
        holidays = HolidaysReconciler(holidays, w.academicCalendar),
        roster = RosterReconciler(
            w.dataCache, w.auth, w.pushApi, w.courseService, w.semesterCatalog, w.widgetUpdater, adds, deletes, overrides,
            idleTermSync(w), idleScope,
        ),
    )
    val sync = HomeBackendSync(synchronizer, w.auth, w.pushApi, w.notificationSettingsSync, marksReconciler, overridesReconciler)
    return HomeSyncRig(syncApi, marks, adds, deletes, overrides, holidays, ignores, gate, sync)
}

/** Asks the school nothing: a run's roster requests never reach it. */
internal fun idleTermSync(w: AccountWorld) = TermCourseSync(
    w.dataCache,
    sources = object : TermCourseSync.Sources {
        override fun servesSelection(semester: String) = false
        override suspend fun selection(studentId: String, password: String, fresh: Boolean) = emptyList<String>()
        override suspend fun moodle() = emptyList<org.ntust.app.tigerduck.network.model.MoodleEnrolledCourse>()
        override suspend fun lookUp(
            semester: String,
            courseNo: String,
            moodle: org.ntust.app.tigerduck.network.model.MoodleEnrolledCourse?,
            kept: Course?,
        ) = kept
    },
    uploader = { _, _, _, _, _ -> },
    waitingAdds = { emptySet() },
    uploading = { block -> block() },
    courseLocale = { "zh" },
    uploadFailed = {},
)
