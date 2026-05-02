package org.ntust.app.tigerduck

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration as ResConfiguration
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.push.FcmBootstrap
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class TigerDuckApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var fcmBootstrap: FcmBootstrap

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
                "assignment_due",
                ctx.getString(R.string.notification_assignment_due_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = ctx.getString(R.string.notification_assignment_due_channel_description)
            }
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(
                "bulletins",
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
