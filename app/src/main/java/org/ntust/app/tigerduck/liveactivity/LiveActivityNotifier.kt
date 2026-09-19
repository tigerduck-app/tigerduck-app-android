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
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.MainActivity
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.notification.ClassPreparingNotificationReceiver
import org.ntust.app.tigerduck.notification.DeviceSkin
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
 *
 * None of that is gated on a capability check here, deliberately.
 * `canPostPromotedNotifications()` is wrong in both directions on shipping
 * hardware — see [org.ntust.app.tigerduck.notification.DeviceSkin] — and
 * posting when it would have said no costs nothing, because an unpromoted
 * Live Update is just an ordinary ongoing notification. Gating on it would
 * silently remove the chip on OEMs that render it fine. The capability is a
 * diagnostic for the settings screen, never a precondition for posting.
 *
 * One vendor does need code: see [samsungNowBarExtras].
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

    /** Fixed for the life of the process; see [samsungNowBarExtras]. */
    private val deviceSkin = DeviceSkin.current()

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
            // Nothing is posted and nothing throws, so "I am in class and no
            // notification appeared" looks like a resolver bug rather than a
            // missing permission. The red dot on the settings permission row
            // is the only other place this surfaces, and it is easy to miss
            // while testing. Reaching here means we genuinely had something
            // to show, so this cannot spam a working install.
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "dropping ${snapshot.scenario}: POST_NOTIFICATIONS is denied")
            }
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
            // Brand tint, not snapshot.accentHex: every monochrome small icon
            // in the app now tints duck yellow, so the shade badge and the
            // Android 16 promoted-ongoing chip stay consistent with the
            // assignment / bulletin notifications instead of shifting colour
            // per course. The per-course accent still drives the watch, which
            // reads it from prefs via WearScheduleBridge, not from here.
            .setColor(ContextCompat.getColor(context, R.color.duck_yellow))
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

        samsungNowBarExtras()?.let { builder.addExtras(it) }

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

    /**
     * The one vendor-specific thing this class does.
     *
     * Samsung's Now Bar (即時通知) runs a pipeline that predates AOSP Live
     * Updates and ignores a plain promoted notification, so on One UI the chip
     * stays absent however correct the AOSP side of the builder is. A single
     * extra opens it. From decompiled One UI 8.5 SystemUI:
     *
     * ```
     * NotificationEntry.isPromotedState() =
     *     (isDevelopRonTestAllowed() || isAutomation()
     *      || !AllowedOngoingActivityListManager.isAllowListUsing) && mIsRon
     * NotificationEntry.isAutomation() =
     *     extras.getBoolean("android.ongoingActivityNoti.automation")
     * mIsRon = notification.hasPromotableCharacteristics()
     * ```
     *
     * So `automation` short-circuits Samsung's allowlist, provided the
     * notification already passes AOSP's promotable checks — which this one
     * does. Verified on One UI 8.5 (A26, S26 Ultra) and 9.0 (A07). It is inert
     * on 8.0 and below, where the platform promotes nothing at all, and inert
     * on a package in Samsung's `blockedRONAppList`, which is checked before
     * any of this.
     *
     * Deliberately just the one key. `android.ongoingActivityNoti.style` >= 1
     * sends `isOngoingActivity()` down Samsung's private-card lane, which sets
     * `mIsRon = false` and so cancels this bypass: the two most widely
     * documented extras undo each other, and the decorative ones (chipIcon,
     * chipBgColor, primaryInfo …) are useless without the style that breaks it.
     *
     * Scoped to Samsung because no other OEM reads these keys, and a
     * notification carrying vendor extras it does not need is one more thing
     * for the next OEM's parser to disagree with.
     *
     * Worth knowing when testing by hand: the entry sits in the controller's
     * Pending list while TigerDuck itself is in the foreground, and only moves
     * to Showing once the app is backgrounded with the screen on and unlocked.
     * That is the normal state for a class countdown, but it makes the chip
     * look broken if you watch for it without leaving the app.
     */
    private fun samsungNowBarExtras(): Bundle? {
        if (!deviceSkin.isSamsung) return null
        return Bundle(2).apply {
            putBoolean(SAMSUNG_AUTOMATION, true)
            putString(SAMSUNG_AUTOMATION_PACKAGE, context.packageName)
        }
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
        private const val TAG = "LiveActivity"
        const val CHANNEL_ID = "live_activity_v3"
        /** Denominator for [NotificationCompat.Builder.setProgress]; percent reads well enough. */
        private const val PROGRESS_MAX = 100
        private val LEGACY_CHANNEL_IDS = listOf("live_activity", "live_activity_v2")

        /** Samsung's undocumented Now Bar allowlist bypass; see [samsungNowBarExtras]. */
        private const val SAMSUNG_AUTOMATION = "android.ongoingActivityNoti.automation"
        private const val SAMSUNG_AUTOMATION_PACKAGE =
            "android.ongoingActivityNoti.automationPackage"
        const val NOTIFICATION_ID = 42_001
    }
}
