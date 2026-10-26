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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.shared.AccentGradientDirection
import org.ntust.app.tigerduck.ui.component.ColorCodeField
import org.ntust.app.tigerduck.ui.component.ColorCodeState
import org.ntust.app.tigerduck.ui.component.DetentSheet
import org.ntust.app.tigerduck.ui.component.HueSlider
import org.ntust.app.tigerduck.ui.component.SatValSquare

/**
 * One color the sheet edits, as the HSV the square and slider move, and the
 * light palette hex it stands for. The hex is the one the sheet opened with
 * until the color is moved, so applying an untouched color gives it back
 * exactly, without the drift of a round trip through HSV.
 */
private data class EditedColor(val hue: Float, val sat: Float, val value: Float, val hex: Int) {
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
 * The sheet that picks a color of the user's own for the accent, or with
 * [initialEnd] a gradient of their own: a preview as the theme will draw it,
 * the start and end to switch between, the color square and hue slider of
 * the course color picker, and the color's code, which takes one typed in,
 * as TalkBack can where it cannot drag. The controls scroll and the buttons
 * stay below them, so Apply is there on a short screen too. [onApply] hands
 * back light palette hexes, the end null for a single color.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccentPickerSheet(
    initialStart: Int,
    initialEnd: Int?,
    direction: AccentGradientDirection,
    isDark: Boolean,
    onApply: (start: Int, end: Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = remember {
        mutableStateListOf(EditedColor.of(initialStart)).apply { if (initialEnd != null) add(EditedColor.of(initialEnd)) }
    }
    val isGradient = colors.size == 2
    var editing by remember { mutableIntStateOf(0) }
    val edited = colors[editing]
    // What the code field holds: the edited color's code, or what is being typed.
    val code = remember { ColorCodeState(colors[0].hex) }

    // The square and the slider keep the first lambdas they are given, so
    // these read which color is edited when they are called.
    fun move(change: (EditedColor) -> EditedColor) {
        val index = editing
        colors[index] = change(colors[index])
        code.show(colors[index].hex)
    }

    fun edit(index: Int) {
        editing = index
        code.show(colors[index].hex)
    }

    DetentSheet(
        onDismissed = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(
                        if (isGradient) R.string.settings_accent_gradient_custom else R.string.color_picker_title,
                    ),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (isGradient) {
                                gradientSwatch(colors[0].hex, colors[1].hex, direction, isDark)
                            } else {
                                solidSwatch(colors[0].hex, isDark)
                            },
                        ),
                )
                if (isGradient) {
                    Spacer(Modifier.height(16.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        listOf(R.string.settings_accent_gradient_start, R.string.settings_accent_gradient_end)
                            .forEachIndexed { index, label ->
                                SegmentedButton(
                                    selected = editing == index,
                                    onClick = { edit(index) },
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                                ) { Text(stringResource(label)) }
                            }
                    }
                }
                Spacer(Modifier.height(16.dp))
                SatValSquare(
                    hue = edited.hue,
                    sat = edited.sat,
                    value = edited.value,
                    onSatValChange = { s, v -> move { it.moved(sat = s, value = v) } },
                )
                Spacer(Modifier.height(12.dp))
                HueSlider(
                    hue = edited.hue,
                    onHueChange = { h -> move { it.moved(hue = h) } },
                )
                Spacer(Modifier.height(12.dp))
                ColorCodeField(
                    state = code,
                    onColor = { colors[editing] = EditedColor.of(it) },
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.action_cancel)) }
                Button(
                    onClick = { onApply(colors[0].hex, colors.getOrNull(1)?.hex) },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.color_picker_apply)) }
            }
        }
    }
}
