package org.ntust.app.tigerduck

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.DataMigration
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.UiLanguageMonitor
import org.ntust.app.tigerduck.ui.component.ServerStatusTracker
import org.ntust.app.tigerduck.debug.DebugClockController
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.liveactivity.LiveActivityManager
import org.ntust.app.tigerduck.notification.NotificationChannelRegistrar
import org.ntust.app.tigerduck.analytics.AnalyticsLogger
import org.ntust.app.tigerduck.push.FcmBootstrap
import org.ntust.app.tigerduck.wear.WearScheduleBridge
import javax.inject.Inject

@HiltAndroidApp
class TigerDuckApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var appPreferences: AppPreferences

    @Inject
    lateinit var fcmBootstrap: FcmBootstrap

    @Inject
    lateinit var dataMigration: DataMigration

    @Inject
    lateinit var dataCache: DataCache

    @Inject
    lateinit var authService: AuthService

    @Inject
    lateinit var wearBridge: WearScheduleBridge

    @Inject
    lateinit var analyticsLogger: AnalyticsLogger

    @Inject
    lateinit var debugClockController: DebugClockController

    @Inject
    lateinit var liveActivityManager: LiveActivityManager

    @Inject
    lateinit var uiLanguage: UiLanguageMonitor

    @Inject
    lateinit var notificationChannels: NotificationChannelRegistrar

    // The DI singleton, not a private scope: it carries a logging
    // CoroutineExceptionHandler (see CoroutineModule) so a failure in the
    // safety-net publishes below can't crash the process pre-first-frame.
    // DataCache does its own withContext(Dispatchers.IO) for file I/O, so
    // the scope's Default dispatcher is fine here.
    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // First, before anything can read or write DataCache. BootReceiver and
        // BackgroundSyncWorker both run without an Activity, so leaving this
        // to AppState let WorkManager's persisted periodic sync fire on the
        // upgrade launch and rebuild caches that a pending migration step then
        // deleted. AppState re-reads the cached outcome for the reset prompt.
        dataMigration.run()
        analyticsLogger.setEnabled(appPreferences.analyticsEnabled)
        // Seeded here so a cold launch with cloud sync off does not show the
        // status dot's backend row as "unknown" until the first sync guard
        // runs — which, sync being off, may be never.
        ServerStatusTracker.setCloudSyncEnabled(appPreferences.cloudSyncEnabled)
        analyticsLogger.setUserProperty("app_version", BuildConfig.VERSION_NAME)
        debugClockController.bootstrap()
        AppLanguageManager.apply(appPreferences.appLanguage)
        notificationChannels.register()
        uiLanguage.onLocales(resources.configuration.locales.toLanguageTags())
        fcmBootstrap.start()
        warnIfPinsNearExpiry()

        // Wear OS bridge — publish to paired watch on schedule/auth/accent changes.
        // On fdroid the bridge is a no-op stub; these calls are still safe.
        dataCache.setOnCoursesSavedListener {
            appScope.launch { wearBridge.publish() }
        }
        appScope.launch {
            appPreferences.accentColorChanged.collect { wearBridge.publish() }
        }
        // The dot hides itself while signed out, but the tracker is
        // process-wide: a status written before logout would still be here
        // for the next account to inherit, green from the moment the dot
        // comes back. Clearing on the auth signal rather than inside
        // AuthService.logout() keeps a UI singleton out of the auth layer.
        appScope.launch {
            authService.authState.collect { signedIn ->
                ServerStatusTracker.setSignedIn(signedIn)
                if (!signedIn) ServerStatusTracker.reset()
            }
        }
        appScope.launch {
            authService.authState.collect {
                wearBridge.publish()
                // Auth state flips when SSO + library login finishes (or
                // when logout clears creds), so this is also the right
                // moment to (re)mirror the library credentials onto the
                // paired watch.
                wearBridge.publishLibraryCredentials()
            }
        }
        // Mirror language changes to the watch so its UI follows the phone.
        appScope.launch {
            appPreferences.appLanguageChanged.collect { wearBridge.publish() }
        }
        // Switching language recreates the Activities but not the process, so
        // without this the channel names in system Settings would stay in the
        // old language until the next cold start. One collector, so renames
        // run one after another and each reads the language as it is when it
        // runs; conflated, because only the last of a burst matters.
        appScope.launch {
            uiLanguage.changes.conflate().collect { relocalizeNotifications() }
        }
        // Mirror the debug screen-capture override so flipping the toggle
        // takes effect on the paired watch's LibraryQR window without a
        // wear-app restart. No-op in release builds (the toggle row is
        // hidden and the pref can't change).
        appScope.launch {
            appPreferences.disableScreenCaptureProtectionChanged.collect {
                wearBridge.publish()
            }
        }
        appScope.launch { wearBridge.publish() }  // safety-net publish at launch
        appScope.launch { wearBridge.publishLibraryCredentials() }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // What a change of locales here stands for is in UiLanguageMonitor.
        uiLanguage.onLocales(newConfig.locales.toLanguageTags())
    }

    /**
     * Rename the notification channels and redraw the Live Update, the two
     * system-drawn surfaces that keep whatever language they were last given.
     * Quietly: a new language is no reason to chime, see
     * [org.ntust.app.tigerduck.liveactivity.LiveActivityNotifier.apply].
     */
    private fun relocalizeNotifications() {
        notificationChannels.register()
        liveActivityManager.refresh(quiet = true)
    }

    private fun warnIfPinsNearExpiry() {
        val daysUntilExpiry =
            (BuildConfig.PIN_EXPIRY_EPOCH - System.currentTimeMillis()) /
                    (24L * 60 * 60 * 1000)
        if (daysUntilExpiry in 0..30) {
            android.util.Log.w(
                "TigerDuckApp",
                "NTUST cert pins expire in $daysUntilExpiry day(s); rotate before lapse — " +
                        "post-expiry the platform falls back to system CA trust silently",
            )
        } else if (daysUntilExpiry < 0) {
            android.util.Log.e(
                "TigerDuckApp",
                "NTUST cert pins EXPIRED ${-daysUntilExpiry} day(s) ago — rotation overdue",
            )
        }
    }

}
