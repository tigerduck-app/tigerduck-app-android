package org.ntust.app.tigerduck.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/**
 * What a [VisualPreset] means for each surface that draws differently under
 * it. Views read these values instead of branching on the preset, so a new
 * style is a new case here rather than an `if` in every view.
 *
 * The class table, the course cards in its today carousel, Home's time
 * slider and the Week widget differ; every other screen looks the same
 * under both presets.
 */
@Immutable
data class VisualStylePolicy(val preset: VisualPreset) {

    enum class ClassTableGridStyle {
        /**
         * A course's run of periods is one block, the period column shows each
         * period's start and end, and an empty slot is a faint tile.
         */
        Blocks,

        /**
         * Each period of a run is a tile of its own, the period column shows
         * only the period, and rows alternate background bands while an empty
         * slot stays bare.
         */
        PeriodTiles,
    }

    val classTableGridStyle: ClassTableGridStyle
        get() = when (preset) {
            VisualPreset.TigerDuck -> ClassTableGridStyle.Blocks
            VisualPreset.Tat -> ClassTableGridStyle.PeriodTiles
        }

    /** Whether the period column shows each period's start and end beside it. */
    val classTableShowsPeriodTimes: Boolean
        get() = classTableGridStyle == ClassTableGridStyle.Blocks

    /**
     * A course's fill on the class table, conflict clusters included.
     *
     * [courseColor] is the course's own colour, the light palette hex that is
     * stored and synced; [themeColor] is that colour as the current theme draws
     * it, its dark variant in dark mode.
     */
    fun classTableCourseFill(courseColor: Color, themeColor: Color, isDark: Boolean): Color =
        when (classTableGridStyle) {
            ClassTableGridStyle.Blocks -> if (isDark) themeColor else courseColor.copy(alpha = 0.5f)
            ClassTableGridStyle.PeriodTiles -> courseColor.pastel()
        }

    /**
     * The outline of a required course on [classTableCourseFill], where the
     * user has them outlined. [courseColor] is the course's own colour, as for
     * the fill: on a translucent block that colour itself stands out; on a
     * pastel tile it would all but vanish, so a darker mix of it is drawn, as
     * the iPhone's `classTableCourseOutline` does.
     */
    fun classTableCourseOutline(courseColor: Color): Color = when (classTableGridStyle) {
        ClassTableGridStyle.Blocks -> courseColor
        ClassTableGridStyle.PeriodTiles -> courseColor.pastelOutline()
    }

    /** A course's name on [classTableCourseFill]. */
    fun classTableCourseTextColor(isDark: Boolean): Color = when (classTableGridStyle) {
        ClassTableGridStyle.Blocks -> if (isDark) Color.White else Color(0xFF1C1C1E)
        ClassTableGridStyle.PeriodTiles -> TextOnPastel
    }

    /**
     * The room hint and the corner marks on [classTableCourseFill]; on a pastel,
     * [TextOnPastelSecondary].
     */
    fun classTableCourseSecondaryTextColor(isDark: Boolean): Color = when (classTableGridStyle) {
        ClassTableGridStyle.Blocks -> classTableCourseTextColor(isDark).copy(alpha = 0.7f)
        ClassTableGridStyle.PeriodTiles -> TextOnPastelSecondary
    }

    /** A slot no course takes, given the theme's `surfaceVariant`. */
    fun classTableEmptySlotColor(surfaceVariant: Color): Color = when (classTableGridStyle) {
        ClassTableGridStyle.Blocks -> surfaceVariant.copy(alpha = 0.3f)
        ClassTableGridStyle.PeriodTiles -> Color.Transparent
    }

    /**
     * The band behind the period row at [rowIndex], from the top, given the
     * theme's `onSurface`; null where rows have no bands.
     */
    fun classTableRowBand(rowIndex: Int, onSurface: Color): Color? = when (classTableGridStyle) {
        ClassTableGridStyle.Blocks -> null
        ClassTableGridStyle.PeriodTiles -> onSurface.copy(alpha = if (rowIndex % 2 == 0) 0.08f else 0.04f)
    }

    enum class CourseColorStyle {
        /**
         * The course colour laid over what is behind it, as strongly as each
         * card or segment picks for its state, with the theme's text.
         */
        Tint,

        /**
         * The course colour's opaque [pastel], as on the class table's period
         * tiles, with dark text in either theme; a card that steps back takes
         * a paler one.
         */
        Pastel,
    }

    /**
     * How the course colour shows outside the class table: on the today
     * carousel's cards above it, and on Home's time slider, its cards and
     * its track.
     */
    val courseColorStyle: CourseColorStyle
        get() = when (preset) {
            VisualPreset.TigerDuck -> CourseColorStyle.Tint
            VisualPreset.Tat -> CourseColorStyle.Pastel
        }

    /**
     * A course card's fill. [courseColor] is the course's own colour, the light
     * palette hex; [tintAlpha] is how strongly a [CourseColorStyle.Tint] card
     * lays it over [surface], which each card picks for its state. [muted]
     * marks a card that steps back: a class already over, or one beside the
     * class the time slider is on.
     */
    fun courseCardFill(
        courseColor: Color,
        tintAlpha: Float,
        muted: Boolean,
        surface: Color,
        isDark: Boolean,
    ): Color = when (courseColorStyle) {
        // See-through in light mode and laid over the surface in dark, as the
        // cards have always drawn it.
        CourseColorStyle.Tint -> courseColor.copy(alpha = tintAlpha).let {
            if (isDark) it.compositeOver(surface) else it
        }
        CourseColorStyle.Pastel ->
            courseColor.pastel(if (muted) MUTED_PASTEL_WHITE_SHARE else PASTEL_WHITE_SHARE)
    }

    /** A course card's name on [courseCardFill], given the theme's text colour there. */
    fun courseCardTextColor(themeText: Color): Color = when (courseColorStyle) {
        CourseColorStyle.Tint -> themeText
        CourseColorStyle.Pastel -> TextOnPastel
    }

    /** A course card's room and time on [courseCardFill], given the theme's `onSurface`. */
    fun courseCardSecondaryTextColor(onSurface: Color): Color = when (courseColorStyle) {
        CourseColorStyle.Tint -> onSurface.copy(alpha = ContentAlpha.SECONDARY)
        CourseColorStyle.Pastel -> TextOnPastelSecondary
    }

    /** A course card's assignment mark on [courseCardFill]. */
    val courseCardMarkColor: Color
        get() = when (courseColorStyle) {
            CourseColorStyle.Tint -> Color.Gray.copy(alpha = 0.5f)
            CourseColorStyle.Pastel -> TextOnPastelSecondary
        }

    /**
     * A class's segment on the time slider's track. [courseColor] is the light
     * palette hex; [tintAlpha] is how strongly a [CourseColorStyle.Tint] track
     * lays it on, stronger for the class the slider is on. A pastel track
     * draws every segment alike and marks that one with
     * [timeSliderActiveOutline] instead: a paler pastel all but vanishes into
     * the light track, and on the dark one it would stand out the more.
     */
    fun timeSliderSegmentColor(courseColor: Color, tintAlpha: Float): Color =
        when (courseColorStyle) {
            CourseColorStyle.Tint -> courseColor.copy(alpha = tintAlpha)
            CourseColorStyle.Pastel -> courseColor.pastel()
        }

    /**
     * The outline drawn just inside the segment of the class the slider is on;
     * null where its stronger tint marks it.
     */
    val timeSliderActiveOutline: Color?
        get() = when (courseColorStyle) {
            CourseColorStyle.Tint -> null
            CourseColorStyle.Pastel -> TextOnPastelSecondary
        }

    /** The dot at the slider's centre while it is on a class of [courseColor]. */
    fun timeSliderInClassDotColor(courseColor: Color): Color = when (courseColorStyle) {
        CourseColorStyle.Tint -> courseColor
        // Dark on the class's pastel, where the course colour itself can all
        // but vanish: a yellow on its own pastel, say.
        CourseColorStyle.Pastel -> TextOnPastel
    }

    companion object {
        /** Text on a [pastel] fill, which stays light in either theme. */
        val TextOnPastel = Color(0xFF1A1A1A)

        /**
         * Room hints, times and marks on a [pastel] fill. At 80%: at 70% it fell
         * under 4.5:1 on the pastel of a dark picked colour.
         */
        val TextOnPastelSecondary = TextOnPastel.copy(alpha = 0.8f)
    }
}

/**
 * This colour mixed with white into an opaque pastel. Each sRGB channel moves
 * [whiteShare] of the way to white, which keeps the hue, so a course still
 * shows the colour it was given. At the default 65% every channel is lifted
 * to at least 65%, where [VisualStylePolicy.TextOnPastel] reads at better
 * than 7:1; a larger share is paler still and reads better yet.
 */
fun Color.pastel(whiteShare: Float = PASTEL_WHITE_SHARE): Color = Color(
    red = red + (1f - red) * whiteShare,
    green = green + (1f - green) * whiteShare,
    blue = blue + (1f - blue) * whiteShare,
    alpha = 1f,
)

/**
 * This colour mixed half with black, opaque: a line on its own [pastel] that
 * stands out from it at better than 3:1 whatever the colour, as the iPhone's
 * `pastelOutline` is.
 */
fun Color.pastelOutline(): Color = Color(
    red = red * (1f - PASTEL_OUTLINE_BLACK_SHARE),
    green = green * (1f - PASTEL_OUTLINE_BLACK_SHARE),
    blue = blue * (1f - PASTEL_OUTLINE_BLACK_SHARE),
    alpha = 1f,
)

private const val PASTEL_WHITE_SHARE = 0.65f

private const val PASTEL_OUTLINE_BLACK_SHARE = 0.5f

/** A muted course card's pastel: paler, as a TigerDuck card's tint is fainter. */
private const val MUTED_PASTEL_WHITE_SHARE = 0.82f

/**
 * The interface style in effect, provided once at the root of the app from
 * the Settings preference.
 */
val LocalVisualStylePolicy = staticCompositionLocalOf { VisualStylePolicy(VisualPreset.Default) }
