package org.ntust.app.tigerduck

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
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
    }

    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                "assignment_due",
                getString(R.string.notification_assignment_due_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.notification_assignment_due_channel_description)
            }
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(
                "bulletins",
                getString(R.string.notification_bulletin_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.notification_bulletin_channel_description)
            }
        )
    }

}
