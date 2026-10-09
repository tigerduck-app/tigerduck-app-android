package org.ntust.app.tigerduck.notification

import androidx.annotation.DrawableRes
import org.ntust.app.tigerduck.R

/**
 * The small icon every notification posts with: the duck-yellow brand icon,
 * its white twin where the yellow would end up in the status bar
 * ([DeviceSkin.statusBarKeepsColouredSmallIcon]), the yellow one wrapped so
 * an island that whitens vectors leaves it alone
 * ([DeviceSkin.islandWhitensVectorSmallIcon]), or the yellow one rendered
 * to a bitmap for an island that greys anything else
 * ([DeviceSkin.islandGreysNonBitmapSmallIcon]). Every `setSmallIcon` call
 * goes through this, so no notification can post the wrong one.
 */
@get:DrawableRes
val DeviceSkin.notificationSmallIcon: Int
    get() = when {
        statusBarKeepsColouredSmallIcon -> R.drawable.ic_notification_white
        islandWhitensVectorSmallIcon -> R.drawable.ic_notification_wrapped
        islandGreysNonBitmapSmallIcon -> R.drawable.ic_notification_bitmap
        else -> R.drawable.ic_notification
    }
