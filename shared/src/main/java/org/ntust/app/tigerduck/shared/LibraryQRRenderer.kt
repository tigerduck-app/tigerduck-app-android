package org.ntust.app.tigerduck.shared

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
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

    /**
     * Tight bounding box of non-white pixels — i.e. the actual QR module
     * pattern within the rendered bitmap, excluding the embedded quiet zone
     * and zxing's floor-rounding leftover white border.
     *
     * Watch fullscreen uses this to draw only the pattern portion at the
     * size indicated by the QR-padding settings preview, so the brackets
     * line up with what the user actually sees. Returns the full bitmap
     * rect if no non-white pixels are found (defensive — should not happen
     * for a well-formed QR).
     */
    fun patternBounds(source: Bitmap): Rect {
        val w = source.width
        val h = source.height
        if (w == 0 || h == 0) return Rect(0, 0, 0, 0)
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        var top = -1
        scanTop@ for (y in 0 until h) {
            for (x in 0 until w) {
                if (pixels[y * w + x] != Color.WHITE) { top = y; break@scanTop }
            }
        }
        if (top < 0) return Rect(0, 0, w, h)

        var bottom = top
        scanBottom@ for (y in h - 1 downTo top) {
            for (x in 0 until w) {
                if (pixels[y * w + x] != Color.WHITE) { bottom = y; break@scanBottom }
            }
        }

        var left = 0
        scanLeft@ for (x in 0 until w) {
            for (y in top..bottom) {
                if (pixels[y * w + x] != Color.WHITE) { left = x; break@scanLeft }
            }
        }

        var right = left
        scanRight@ for (x in w - 1 downTo left) {
            for (y in top..bottom) {
                if (pixels[y * w + x] != Color.WHITE) { right = x; break@scanRight }
            }
        }

        // Rect.right/bottom are exclusive per Android convention.
        return Rect(left, top, right + 1, bottom + 1)
    }
}
