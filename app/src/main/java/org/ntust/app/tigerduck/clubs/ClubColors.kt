package org.ntust.app.tigerduck.clubs

import androidx.compose.ui.graphics.toArgb
import org.ntust.app.tigerduck.ui.theme.courseColorPalette

/**
 * A club's colors as `0xRRGGBB`. The default is the one every device and the Apple apps give a
 * club that has none yet (docs/decisions/0030 in the Apple repo): the 32-bit FNV-1a hash of the
 * decimal id's UTF-8 bytes, modulo the course palette, which it indexes in its order.
 */
object ClubColors {
    /** The course palette's light hexes, in order: `TigerDuckTheme.coursePaletteHexes` on iOS. */
    val palette: List<Int> = courseColorPalette.map { it.toArgb() and 0xFFFFFF }

    fun defaultColor(id: Int): Int = palette[(fnv1a(id.toString()) % palette.size.toUInt()).toInt()]

    /** 32-bit FNV-1a over [text]'s UTF-8 bytes. */
    fun fnv1a(text: String): UInt {
        var hash = 2_166_136_261u
        for (byte in text.encodeToByteArray()) {
            hash = (hash xor byte.toUByte().toUInt()) * 16_777_619u
        }
        return hash
    }

    /** `#RRGGBB`, as course colors travel; null for anything else. */
    fun parse(hex: String): Int? {
        if (hex.length != 7 || hex[0] != '#' || !hex.drop(1).all { it.isHexDigitChar() }) return null
        return hex.drop(1).toInt(16)
    }

    fun hex(color: Int): String = "#%06X".format(color and 0xFFFFFF)

    private fun Char.isHexDigitChar(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
