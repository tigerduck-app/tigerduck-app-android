package org.ntust.app.tigerduck.wear.ui.theme

import androidx.compose.ui.graphics.Color
import org.ntust.app.tigerduck.shared.Pastel
import org.ntust.app.tigerduck.shared.WearProtocol

/**
 * How the watch's course cards draw a course, following the phone's interface
 * style. The iPhone's watch draws both styles alike; here they follow the phone.
 */
enum class WatchCardStyle {
    /** TigerDuck: the course color laid over the black, with the watch's own text. */
    Tint,

    /** TAT: the course color's opaque pastel, as on the phone's class table, with dark text. */
    Pastel;

    companion object {
        /** The style for the phone's raw [visualPreset]; none, from an older phone, or one this build doesn't know, is TigerDuck. */
        fun fromVisualPreset(visualPreset: String?): WatchCardStyle =
            if (visualPreset == WearProtocol.Schedule.VISUAL_PRESET_TAT) Pastel else Tint
    }
}

/**
 * A course card's colors. [Color.Unspecified] leaves a part to the theme, as a
 * TigerDuck card's text and progress have always been.
 */
data class CourseCardColors(
    val fill: Color,
    /** The course's name. */
    val text: Color,
    /** Room, instructor and times. */
    val secondaryText: Color,
    /** The status line ("Starts 12:20", "Next"). */
    val emphasis: Color,
    /** The ongoing class's progress bar and its track. */
    val progress: Color,
    val progressTrack: Color,
)

/**
 * The card for [courseColor] in [style]. [tintAlpha] is how strongly a TigerDuck
 * card lays the color over the black, which each card picks for its state;
 * [muted] marks a card that steps back, as a class already over, which a TAT
 * card shows with a paler pastel instead.
 */
fun courseCardColors(style: WatchCardStyle, courseColor: Color, tintAlpha: Float, muted: Boolean): CourseCardColors =
    when (style) {
        WatchCardStyle.Tint -> CourseCardColors(
            fill = courseColor.copy(alpha = tintAlpha),
            text = Color.Unspecified,
            secondaryText = Color.Unspecified,
            emphasis = courseColor,
            progress = Color.Unspecified,
            progressTrack = Color.Unspecified,
        )
        WatchCardStyle.Pastel -> {
            val text = Color(Pastel.TEXT_ARGB)
            val secondary = text.copy(alpha = Pastel.SECONDARY_TEXT_ALPHA)
            CourseCardColors(
                fill = courseColor.pastel(if (muted) Pastel.MUTED_WHITE_SHARE else Pastel.WHITE_SHARE),
                text = text,
                secondaryText = secondary,
                // The course color itself can all but vanish on its own pastel: a yellow, say.
                emphasis = text,
                progress = text,
                progressTrack = text.copy(alpha = 0.2f),
            )
        }
    }

/** This color mixed with white into an opaque pastel, by [Pastel]'s math, as the phone draws it. */
fun Color.pastel(whiteShare: Float = Pastel.WHITE_SHARE): Color = Color(
    red = Pastel.channel(red, whiteShare),
    green = Pastel.channel(green, whiteShare),
    blue = Pastel.channel(blue, whiteShare),
    alpha = 1f,
)
