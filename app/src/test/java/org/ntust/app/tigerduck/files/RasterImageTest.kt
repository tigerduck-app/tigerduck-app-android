package org.ntust.app.tigerduck.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A picture's kind from its first bytes alone; markup, whatever it claims, is none. */
class RasterImageTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    /** An ISO media file's start: its size, `ftyp`, then [brand]. */
    private fun iso(brand: String) = bytes(0, 0, 0, 0x18) + ascii("ftyp$brand") + ByteArray(12)

    @Test
    fun `each kind is known by its signature`() {
        assertEquals(RasterImage.PNG, RasterImage.of(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0)))
        assertEquals(RasterImage.JPEG, RasterImage.of(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10)))
        assertEquals(RasterImage.GIF, RasterImage.of(ascii("GIF89a") + ByteArray(6)))
        assertEquals(RasterImage.GIF, RasterImage.of(ascii("GIF87a") + ByteArray(6)))
        assertEquals(RasterImage.WEBP, RasterImage.of(ascii("RIFF") + bytes(0x24, 0, 0, 0) + ascii("WEBPVP8 ")))
        assertEquals(RasterImage.HEIC, RasterImage.of(iso("heic")))
        assertEquals(RasterImage.HEIF, RasterImage.of(iso("mif1")))
        assertEquals(RasterImage.TIFF, RasterImage.of(ascii("II") + bytes(0x2A, 0, 8, 0, 0, 0)))
        assertEquals(RasterImage.TIFF, RasterImage.of(ascii("MM") + bytes(0, 0x2A, 0, 0, 0, 8)))
        // A file header, then a BITMAPINFOHEADER's size, 40, at byte 14.
        assertEquals(RasterImage.BMP, RasterImage.of(ascii("BM") + ByteArray(12) + bytes(40, 0, 0, 0) + ByteArray(8)))
    }

    @Test
    fun `markup is no picture, whatever it is sent as`() {
        assertNull(RasterImage.of(ascii("""<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>""")))
        assertNull(RasterImage.of(ascii("<html><body><script>alert(1)</script></body></html>")))
        assertNull(RasterImage.of(ascii("""<?xml version="1.0"?><svg/>""")))
    }

    @Test
    fun `a near miss is no picture`() {
        assertNull(RasterImage.of(ByteArray(0)))
        assertNull(RasterImage.of(bytes(0x89, 0x50, 0x4E, 0x47)))
        assertNull(RasterImage.of(ascii("GIF8")))
        assertNull(RasterImage.of(ascii("RIFF") + bytes(0x24, 0, 0, 0) + ascii("WAVEfmt ")))
        // A video and an AVIF are ISO media files too.
        assertNull(RasterImage.of(iso("isom")))
        assertNull(RasterImage.of(iso("avif")))
        assertNull(RasterImage.of(ascii("BM is how this line starts, and it is text")))
    }
}
