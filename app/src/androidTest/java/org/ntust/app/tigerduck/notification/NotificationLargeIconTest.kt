package org.ntust.app.tigerduck.notification

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R

/**
 * Guards the large icon pushes post with.
 *
 * They used to pass `BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)`,
 * which returns null from Android 8 on, so on every release the app runs on:
 * the mipmap resolves to the adaptive icon's XML there, and BitmapFactory
 * decodes image files only. A null large
 * icon is legal, so every push simply posted without one and nothing failed.
 */
@RunWith(AndroidJUnit4::class)
class NotificationLargeIconTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun decodingTheLauncherMipmapDirectlyYieldsNothing() {
        assertNull(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
    }

    @Test
    fun theLargeIconIsTheLauncherIconAtTheSlotsSize() {
        val icon = context.notificationLargeIcon()
        assertNotNull(icon)
        val size = context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width)
        assertEquals(size, icon!!.width)
        assertEquals(size, icon.height)
        // Not a blank canvas: the middle of the launcher icon is the mascot.
        assertTrue(Color.alpha(icon.getPixel(size / 2, size / 2)) == 255)
    }

    @Test
    fun theLargeIconIsDrawnOnceAndReused() {
        assertSame(context.notificationLargeIcon(), context.notificationLargeIcon())
    }
}
