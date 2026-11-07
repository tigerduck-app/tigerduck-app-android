package org.ntust.app.tigerduck.liveactivity

import android.app.Notification
import androidx.core.app.NotificationCompat
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.shared.AccentGradient

/**
 * What the Live Update draws of the accent's gradient. A notification takes one color, and keeps
 * the brand's (see [LiveActivityNotifier]); the one place Android takes several is a progress
 * bar's segments ([NotificationCompat.ProgressStyle]), which then run through the stops in order.
 */
internal object LiveUpdateSegments {

    /** The gradient the Live Update is handed: only while Settings' Live Updates switch is on, as on the iPhone. */
    fun accentOf(prefs: AppPreferences): AccentGradient? = prefs.deviceAccents.liveActivities

    /**
     * The progress bar's segments, [max] long in all: one, in the notification's own color, as
     * before; with [LiveActivitySnapshot.accentGradient] one for each stop, in its color, the
     * length shared out evenly and the first taking what is left over.
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
