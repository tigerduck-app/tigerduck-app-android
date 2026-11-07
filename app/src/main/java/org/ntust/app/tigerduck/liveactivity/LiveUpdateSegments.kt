package org.ntust.app.tigerduck.liveactivity

import android.app.Notification
import androidx.core.app.NotificationCompat
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.notification.DeviceSkin
import org.ntust.app.tigerduck.shared.AccentGradient

/**
 * What the Live Update draws of the accent's gradient. A notification takes one color, and keeps
 * the brand's (see [LiveActivityNotifier]); the one place Android takes several is a progress
 * bar's segments ([NotificationCompat.ProgressStyle]), which then run through the stops in order.
 */
internal object LiveUpdateSegments {

    /**
     * Whether a Live Update on this phone can show the gradient at all: only where it is drawn with
     * ProgressStyle, ColorOS's Live Alerts card ([DeviceSkin.cardHidesTextBehindClock], the flag
     * [LiveActivityNotifier] picks that style by). Elsewhere it draws the legacy bar, which takes no
     * color of its own, and Settings offers no switch for it.
     */
    fun canShowGradient(skin: DeviceSkin = DeviceSkin.current()): Boolean = skin.cardHidesTextBehindClock

    /** The gradient the Live Update is handed: only while Settings' Live Updates switch is on, as on the iPhone. */
    fun accentOf(prefs: AppPreferences): AccentGradient? = prefs.deviceAccents.liveActivities

    /**
     * The progress bar's segments, [max] long in all: without [LiveActivitySnapshot.accentGradient]
     * the one segment the notifier always drew, in the notification's own color, the brand's; with
     * it one for each stop, in its color, the length shared out evenly and the first taking what is
     * left over.
     */
    fun of(snapshot: LiveActivitySnapshot, max: Int): List<NotificationCompat.ProgressStyle.Segment> {
        val stops = snapshot.accentGradient?.takeIf { it.isGradient }?.stops
            ?: return listOf(NotificationCompat.ProgressStyle.Segment(max).setColor(Notification.COLOR_DEFAULT))
        val each = max / stops.size
        return stops.mapIndexed { index, rgb ->
            val length = if (index == 0) max - each * (stops.size - 1) else each
            NotificationCompat.ProgressStyle.Segment(length).setColor(rgb or 0xFF000000.toInt())
        }
    }
}
