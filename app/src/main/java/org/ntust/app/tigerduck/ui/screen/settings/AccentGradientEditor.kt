package org.ntust.app.tigerduck.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.shared.AccentGradient
import org.ntust.app.tigerduck.shared.AccentGradientDirection
import org.ntust.app.tigerduck.shared.AccentGradientStyle
import org.ntust.app.tigerduck.ui.component.ColorCodeField
import org.ntust.app.tigerduck.ui.component.ColorCodeState
import org.ntust.app.tigerduck.ui.component.DetentSheet
import org.ntust.app.tigerduck.ui.component.HueSlider
import org.ntust.app.tigerduck.ui.component.SatValSquare

/**
 * One color the editor's controls move, as the HSV the square and slider move, and the light
 * palette hex it stands for. The hex is the one the editor opened with until the color is moved,
 * so applying an untouched color gives it back exactly, without the drift of a round trip through
 * HSV.
 */
internal data class EditedColor(val hue: Float, val sat: Float, val value: Float, val hex: Int) {
    fun moved(hue: Float = this.hue, sat: Float = this.sat, value: Float = this.value) =
        EditedColor(hue, sat, value, android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)) and 0xFFFFFF)

    companion object {
        fun of(hex: Int): EditedColor {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(hex or 0xFF000000.toInt(), hsv)
            return EditedColor(hsv[0], hsv[1], hsv[2], hex and 0xFFFFFF)
        }
    }
}

/**
 * The accent being edited: its stops, style and direction ([accent], kept to one to five stops by
 * [AccentGradient] itself), which stop the color controls move ([editing]), and, once a stop is
 * added or removed, the count of colors for TalkBack to say ([announcedCount]).
 */
@Stable
internal class AccentDraft(initial: AccentGradient) {
    var accent by mutableStateOf(initial)
        private set
    var editing by mutableIntStateOf(0)
        private set
    var edited by mutableStateOf(EditedColor.of(initial.first))
        private set
    var announcedCount by mutableStateOf<Int?>(null)
        private set

    /** What the color code field holds: the edited stop's code, or what is being typed. */
    val code = ColorCodeState(initial.first)

    fun edit(index: Int) {
        if (index !in accent.stops.indices) return
        editing = index
        edited = EditedColor.of(accent.stops[index])
        code.show(edited.hex)
    }

    /** Adds the first preset not yet a stop, as the iPhone's editor does, or the last stop again; and edits it. */
    fun add() {
        val presets = AppPreferences.themeColors.map { it.second }
        changeCount(accent.adding(presets.firstOrNull { it !in accent.stops } ?: accent.stops.last()))
        edit(accent.stops.lastIndex)
    }

    fun remove(index: Int) {
        changeCount(accent.removing(index))
        edit(if (editing > index) editing - 1 else editing.coerceAtMost(accent.stops.lastIndex))
    }

    /** Moves the stop at [from] to [to], the color controls staying on the stop they edit. */
    fun move(from: Int, to: Int) {
        val moved = accent.moving(from, to)
        if (moved == accent) return
        accent = moved
        editing = when {
            editing == from -> to
            editing in (from + 1)..to -> editing - 1
            editing in to until from -> editing + 1
            else -> editing
        }
    }

    /** The edited stop moved by the square or the slider. */
    fun recolor(change: (EditedColor) -> EditedColor) {
        edited = change(edited)
        accent = accent.replacing(editing, edited.hex)
        code.show(edited.hex)
    }

    /** The edited stop set to [hex], typed in whole. */
    fun recolorTo(hex: Int) {
        edited = EditedColor.of(hex)
        accent = accent.replacing(editing, edited.hex)
    }

    fun pickStyle(style: AccentGradientStyle) {
        accent = accent.withStyle(style)
    }

    fun pickDirection(direction: AccentGradientDirection) {
        accent = accent.withDirection(direction)
    }

    private fun changeCount(next: AccentGradient) {
        if (next == accent) return
        accent = next
        announcedCount = next.stops.size
    }
}

/**
 * The accent editor, the iPhone's (tigerduck-app#1004) as a sheet: a preview as the theme will
 * draw it, the four styles and, for a linear gradient, the eight directions; the stops, to add (up
 * to five), remove (down to one), reorder and recolor with the course color picker's square, hue
 * slider and color code, which takes one typed in, as TalkBack can where it cannot drag. The
 * controls scroll and the buttons stay below them, so Apply is there on a short screen too.
 *
 * [onApply] hands back the stops as picked, light palette hexes: each screen draws a color of the
 * user's own readable on its cards as it does any ([AccentPolicy.fixedColor]), so the colors the
 * editor opened with, a pale preset included, come back unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccentGradientEditor(
    initial: AccentGradient,
    isDark: Boolean,
    onApply: (AccentGradient) -> Unit,
    onDismiss: () -> Unit,
) {
    val draft = remember { AccentDraft(initial) }
    DetentSheet(onDismissed = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                EditorControls(draft, isDark)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(onClick = { onApply(draft.accent) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.color_picker_apply))
                }
            }
        }
    }
}

@Composable
private fun EditorControls(draft: AccentDraft, isDark: Boolean) {
    val accent = draft.accent
    Text(
        text = stringResource(R.string.settings_accent_gradient_custom),
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(16.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(drawnAccent(accent, isDark)),
    )
    if (accent.isGradient) {
        Spacer(Modifier.height(16.dp))
        StyleChooser(accent, isDark, onPick = draft::pickStyle)
        if (accent.style == AccentGradientStyle.Linear) {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.settings_accent_gradient_direction), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            DirectionChooser(accent, isDark, onPick = draft::pickDirection)
        }
    }
    Spacer(Modifier.height(12.dp))
    AccentGradientStops(draft, isDark)
    Spacer(Modifier.height(12.dp))
    // The square and the slider keep the first lambdas they are given, so these go through the
    // draft, which knows which stop is edited when they are called.
    SatValSquare(
        hue = draft.edited.hue,
        sat = draft.edited.sat,
        value = draft.edited.value,
        onSatValChange = { s, v -> draft.recolor { it.moved(sat = s, value = v) } },
    )
    Spacer(Modifier.height(12.dp))
    HueSlider(hue = draft.edited.hue, onHueChange = { h -> draft.recolor { it.moved(hue = h) } })
    Spacer(Modifier.height(12.dp))
    ColorCodeField(state = draft.code, onColor = draft::recolorTo)
}
