package org.ntust.app.tigerduck.files

/**
 * A picture's kind, by its first bytes alone, whatever name or type it came with: the kinds a
 * picture viewer draws as pixels, never markup such as an SVG or a web page, which whatever showed
 * them would run. As the iPhone's `Data.imageType` (tigerduck-app#1047), for a mail's body image,
 * and for a Moodle member's picture.
 */
enum class RasterImage(val mimeType: String, val extension: String) {
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg"),
    GIF("image/gif", "gif"),
    WEBP("image/webp", "webp"),
    HEIC("image/heic", "heic"),
    HEIF("image/heif", "heif"),
    BMP("image/bmp", "bmp"),
    TIFF("image/tiff", "tiff"),
    ;

    companion object {
        /** The kind [bytes] start as, or null for anything else. */
        fun of(bytes: ByteArray): RasterImage? {
            fun at(index: Int, vararg expected: Int) =
                bytes.size >= index + expected.size && expected.withIndex().all { (i, b) -> bytes[index + i] == b.toByte() }
            fun textAt(index: Int, text: String) = at(index, *text.map { it.code }.toIntArray())
            return when {
                at(0, 0x89, 'P'.code, 'N'.code, 'G'.code, 0x0D, 0x0A, 0x1A, 0x0A) -> PNG
                at(0, 0xFF, 0xD8, 0xFF) -> JPEG
                textAt(0, "GIF87a") || textAt(0, "GIF89a") -> GIF
                textAt(0, "RIFF") && textAt(8, "WEBP") -> WEBP
                textAt(4, "ftyp") -> isoBrand(bytes.decodeToString(8, minOf(12, bytes.size)))
                textAt(0, "II") && at(2, 0x2A, 0x00) || textAt(0, "MM") && at(2, 0x00, 0x2A) -> TIFF
                textAt(0, "BM") && bytes.size >= 18 && littleEndianInt(bytes, 14) in BMP_HEADER_SIZES -> BMP
                else -> null
            }
        }

        /** An ISO media file is a picture only under a HEIF brand: a video or an AVIF is not one of these. */
        private fun isoBrand(brand: String): RasterImage? = when (brand) {
            in HEIC_BRANDS -> HEIC
            in HEIF_BRANDS -> HEIF
            else -> null
        }

        private fun littleEndianInt(bytes: ByteArray, at: Int): Int =
            (0 until 4).sumOf { (bytes[at + it].toInt() and 0xFF) shl (8 * it) }

        private val HEIC_BRANDS = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs")
        private val HEIF_BRANDS = setOf("mif1", "msf1")

        /** The sizes a BMP's info header comes in, from the OS/2 one to the fifth version's. */
        private val BMP_HEADER_SIZES = setOf(12, 40, 52, 56, 64, 108, 124)
    }
}
