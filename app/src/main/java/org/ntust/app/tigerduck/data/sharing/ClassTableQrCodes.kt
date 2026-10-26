package org.ntust.app.tigerduck.data.sharing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.ntust.app.tigerduck.util.logSummary
import java.io.IOException
import kotlin.math.max

/**
 * The share sheet's QR code. Error correction level M, as the iOS app draws it, and the four
 * modules of light margin the QR code standard asks for. Both share code formats are in upper
 * case, which ZXing writes in alphanumeric mode, the smallest symbol for them.
 */
object ClassTableShareQrCode {
    private const val MARGIN = 4

    /** One module a cell, margin included; null for a text too long for any QR code. */
    fun matrix(text: String): BitMatrix? = try {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to MARGIN,
        )
        // A size of zero leaves the symbol at one pixel a module.
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    } catch (e: WriterException) {
        Log.w(TAG, "No QR code for the share code: ${e.logSummary()}")
        null
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "No QR code for the share code: ${e.logSummary()}")
        null
    }

    /** One pixel a module, for the sheet to scale up without smoothing. */
    fun bitmap(text: String): Bitmap? {
        val matrix = matrix(text) ?: return null
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) BLACK else WHITE
        }
        return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }

    private const val TAG = "ClassTableShareQr"
    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
}

/**
 * Reads the QR codes in a picture, such as a screenshot of someone's share sheet, with ZXing on
 * the device alone, as the import page's camera does.
 */
object QrCodeImageReader {
    /**
     * A phone screenshot keeps its full size, which a dense code of several terms needs; a
     * camera photo is scaled down to this on its long side, still several pixels a module.
     */
    private const val MAX_SIDE = 3000

    /** Every QR code's text in the [width] by [height] ARGB [pixels], in no set order; empty for none. */
    fun read(pixels: IntArray, width: Int, height: Int): List<String> = try {
        val source = RGBLuminanceSource(width, height, pixels)
        // The second pass reads a light code on a dark background, as a dark-themed screenshot may hold.
        listOf<LuminanceSource>(source, source.invert()).firstNotNullOfOrNull { luminance ->
            readAll(luminance).takeIf { it.isNotEmpty() }
        }.orEmpty()
    } catch (e: RuntimeException) {
        // Any picture the user picks: one ZXing trips over holds no code it can read.
        Log.w(TAG, "The picture did not read: ${e.logSummary()}")
        emptyList()
    }

    /** The QR codes in the picture at [uri], or null when the picture cannot be opened. */
    fun read(context: Context, uri: Uri): List<String>? {
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                // Software, so its pixels can be read back.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val side = max(info.size.width, info.size.height)
                if (side > MAX_SIDE) {
                    val scale = MAX_SIDE.toFloat() / side
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1),
                    )
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not open the picture: ${e.logSummary()}")
            return null
        } catch (e: RuntimeException) {
            // A picture the decoder does not support, or a provider that refused it.
            Log.w(TAG, "Could not open the picture: ${e.logSummary()}")
            return null
        } catch (_: OutOfMemoryError) {
            Log.w(TAG, "The picture is too large to open")
            return null
        }
        val pixels = try {
            IntArray(bitmap.width * bitmap.height)
        } catch (_: OutOfMemoryError) {
            bitmap.recycle()
            Log.w(TAG, "The picture is too large to read")
            return null
        }
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return read(pixels, bitmap.width, bitmap.height).also { bitmap.recycle() }
    }

    private fun readAll(source: LuminanceSource): List<String> {
        val hints = mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        )
        for (bitmap in listOf(BinaryBitmap(HybridBinarizer(source)), BinaryBitmap(GlobalHistogramBinarizer(source)))) {
            try {
                val texts = QRCodeMultiReader().decodeMultiple(bitmap, hints).mapNotNull { it.text }
                if (texts.isNotEmpty()) return texts.distinct()
            } catch (_: ReaderException) {
                // None found this way; the single reader below finds some the multi reader misses.
            }
            try {
                QRCodeReader().decode(bitmap, hints).text?.let { return listOf(it) }
            } catch (_: ReaderException) {
                // Try the next binarizer.
            }
        }
        return emptyList()
    }

    private const val TAG = "QrCodeImageReader"
}
