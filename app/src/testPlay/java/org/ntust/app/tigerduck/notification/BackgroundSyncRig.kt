package org.ntust.app.tigerduck.notification

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import org.ntust.app.tigerduck.auth.AccountWorld
import org.ntust.app.tigerduck.auth.PersistedStoredPasswordLatch
import org.ntust.app.tigerduck.data.preferences.UiLanguageMonitor
import org.ntust.app.tigerduck.debug.DemoModeInterceptor
import org.ntust.app.tigerduck.liveactivity.LiveActivityBoundaryScheduler
import org.ntust.app.tigerduck.liveactivity.LiveActivityManager
import org.ntust.app.tigerduck.liveactivity.LiveActivityNotifier
import org.ntust.app.tigerduck.liveactivity.LiveActivityPreferences
import org.ntust.app.tigerduck.network.MoodleService
import org.ntust.app.tigerduck.network.MoodleTokenPairStore
import org.ntust.app.tigerduck.network.MoodleTokenService
import org.ntust.app.tigerduck.network.NtustSessionManager
import org.ntust.app.tigerduck.network.moodle.MoodleWebservice
import org.ntust.app.tigerduck.push.DepartingDeviceUnregister
import org.ntust.app.tigerduck.push.FcmBootstrap
import org.ntust.app.tigerduck.push.NotificationSettingsSync
import org.ntust.app.tigerduck.push.OwedUnregister
import org.ntust.app.tigerduck.push.PushIdentity
import org.ntust.app.tigerduck.push.PushRegistrationService
import org.ntust.app.tigerduck.push.SettingsDocumentApiClient
import org.ntust.app.tigerduck.ui.screen.home.HomeSyncRig
import org.ntust.app.tigerduck.ui.screen.home.idleTermSync
import java.io.IOException

/**
 * The notification settings sync over [backend], so what the worker's settings pull asks reaches
 * the test's backend, as the other requests do.
 */
internal fun settingsSyncAt(context: Context, w: AccountWorld, backend: OkHttpClient, idleScope: CoroutineScope) =
    NotificationSettingsSync(
        SettingsDocumentApiClient(backend, w.appPrefs, w.tokens), LiveActivityPreferences(context), w.appPrefs, w.tokens,
        w.credentials, AssignmentNotificationScheduler(context), w.dataCache, idleScope,
    )

/**
 * The background worker as Hilt builds it, over [w]'s stores and the full sync of [rig], whose
 * queues Home's sync sends from too; [settings] is its notification settings sync. The
 * school and Moodle are never asked: a test leaves the courses and the assignments not due.
 * What would run on the application scope goes to [idleScope], which never runs it.
 */
internal fun backgroundSyncWorker(
    context: Context,
    w: AccountWorld,
    rig: HomeSyncRig,
    settings: NotificationSettingsSync,
    idleScope: CoroutineScope,
): BackgroundSyncWorker {
    val refusing = OkHttpClient.Builder().addInterceptor { throw IOException("no network in this test") }.build()
    val sessionManager = NtustSessionManager(w.appPrefs, DemoModeInterceptor(w.fixtures), session = w.sessions.school)
    val latch = PersistedStoredPasswordLatch(context, w.credentials)
    val moodleTokens = MoodleTokenService(
        clientFor = { refusing },
        tokenStore = object : MoodleTokenPairStore {
            override var token: String? = null
            override var privateToken: String? = null
            override val studentId: String? = null
            override val password: String? = null
            override fun storeIfSignedIn(studentId: String, token: String, privateToken: String?) = false
        },
        passwordLatch = latch,
        scope = idleScope,
        now = System::currentTimeMillis,
    )
    val moodle = MoodleService(
        sessionManager, moodleTokens, w.courseService, w.dataCache, w.appPrefs, MoodleWebservice(sessionManager, moodleTokens),
        w.sessions, idleScope,
    )
    val liveUpdate = LiveActivityPreferences(context)
    val liveUpdates = LiveActivityManager(
        preferences = liveUpdate,
        notifier = LiveActivityNotifier(context, liveUpdate, w.appPrefs, NotificationChannelRegistrar(context, w.appPrefs)),
        dataCache = w.dataCache,
        isSignedIn = { w.auth.authState.value },
        appPrefs = w.appPrefs,
        classPreparingScheduler = ClassPreparingNotificationScheduler(context),
        academicCalendar = w.academicCalendar,
        boundaryScheduler = LiveActivityBoundaryScheduler(context),
        appScope = idleScope,
        session = w.sessions.school,
    )
    val registration = PushRegistrationService(
        context, PushIdentity(w.appPrefs, context), w.pushApi, w.tokens, w.appPrefs, UiLanguageMonitor(w.appPrefs), idleScope,
        DepartingDeviceUnregister(OwedUnregister(w.credentials), w.pushApi, w.tokens, w.appPrefs),
    )
    val factory = object : WorkerFactory() {
        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
            BackgroundSyncWorker(
                appContext, workerParameters,
                authService = w.auth,
                moodleService = moodle,
                courseService = w.courseService,
                semesterCatalog = w.semesterCatalog,
                dataCache = w.dataCache,
                notificationScheduler = AssignmentNotificationScheduler(context),
                liveActivityManager = liveUpdates,
                prefs = w.appPrefs,
                widgetUpdater = w.widgetUpdater,
                pushApiClient = w.pushApi,
                authTokenManager = w.tokens,
                fcmBootstrap = FcmBootstrap(context, registration, idleScope),
                notificationSettingsSync = settings,
                storedPasswordLatch = latch,
                academicCalendar = w.academicCalendar,
                termCourseSync = idleTermSync(w),
                synchronizer = rig.synchronizer,
            )
    }
    // A periodic run's first attempt: a retry would fetch the school's data whatever is due.
    return TestListenableWorkerBuilder<BackgroundSyncWorker>(context).setRunAttemptCount(0).setWorkerFactory(factory).build()
}
