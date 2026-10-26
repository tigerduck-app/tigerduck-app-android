package org.ntust.app.tigerduck.ui.screen.coursemoodle

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.core.strings.R
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A new profile picture's crop (#446, as the iPhone's tigerduck-app#802): the picture under a
 * circle, the shape the members list and a member's page show, at a zoom of 1 (its shorter side
 * across the circle) to [MAX_ZOOM], moved by an offset of its centre from the circle's. It covers
 * the circle wherever it is moved, and what the circle shows goes up as an [OUTPUT_PX] square JPEG.
 */
internal object PictureCrop {
    const val OUTPUT_PX = 512
    const val MAX_ZOOM = 5f
    private const val JPEG_QUALITY = 90

    /** The longest side a picked photo is decoded to, which bounds its memory whatever its shape. */
    const val MAX_DECODED_SIDE = 4096

    /**
     * How much to shrink a [width] by [height] photo as it is decoded: to no less than twice the
     * upload across its shorter side, and no more than [MAX_DECODED_SIDE] along its longer one.
     * A very long picture, such as a scrolling screenshot, ends up short across, never too large.
     */
    fun decodeSampleSize(width: Int, height: Int): Int {
        val forCrop = max(1, min(width, height) / (OUTPUT_PX * 2))
        val forMemory = (max(width, height) + MAX_DECODED_SIDE - 1) / MAX_DECODED_SIDE
        return max(forCrop, forMemory)
    }

    /** The scale at which [image] just covers a circle [diameter] across. */
    fun coverScale(image: Size, diameter: Float): Float = diameter / min(image.width, image.height)

    /** [offset] held where [image], at [zoom], still covers the circle. */
    fun clamp(image: Size, diameter: Float, zoom: Float, offset: Offset): Offset {
        val scale = coverScale(image, diameter) * zoom
        val maxX = max(0f, (image.width * scale - diameter) / 2)
        val maxY = max(0f, (image.height * scale - diameter) / 2)
        return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
    }

    /** The square of [image] the circle shows at [zoom] and [offset], in the image's pixels. */
    fun source(image: Size, diameter: Float, zoom: Float, offset: Offset): Rect {
        val scale = coverScale(image, diameter) * zoom
        val side = diameter / scale
        val left = image.width / 2 - (diameter / 2 + offset.x) / scale
        val top = image.height / 2 - (diameter / 2 + offset.y) / scale
        return Rect(left, top, left + side, top + side)
    }

    /**
     * The square the circle shows of [image] at [zoom] and [offset], with [offset] held to the
     * photo for the circle as it is now: one that fit a larger circle, before the window shrank,
     * would show past the photo's edge.
     */
    fun crop(image: Size, diameter: Float, zoom: Float, offset: Offset): Rect =
        source(image, diameter, zoom, clamp(image, diameter, zoom, offset))

    /**
     * [source] of [picture] as an [OUTPUT_PX] square JPEG, on white: a JPEG has no transparency,
     * which would otherwise come out black.
     */
    fun jpeg(picture: Bitmap, source: Rect): ByteArray {
        val square = Bitmap.createBitmap(OUTPUT_PX, OUTPUT_PX, Bitmap.Config.ARGB_8888)
        try {
            AndroidCanvas(square).apply {
                drawColor(android.graphics.Color.WHITE)
                drawBitmap(
                    picture,
                    android.graphics.Rect(
                        source.left.roundToInt(), source.top.roundToInt(),
                        source.right.roundToInt(), source.bottom.roundToInt(),
                    ),
                    android.graphics.Rect(0, 0, OUTPUT_PX, OUTPUT_PX),
                    Paint(Paint.FILTER_BITMAP_FLAG),
                )
            }
            return ByteArrayOutputStream().use { out ->
                square.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
        } finally {
            square.recycle()
        }
    }
}

/**
 * The picture at [uri], as the photo picker hands it, turned upright as its EXIF data says and
 * decoded no larger than the crop needs; null when it cannot be read.
 */
internal suspend fun decodePickedPicture(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
    try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            // Readable pixels for the crop's canvas, and no more than the crop needs.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(PictureCrop.decodeSampleSize(info.size.width, info.size.height))
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}

/**
 * The crop of [picture] before it goes up: a pinch zooms it and a drag moves it under the
 * circle; Done hands back the square the circle shows ([PictureCrop.source]). Without the
 * gestures, as with TalkBack, Done takes the picture's middle.
 */
@Composable
internal fun PictureCropDialog(picture: Bitmap, onCancel: () -> Unit, onDone: (source: Rect) -> Unit) {
    val image = remember(picture) { picture.asImageBitmap() }
    val size = Size(picture.width.toFloat(), picture.height.toFloat())
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val diameter = with(LocalDensity.current) { min(maxWidth.toPx(), maxHeight.toPx()) } * CIRCLE_SHARE
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(diameter) {
                            detectTransformGestures { _, pan, gestureZoom, _ ->
                                zoom = (zoom * gestureZoom).coerceIn(1f, PictureCrop.MAX_ZOOM)
                                offset = PictureCrop.clamp(size, diameter, zoom, offset + pan)
                            }
                        },
                ) {
                    val scale = PictureCrop.coverScale(size, diameter) * zoom
                    val drawn = Size(size.width * scale, size.height * scale)
                    // Held to the photo for the circle as it is now, as Done takes it.
                    val shown = PictureCrop.clamp(size, diameter, zoom, offset)
                    val topLeft = center + shown - Offset(drawn.width / 2, drawn.height / 2)
                    drawImage(
                        image,
                        dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
                        dstSize = IntSize(drawn.width.roundToInt(), drawn.height.roundToInt()),
                        filterQuality = FilterQuality.Medium,
                    )
                    // All but the circle dimmed, so the circle is what goes up.
                    val outside = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(Rect(Offset.Zero, this@Canvas.size))
                        addOval(Rect(center, diameter / 2))
                    }
                    drawPath(outside, Color.Black.copy(alpha = 0.6f))
                    drawCircle(Color.White.copy(alpha = 0.8f), radius = diameter / 2, center = center, style = Stroke(1.dp.toPx()))
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel), color = Color.White) }
                    TextButton(onClick = { onDone(PictureCrop.crop(size, diameter, zoom, offset)) }) {
                        Text(stringResource(R.string.action_done), color = Color.White)
                    }
                }
            }
        }
    }
}

/** How much of the screen's shorter side the crop circle takes. */
private const val CIRCLE_SHARE = 0.85f
