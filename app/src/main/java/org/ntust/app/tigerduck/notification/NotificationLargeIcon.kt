package org.ntust.app.tigerduck.notification

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import org.ntust.app.tigerduck.R

/** The last bitmap drawn, and the configuration it was drawn in; see [notificationLargeIcon]. */
@Volatile
private var cachedLargeIcon: Pair<Configuration, Bitmap>? = null

/**
 * The launcher icon as a bitmap at the notification large icon's size, for
 * `setLargeIcon`.
 *
 * `BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)` is the
 * obvious call and returns null from Android 8 on: there the mipmap resolves
 * to the adaptive icon's XML, which BitmapFactory cannot read. Drawing the
 * drawable instead works for both the adaptive icon and the PNGs older
 * releases pick, and the adaptive icon draws itself clipped to the device's
 * launcher shape.
 *
 * Drawn once and reused while the configuration holds, since a notification
 * only reads the bitmap. What can change it while the app runs changes the
 * configuration too: the display size, and the icon shape, which the system
 * applies as an overlay and so as a new assets sequence.
 */
fun Context.notificationLargeIcon(): Bitmap? {
    val config = resources.configuration
    cachedLargeIcon?.let { (drawnIn, bitmap) -> if (drawnIn == config) return bitmap }
    val size = resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width)
    return ContextCompat.getDrawable(this, R.mipmap.ic_launcher)?.toBitmap(size, size)
        ?.also { cachedLargeIcon = Configuration(config) to it }
}
