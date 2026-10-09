package org.ntust.app.tigerduck.notification

import android.content.Context
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import org.ntust.app.tigerduck.R

/** The last bitmap drawn, keyed by its size; see [notificationLargeIcon]. */
@Volatile
private var cachedLargeIcon: Pair<Int, Bitmap>? = null

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
 * Drawn once and reused: the icon cannot change while the app runs, and a
 * notification only reads the bitmap. A new size, after the display size
 * changes, draws it again.
 */
fun Context.notificationLargeIcon(): Bitmap? {
    val size = resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width)
    cachedLargeIcon?.let { (cachedSize, bitmap) -> if (cachedSize == size) return bitmap }
    return ContextCompat.getDrawable(this, R.mipmap.ic_launcher)?.toBitmap(size, size)
        ?.also { cachedLargeIcon = size to it }
}
