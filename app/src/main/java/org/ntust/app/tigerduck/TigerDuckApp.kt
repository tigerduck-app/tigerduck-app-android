package org.ntust.app.tigerduck

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.notification.NotificationChannels
import org.ntust.app.tigerduck.push.FcmBootstrap
import org.ntust.app.tigerduck.wear.WearScheduleBridge
import javax.inject.Inject
import android.content.res.Configuration as ResConfiguration

@HiltAndroidApp
class TigerDuckApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory
    @Inject
    lateinit var appPreferences: AppPreferences
    @Inject
    lateinit var fcmBootstrap: FcmBootstrap
    @Inject
    lateinit var dataCache: DataCache
    @Inject
    lateinit var authService: AuthService
    @Inject
    lateinit var wearBridge: WearScheduleBridge

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        AppLanguageManager.apply(appPreferences.appLanguage)
        createNotificationChannels()
        fcmBootstrap.start()
        warnIfPinsNearExpiry()

        // Wear OS bridge — publish to paired watch on schedule/auth/accent changes.
        // On fdroid the bridge is a no-op stub; these calls are still safe.
        dataCache.setOnCoursesSavedListener {
            appScope.launch { wearBridge.publish() }
        }
        appPreferences.onAccentColorChanged = {
            appScope.launch { wearBridge.publish() }
        }
        appScope.launch {
            authService.authState.collect { wearBridge.publish() }
        }
        appScope.launch { wearBridge.publish() }  // safety-net publish at launch
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

    /**
     * Channel names are cached by Android the first time
     * `createNotificationChannel` is called for an id, so we MUST emit them
     * in the user's chosen language. `setApplicationLocales` above is async
     * and doesn't reach `getString()` in this same onCreate, so explicitly
     * resolve the user's locale and look up strings against it.
     */
    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val ctx = localizedContext(appPreferences.appLanguage)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NotificationChannels.ASSIGNMENT_DUE,
                ctx.getString(R.string.notification_assignment_due_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description =
                    ctx.getString(R.string.notification_assignment_due_channel_description)
            }
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NotificationChannels.BULLETINS,
                ctx.getString(R.string.notification_bulletin_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = ctx.getString(R.string.notification_bulletin_channel_description)
            }
        )
    }

    @android.annotation.SuppressLint("AppBundleLocaleChanges")
    private fun localizedContext(language: String): Context {
        // Narrow, one-shot use for notification-channel name lookup before
        // AppCompatDelegate.setApplicationLocales propagates. Not dynamic UI
        // locale switching, so the AppBundleLocaleChanges lint doesn't apply.
        val locale = AppLanguageManager.resolveExplicitLocale(language) ?: return this
        val config = ResConfiguration(resources.configuration)
        config.setLocale(locale)
        return createConfigurationContext(config)
    }

}
