package org.ntust.app.tigerduck.widget.content

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.background
import androidx.glance.unit.ColorProvider
import org.ntust.app.tigerduck.shared.AccentGradient
import org.ntust.app.tigerduck.shared.AccentShader
import org.ntust.app.tigerduck.widget.WidgetColors

/**
 * The shapes a widget fills with the accent, each with the size of the bitmap its gradient is drawn
 * into: Glance takes no brush, only colors and images. Each is stretched over its shape, so the
 * gradient runs across the whole of it, in about the shape's own proportions.
 */
enum class WidgetAccentMark(val widthPx: Int, val heightPx: Int) {
    /** The library shortcut's tile. */
    Tile(96, 96),

    /** An "ongoing" badge round its label. */
    Badge(120, 40),

    /** A class's progress bar, the gradient across the part filled. */
    Bar(240, 8),
}

/**
 * [this] filled with the widgets' accent: its gradient ([WidgetColors.highlightGradient]), drawn for
 * [mark], while Settings' widgets switch is on, and its first stop ([WidgetColors.highlight]) else.
 */
internal fun GlanceModifier.accentBackground(colors: WidgetColors, mark: WidgetAccentMark): GlanceModifier =
    accentBackground(colors.highlight, colors.highlightGradient, mark)

/** [this] filled with [gradient] drawn for [mark], or with [color] while there is none. */
internal fun GlanceModifier.accentBackground(color: Color, gradient: AccentGradient?, mark: WidgetAccentMark): GlanceModifier =
    accentImage(gradient, mark)?.let { background(it) } ?: background(ColorProvider(color))

/** [gradient] as an image for [mark], or null for none. */
internal fun accentImage(gradient: AccentGradient?, mark: WidgetAccentMark): ImageProvider? =
    gradient?.takeIf { it.isGradient }?.let { ImageProvider(accentBitmap(it, mark)) }

/**
 * [gradient], each stop already as the widget's theme draws it, drawn for [mark] in its style
 * ([AccentShader.bitmap], a mesh included on every Android). The same bitmap each time it is asked
 * for again: a widget redraws on every tick, and its host is then sent the one image only once.
 */
internal fun accentBitmap(gradient: AccentGradient, mark: WidgetAccentMark): Bitmap = synchronized(bitmaps) {
    val key = gradient to mark
    bitmaps.get(key) ?: AccentShader.bitmap(gradient, mark.widthPx, mark.heightPx).also { bitmaps.put(key, it) }
}

/** Every mark of the accent in both themes, and a little room for the one picked before. */
private val bitmaps = LruCache<Pair<AccentGradient, WidgetAccentMark>, Bitmap>(12)
