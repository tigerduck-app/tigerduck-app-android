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
import org.ntust.app.tigerduck.notification.ClassPreparingNotificationReceiver
import org.ntust.app.tigerduck.shared.clock.AppClock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Renders / updates / ends the single "Live Update" ongoing notification used
 * as the Android analogue of the iOS Dynamic Island live activity.
 *
 * On Android 16 QPR1 and newer the system may additionally promote it to a
 * chip in the status bar. Promotion is not a switch we flip: the platform
 * runs `Notification.hasPromotableCharacteristics()`, and every one of its
 * conditions has to hold at once — `setRequestPromotedOngoing(true)`,
 * `setOngoing(true)`, a non-empty content title, a style it is willing to
 * promote (none, BigTextStyle, CallStyle, MetricStyle or ProgressStyle),
 * no group summary, no custom RemoteViews, and **not colorized**.
 *
 * That last one is worth naming because it cost us the feature: this class
 * used to call `setColorized(true)`, which disqualified the notification
 * silently. It was never doing anything visible either — the platform honours
 * colorized only for a foreground-service, media, or already-promoted
 * notification, and this is none of those — so it bought nothing and took
 * the chip away.
 *
 * The user must also grant POST_PROMOTED_NOTIFICATIONS separately; the Live
 * Activity settings screen surfaces that as its own permission row. Below
 * Android 16 QPR1 the platform gates the whole feature behind its internal
 * `ui_rich_ongoing` flag, so no chip appears whatever we send, and this
 * stays an ordinary ongoing notification with a countdown and a progress bar.
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
        if (!hasPostPermission()) {
            lastScenario = null
            return
        }

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
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setRequestPromotedOngoing(true)
            .setVisibility(visibility)
            .apply { if (!soundWanted) setSilent(true) }

        // Deliberately no setShortCriticalText: the chip picks its content in
        // priority order — short critical text, then a metric, then `when` —
        // and only the last of those ticks. Leaving it unset is what makes the
        // chip a live counting-down clock instead of a string frozen at
        // whatever the remaining time was when we last posted.
        val target = snapshot.countdownTarget?.time ?: 0L
        if (target > AppClock.nowMillis()) {
            builder.setUsesChronometer(true)
            builder.setChronometerCountDown(true)
            builder.setWhen(target)
        } else {
            builder.setShowWhen(false)
        }

        // The bar does not animate itself; it holds whatever fraction we last
        // posted. LiveActivityManager re-fires us periodically while a class
        // is running so it actually advances — see PROGRESS_TICK_MS there.
        snapshot.progress?.let { fraction ->
            val filled = (fraction * PROGRESS_MAX).roundToInt().coerceIn(0, PROGRESS_MAX)
            builder.setProgress(PROGRESS_MAX, filled, false)
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

        // Once the class is actually ongoing, the alarm-driven "即將上課" banner
        // is redundant. The receiver's setTimeoutAfter is a best-effort hint
        // and can lag past class start, leaving two side-by-side notifications
        // for the same class. Clear any stragglers explicitly on the
        // CLASS_PREPARING → IN_CLASS transition.
        if (scenarioChanged && snapshot.scenario == LiveActivityScenario.IN_CLASS) {
            cancelClassPreparingBanners()
        }

        lastScenario = snapshot.scenario
    }

    private fun cancelClassPreparingBanners() {
        manager.activeNotifications.forEach { sbn ->
            if (sbn.notification.channelId == ClassPreparingNotificationReceiver.CHANNEL_ID) {
                manager.cancel(sbn.tag, sbn.id)
            }
        }
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
            LiveActivityScenario.IN_CLASS -> context.getString(R.string.live_activity_status_in_class)
            LiveActivityScenario.CLASS_PREPARING ->
                context.getString(R.string.live_activity_status_class_preparing)

            LiveActivityScenario.ASSIGNMENT_URGENT ->
                context.getString(R.string.live_activity_status_assignment_urgent)
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
            context.getString(R.string.live_activity_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.live_activity_channel_description)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "live_activity_v3"
        /** Denominator for [NotificationCompat.Builder.setProgress]; percent reads well enough. */
        private const val PROGRESS_MAX = 100
        private val LEGACY_CHANNEL_IDS = listOf("live_activity", "live_activity_v2")
        const val NOTIFICATION_ID = 42_001
    }
}
