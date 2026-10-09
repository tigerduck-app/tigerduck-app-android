package org.ntust.app.tigerduck.notification

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.ntust.app.tigerduck.R

/**
 * A notification builder that already carries the brand: the small icon this
 * skin needs ([notificationSmallIcon]) and the duck-yellow accent. Every
 * notification starts here, so a new one cannot leave either off.
 *
 * [withLargeIcon] adds the launcher logo as the large icon, for the
 * notifications that come from outside the app: bulletins, popups and the
 * reauth notice. It is left off where the shade already draws that logo in
 * the small icon's place ([DeviceSkin.shadeShowsAppIcon]), where it would
 * show twice.
 */
fun Context.brandedNotification(
    channelId: String,
    withLargeIcon: Boolean = false,
    skin: DeviceSkin = DeviceSkin.current(),
): NotificationCompat.Builder =
    NotificationCompat.Builder(this, channelId)
        .setSmallIcon(skin.notificationSmallIcon)
        .setColor(ContextCompat.getColor(this, R.color.duck_yellow))
        .apply { if (withLargeIcon && !skin.shadeShowsAppIcon) setLargeIcon(notificationLargeIcon()) }
