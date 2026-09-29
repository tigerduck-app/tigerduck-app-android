package org.ntust.app.tigerduck.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager

object NotificationChannels {
    const val ASSIGNMENT_DUE = "assignment_due"
    const val BULLETINS = "bulletins"
    /** High-importance bulletin channel: heads-up banner + default sound. */
    const val BULLETINS_SOUND = "bulletins_sound"
    /** Default-importance bulletin channel: shows banner but silent. */
    const val BULLETINS_SILENT = "bulletins_silent"
    /** Account and sync failures the user has to act on. Server-composed copy. */
    const val SYSTEM = "system"
    /** New mail in the school inbox. Default importance: a banner, not an alarm. */
    const val SCHOOL_MAIL = "school_mail"
    /** The alarm-driven "即將上課" banner a few minutes before each class. */
    const val CLASS_PREPARING = "class_preparing"
    /** The single ongoing Live Update; see LiveActivityNotifier. */
    const val LIVE_ACTIVITY = "live_activity_v3"

    /**
     * Earlier Live Update channels. Lock-screen visibility and importance are
     * frozen once a channel exists, so new defaults needed a new id; the old
     * ones are dropped rather than left behind as dead entries in Settings.
     */
    private val LEGACY_LIVE_ACTIVITY = listOf("live_activity", "live_activity_v2")

    /**
     * Create every channel the app posts to, named in [language].
     *
     * The system shows whatever name a channel was last created with and never
     * re-resolves it, so this runs on every launch and again whenever the app
     * language changes — re-creating an existing id is allowed and updates its
     * name and description, and leaves importance, sound and anything else the
     * user has changed alone. A channel created anywhere else, only when it was
     * first posted to, keeps the language of that moment until it is next
     * posted to; that is how two of them stayed in English under a Chinese UI.
     *
     * [language] is resolved explicitly rather than read from [context]'s
     * resources: below API 33 the per-app locale never reaches an application
     * context, and on a cold launch `setApplicationLocales` has not taken effect
     * yet when this first runs.
     */
    fun registerAll(context: Context, language: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val ctx = AppLanguageManager.localizedContext(context, language)
        for (old in LEGACY_LIVE_ACTIVITY) {
            if (manager.getNotificationChannel(old) != null) {
                manager.deleteNotificationChannel(old)
            }
        }
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    ASSIGNMENT_DUE,
                    ctx.getString(R.string.notification_assignment_due_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description =
                        ctx.getString(R.string.notification_assignment_due_channel_description)
                },
                NotificationChannel(
                    BULLETINS,
                    ctx.getString(R.string.notification_bulletin_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = ctx.getString(R.string.notification_bulletin_channel_description)
                },
                // High-importance: heads-up banner + default sound. Used when the
                // operator picks `force_ring=true` on a custom push.
                NotificationChannel(
                    BULLETINS_SOUND,
                    ctx.getString(R.string.notification_bulletin_sound_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description =
                        ctx.getString(R.string.notification_bulletin_sound_channel_description)
                },
                // Default-importance silent: banner shows but no sound or vibration.
                // Used when the operator picks `force_ring=false`.
                NotificationChannel(
                    BULLETINS_SILENT,
                    ctx.getString(R.string.notification_bulletin_silent_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description =
                        ctx.getString(R.string.notification_bulletin_silent_channel_description)
                    setSound(null, null)
                    enableVibration(false)
                },
                // Account and sync failures the user has to act on — same importance
                // as a force_ring bulletin, because a silently dead sync is worse
                // than an interruption.
                NotificationChannel(
                    SYSTEM,
                    ctx.getString(R.string.notification_system_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = ctx.getString(R.string.notification_system_channel_description)
                },
                // New school mail. Default importance: a banner without an alarm-style
                // interruption — the checks run every few minutes, never "instantly".
                NotificationChannel(
                    SCHOOL_MAIL,
                    ctx.getString(R.string.notification_school_mail_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description =
                        ctx.getString(R.string.notification_school_mail_channel_description)
                },
                NotificationChannel(
                    CLASS_PREPARING,
                    ctx.getString(R.string.notification_class_preparing_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description =
                        ctx.getString(R.string.notification_class_preparing_channel_description)
                    setShowBadge(false)
                },
                NotificationChannel(
                    LIVE_ACTIVITY,
                    ctx.getString(R.string.live_activity_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = ctx.getString(R.string.live_activity_channel_description)
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        )
    }
}
