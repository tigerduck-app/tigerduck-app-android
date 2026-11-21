package org.ntust.app.tigerduck.notification

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import org.ntust.app.tigerduck.appicon.LauncherIcons

/** The last bitmap drawn, with the configuration and the icon it was drawn in; see [notificationLargeIcon]. */
private class DrawnLargeIcon(val config: Configuration, val icon: Int, val bitmap: Bitmap)

@Volatile
private var cachedLargeIcon: DrawnLargeIcon? = null

/**
 * The launcher icon as a bitmap at the notification large icon's size, for
 * `setLargeIcon`: the one the launcher shows, an icon picked in Settings
 * ([LauncherIcons]) too.
 *
 * `BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)` is the
 * obvious call and returns null from Android 8 on: there the mipmap resolves
 * to the adaptive icon's XML, which BitmapFactory cannot read. Drawing the
 * drawable instead works for both the adaptive icon and the PNGs older
 * releases pick, and the adaptive icon draws itself clipped to the device's
 * launcher shape.
 *
 * Drawn once and reused while the icon and the configuration hold, since a
 * notification only reads the bitmap. What else can change it while the app
 * runs changes the configuration too: the display size, and the icon shape,
 * which the system applies as an overlay and so as a new assets sequence.
 */
fun Context.notificationLargeIcon(): Bitmap? {
    val config = resources.configuration
    val icon = LauncherIcons(this).shown().icon
    cachedLargeIcon?.let { if (it.config == config && it.icon == icon) return it.bitmap }
    val size = resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width)
    return ContextCompat.getDrawable(this, icon)?.toBitmap(size, size)
        ?.also { cachedLargeIcon = DrawnLargeIcon(Configuration(config), icon, it) }
}
