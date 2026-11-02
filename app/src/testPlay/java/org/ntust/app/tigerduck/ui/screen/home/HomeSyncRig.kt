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
import org.ntust.app.tigerduck.sync.SyncPolicies
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
    val sync: HomeBackendSync,
)

/**
 * Builds [HomeSyncRig] over [w], its backend requests answered by [backend]. What would run past the
 * sync, on the application scope, goes to [idleScope], which never runs it: the notification
 * settings pull, the roster requests and a re-enable check. [ignores] is the course-ignore queue,
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
    val sync = HomeBackendSync(
        prefs = w.appPrefs,
        dataCache = w.dataCache,
        authService = w.auth,
        authTokenManager = w.tokens,
        syncApiClient = syncApi,
        pushApiClient = w.pushApi,
        notificationSettingsSync = w.notificationSettingsSync,
        courseService = w.courseService,
        semesterCatalog = w.semesterCatalog,
        widgetUpdater = w.widgetUpdater,
        academicCalendar = w.academicCalendar,
        courseIgnoreSync = ignores,
        unsentMarks = marks,
        pendingCourseAdds = adds,
        pendingCourseDeletes = deletes,
        pendingCourseOverrides = overrides,
        pendingHolidayOverrides = holidays,
        reenableCheck = ReenableCheck(w.appPrefs, syncApi, w.dataCache, w.semesterCatalog, ignores, w.auth, w.sessions, idleScope, context),
        termCourseSync = idleTermSync(w),
        appScope = idleScope,
        syncPolicies = SyncPolicies(w.appPrefs),
    )
    return HomeSyncRig(syncApi, marks, adds, deletes, overrides, holidays, ignores, sync)
}

/** Asks the school nothing: a run's roster requests never reach it. */
private fun idleTermSync(w: AccountWorld) = TermCourseSync(
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
