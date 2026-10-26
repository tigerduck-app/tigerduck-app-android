package org.ntust.app.tigerduck.ui.screen.coursemoodle

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The crop of a new profile picture (#446, as the iPhone's tigerduck-app#802): the picture covers
 * the circle at every zoom and position, and what the circle shows goes up as a 512 px square JPEG.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PictureCropTest {

    private val wide = Size(1000f, 500f)

    @Test
    fun `at first the circle shows the picture's middle, its shorter side across`() {
        assertEquals(Rect(250f, 0f, 750f, 500f), PictureCrop.source(wide, diameter = 250f, zoom = 1f, offset = Offset.Zero))
    }

    @Test
    fun `zooming in shows less of the picture around the same middle`() {
        assertEquals(Rect(375f, 125f, 625f, 375f), PictureCrop.source(wide, diameter = 250f, zoom = 2f, offset = Offset.Zero))
    }

    @Test
    fun `a drag stops where the picture's edge meets the circle`() {
        val held = PictureCrop.clamp(wide, diameter = 250f, zoom = 1f, offset = Offset(1000f, 1000f))

        assertEquals(Offset(125f, 0f), held)
        assertEquals(Rect(0f, 0f, 500f, 500f), PictureCrop.source(wide, diameter = 250f, zoom = 1f, offset = held))
    }

    @Test
    fun `a circle that shrinks under a drag held at the edge still shows only the photo`() {
        val held = PictureCrop.clamp(wide, diameter = 250f, zoom = 1f, offset = Offset(1000f, 0f))

        // The window shrinks, as on a rotation, and the circle with it.
        val crop = PictureCrop.crop(wide, diameter = 200f, zoom = 1f, offset = held)

        assertTrue("$crop", crop.left >= 0f && crop.top >= 0f && crop.right <= 1000f && crop.bottom <= 500f)
    }

    @Test
    fun `a picked photo is decoded no larger than the crop needs, however long it is`() {
        // A phone photo: halved, still twice the upload across its shorter side.
        assertEquals(2, PictureCrop.decodeSampleSize(4000, 3000))
        // A long screenshot: its long side bounded, though the short one ends up small.
        val sample = PictureCrop.decodeSampleSize(1080, 60_000)
        assertTrue("60000 / $sample", 60_000 / sample <= PictureCrop.MAX_DECODED_SIDE)
        assertEquals(1, PictureCrop.decodeSampleSize(300, 200))
    }

    @Test
    fun `what the circle shows goes up as a 512 px square JPEG`() {
        // Red on the left half, blue on the right; the crop takes the right half.
        val picture = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        Canvas(picture).apply {
            drawColor(Color.RED)
            drawRect(100f, 0f, 200f, 100f, Paint().apply { color = Color.BLUE })
        }

        val jpeg = PictureCrop.jpeg(picture, Rect(100f, 0f, 200f, 100f))

        assertTrue(jpeg.size > 2 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte())
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        assertEquals(PictureCrop.OUTPUT_PX, decoded.width)
        assertEquals(PictureCrop.OUTPUT_PX, decoded.height)
        val middle = decoded.getPixel(256, 256)
        assertTrue("blue in the middle, was ${Integer.toHexString(middle)}", Color.blue(middle) > 200 && Color.red(middle) < 60)
    }
}
