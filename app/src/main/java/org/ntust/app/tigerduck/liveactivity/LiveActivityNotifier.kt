package org.ntust.app.tigerduck.liveactivity

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ntust.app.tigerduck.MainActivity
import org.ntust.app.tigerduck.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Renders / updates / ends the single "Live Update" ongoing notification used
 * as the Android analogue of the iOS Dynamic Island live activity.
 *
 * On Android 16+ this notification surfaces in the status bar as a
 * "Live Update" chip; on earlier versions it is shown as a persistent
 * ongoing notification with a chronometer.
 */
@Singleton
class LiveActivityNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val preferences: LiveActivityPreferences,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    // Tracks the scenario the user is currently looking at, so we can tell a
    // same-scenario chronometer tick (must stay silent) apart from an actual
    // transition into a new scenario (may alert, subject to per-scenario pref).
    private var lastScenario: LiveActivityScenario? = null

    init {
        ensureChannel()
    }

    fun apply(snapshot: LiveActivitySnapshot?) {
        if (snapshot == null) {
            manager.cancel(NOTIFICATION_ID)
            lastScenario = null
            return
        }
        if (!hasPostPermission()) return

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val visibility = if (preferences.showOnLockScreen) {
            NotificationCompat.VISIBILITY_PUBLIC
        } else {
            NotificationCompat.VISIBILITY_PRIVATE
        }

        val scenarioChanged = lastScenario != snapshot.scenario
        val soundWanted = scenarioChanged && wantsSoundFor(snapshot.scenario)

        // For sound to play on a scenario transition we need to (a) drop the
        // prior notification so the system re-arms alert-once, and (b) not
        // call setSilent(true). Same-scenario updates skip the cancel and stay
        // silent regardless of pref — the chronometer tick shouldn't chime.
        if (scenarioChanged) manager.cancel(NOTIFICATION_ID)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(snapshot.title)
            .setContentText(statusLine(snapshot))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(0xFF000000.toInt() or (snapshot.accentHex and 0xFFFFFF))
            .setColorized(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(if (soundWanted) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_LOW)
            .setVisibility(visibility)
            .apply { if (!soundWanted) setSilent(true) }

        val target = snapshot.countdownTarget?.time ?: 0L
        if (target > System.currentTimeMillis()) {
            builder.setUsesChronometer(true)
            builder.setChronometerCountDown(true)
            builder.setWhen(target)
        } else {
            builder.setShowWhen(false)
        }

        val expandedLines = listOfNotNull(
            snapshot.locationText?.let { "📍 $it" },
            snapshot.instructor?.let { "👤 $it" },
            snapshot.subtitle.takeIf { it.isNotBlank() }?.let { "🕒 $it" },
        )
        if (expandedLines.isNotEmpty()) {
            builder.setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(expandedLines.joinToString("\n"))
            )
        }

        manager.notify(NOTIFICATION_ID, builder.build())
        lastScenario = snapshot.scenario
    }

    fun cancel() {
        manager.cancel(NOTIFICATION_ID)
        lastScenario = null
    }

    private fun wantsSoundFor(scenario: LiveActivityScenario): Boolean = when (scenario) {
        LiveActivityScenario.IN_CLASS -> preferences.soundInClass
        LiveActivityScenario.CLASS_PREPARING -> preferences.soundClassPreparing
        LiveActivityScenario.ASSIGNMENT_URGENT -> preferences.soundAssignment
    }

    private fun hasPostPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun statusLine(snapshot: LiveActivitySnapshot): String {
        val prefix = when (snapshot.scenario) {
            LiveActivityScenario.IN_CLASS -> "上課中"
            LiveActivityScenario.CLASS_PREPARING -> "即將上課"
            LiveActivityScenario.ASSIGNMENT_URGENT -> "作業即將到期"
        }
        return if (snapshot.subtitle.isNotBlank()) "$prefix · ${snapshot.subtitle}" else prefix
    }

    private fun ensureChannel() {
        // Drop legacy channels so the new defaults (lockscreen visibility +
        // importance) are actually applied; both attributes are frozen after
        // channel creation on API 26+.
        for (old in LEGACY_CHANNEL_IDS) {
            if (manager.getNotificationChannel(old) != null) {
                manager.deleteNotificationChannel(old)
            }
        }
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "即時動態",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "顯示當前課程、即將上課與作業倒數；可在 App 設定中調整各情境是否發出提示音"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "live_activity_v3"
        private val LEGACY_CHANNEL_IDS = listOf("live_activity", "live_activity_v2")
        const val NOTIFICATION_ID = 42_001
    }
}
