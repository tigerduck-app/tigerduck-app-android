package org.ntust.app.tigerduck.shared

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Render the API-supplied virtual-code string to a square QR [Bitmap]. Shared
 * between phone and watch so the encoding choices (margin, color, pixel
 * batching) stay identical.
 */
object LibraryQRRenderer {

    fun render(content: String, size: Int): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        // Build a single IntArray then hand it to Bitmap.createBitmap so we
        // avoid the ~size*size setPixel calls the naive loop would do per
        // refresh.
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val row = y * size
            for (x in 0 until size) {
                pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
    }
}
