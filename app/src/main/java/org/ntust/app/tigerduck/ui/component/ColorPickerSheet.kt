package org.ntust.app.tigerduck.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.ui.theme.LocalVisualStylePolicy
import org.ntust.app.tigerduck.ui.theme.VisualStylePolicy
import org.ntust.app.tigerduck.util.replaceIosArg
import java.text.NumberFormat
import kotlin.math.roundToInt

/**
 * Bottom sheet that lets the user assign a tile color to a class.
 * Shows a curated preset grid plus an HSV fine-tune panel for custom colors,
 * with the color's code, which takes one typed in.
 *
 * @param usedByOthers maps preset colors that are currently assigned to OTHER
 *                     courses — used only to show a small indicator dot so the
 *                     user knows picking it will displace that course.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ColorPickerSheet(
    courseName: String,
    currentColor: Color,
    presetPalette: List<Color>,
    usedByOthers: Set<Int>,
    onApply: (Color) -> Unit,
    onDismiss: () -> Unit
) {
    val style = LocalVisualStylePolicy.current

    val initialHsv = remember(currentColor) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(currentColor.toArgb(), it) }
    }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var sat by remember { mutableFloatStateOf(initialHsv[1]) }
    var value by remember { mutableFloatStateOf(initialHsv[2]) }
    var showCustom by remember { mutableStateOf(false) }
    val customInView = remember { BringIntoViewRequester() }
    // Opening Custom adds the square, the slider and the code below what is shown; scroll
    // them into view rather than leave them under the fold.
    LaunchedEffect(showCustom) { if (showCustom) customInView.bringIntoView() }

    // A color picked whole, as a code typed in or a swatch, is kept exactly until the square
    // or the slider moves it: a round trip through HSV could change its code by one.
    var pickedHex by remember { mutableStateOf<Int?>(currentColor.toArgb() and 0xFFFFFF) }
    val code = remember { ColorCodeState(currentColor.toArgb()) }

    val hsvColor = remember(hue, sat, value) {
        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))
    }
    val selectedColor = pickedHex?.let { Color(it or 0xFF000000.toInt()) } ?: hsvColor
    val selectedArgb = selectedColor.toArgb() or 0xFF000000.toInt()

    fun setHsvFrom(color: Color) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color.toArgb(), hsv)
        hue = hsv[0]; sat = hsv[1]; value = hsv[2]
    }

    fun pick(hex: Int) {
        setHsvFrom(Color(hex or 0xFF000000.toInt()))
        pickedHex = hex and 0xFFFFFF
    }

    // The square and the slider keep the first lambdas they are given, so this reads the
    // color when it is called. The code field follows each move.
    fun move(h: Float = hue, s: Float = sat, v: Float = value) {
        hue = h; sat = s; value = v
        pickedHex = null
        code.show(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))
    }

    DetentSheet(
        onDismissed = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            // The controls scroll and the buttons stay below them: with Custom open the
            // sheet is taller than a short phone, or one with its text size turned up, and
            // an unscrolled column squeezes whatever comes last.
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.color_picker_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = courseNameForDisplay(courseName, maxChars = 40),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(20.dp))

                // Preview: see-through, as on the schedule grid's blocks, or under
                // TAT the pastel its tiles take.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(style.courseAccentColor(selectedColor, tint = selectedColor.copy(alpha = 0.85f))),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = courseNameForDisplay(
                            courseName.ifBlank { stringResource(R.string.color_picker_preview_fallback) },
                            maxChars = 30
                        ),
                        color = style.courseAccentTextColor(
                            tintText = if (selectedColor.red * 0.299f + selectedColor.green * 0.587f + selectedColor.blue * 0.114f > 0.5f)
                                Color(0xFF1C1C1E) else Color.White
                        ),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }

                Spacer(Modifier.height(22.dp))

                SectionLabel(text = stringResource(R.string.color_picker_presets_note))
                Spacer(Modifier.height(10.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    maxItemsInEachRow = 6
                ) {
                    presetPalette.forEachIndexed { index, preset ->
                        val presetArgb = preset.toArgb() or 0xFF000000.toInt()
                        ColorSwatch(
                            color = style.courseAccentColor(preset, tint = preset),
                            // Numbered from 1, as iOS reads them.
                            label = stringResource(R.string.a11y_course_color_swatch)
                                .replaceIosArg(1, index + 1L),
                            selected = presetArgb == selectedArgb,
                            usedByOther = presetArgb in usedByOthers && presetArgb != selectedArgb,
                            onClick = { pick(presetArgb); code.show(presetArgb) }
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { showCustom = !showCustom }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionLabel(
                        text = stringResource(R.string.color_picker_custom),
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = if (showCustom) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
                    )
                }

                if (showCustom) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .bringIntoViewRequester(customInView)
                    ) {
                        Spacer(Modifier.height(8.dp))
                        SatValSquare(
                            hue = hue,
                            sat = sat,
                            value = value,
                            onSatValChange = { s, v -> move(s = s, v = v) }
                        )
                        Spacer(Modifier.height(12.dp))
                        HueSlider(
                            hue = hue,
                            onHueChange = { move(h = it) }
                        )
                        Spacer(Modifier.height(12.dp))
                        ColorCodeField(state = code, onColor = { pick(it) })
                    }
                }
            }

            Spacer(Modifier.height(22.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.action_cancel)) }
                Button(
                    onClick = { onApply(selectedColor) },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.color_picker_apply)) }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
        modifier = modifier
    )
}

@Composable
private fun ColorSwatch(
    color: Color,
    /** What TalkBack calls the swatch; whether it is chosen rides its selected state. */
    label: String,
    selected: Boolean,
    usedByOther: Boolean,
    onClick: () -> Unit
) {
    val surface = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    // The ring and check of the chosen one: white, or dark on a pastel.
    val mark = LocalVisualStylePolicy.current.courseAccentTextColor(tintText = Color.White)
    Box(
        modifier = Modifier.size(44.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .align(Alignment.Center)
                .clip(CircleShape)
                .background(color)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .semantics { contentDescription = label }
                .then(
                    if (selected) Modifier.border(3.dp, mark, CircleShape)
                    else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = mark,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        if (usedByOther) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .align(Alignment.TopEnd)
                    .clip(CircleShape)
                    .background(surface)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .align(Alignment.Center)
                        .clip(CircleShape)
                        .background(onSurface.copy(alpha = 0.55f))
                )
            }
        }
    }
}

/**
 * The saturation and brightness square. TalkBack gets it as two adjustable
 * controls, Saturation over its top half and Brightness over its bottom, so
 * a swipe up or down moves one value and exploring by touch finds both; they
 * take no pointer input, so a finger still lands on the square under them.
 */
@Composable
internal fun SatValSquare(
    hue: Float,
    sat: Float,
    value: Float,
    onSatValChange: (sat: Float, value: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val pureHueColor = remember(hue) {
        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
    }
    val percent = NumberFormat.getPercentInstance(LocalConfiguration.current.locales[0])

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
    ) {
        SatValCanvas(pureHueColor, sat, value, onSatValChange)
        Column(Modifier.matchParentSize()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .adjustable(
                        name = stringResource(R.string.a11y_color_picker_saturation),
                        state = percent.format(sat),
                        current = sat,
                        range = 0f..1f,
                        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                        onChange = { onSatValChange(it, value) },
                    )
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .adjustable(
                        name = stringResource(R.string.a11y_color_picker_brightness),
                        state = percent.format(value),
                        current = value,
                        range = 0f..1f,
                        shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
                        onChange = { onSatValChange(sat, it) },
                    )
            )
        }
    }
}

@Composable
private fun SatValCanvas(
    pureHueColor: Color,
    sat: Float,
    value: Float,
    onSatValChange: (sat: Float, value: Float) -> Unit,
) {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    val s = (change.position.x / size.width).coerceIn(0f, 1f)
                    val v = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                    onSatValChange(s, v)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val s = (offset.x / size.width).coerceIn(0f, 1f)
                    val v = 1f - (offset.y / size.height).coerceIn(0f, 1f)
                    onSatValChange(s, v)
                }
            }
    ) {
        drawRect(color = pureHueColor)
        drawRect(brush = Brush.horizontalGradient(listOf(Color.White, Color.Transparent)))
        drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))

        val cx = sat * size.width
        val cy = (1f - value) * size.height
        val inner = 8.dp.toPx()
        drawCircle(
            color = Color.Black.copy(alpha = 0.4f),
            radius = inner + 2.dp.toPx(),
            center = Offset(cx, cy),
            style = Stroke(width = 1.dp.toPx())
        )
        drawCircle(
            color = Color.White,
            radius = inner,
            center = Offset(cx, cy),
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

/** The hue bar; TalkBack gets it as an adjustable control named Hue, read in degrees. */
@Composable
internal fun HueSlider(
    hue: Float,
    onHueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val degrees = NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0])
    val hueStops = remember {
        listOf(
            Color(0xFFFF0000),
            Color(0xFFFFFF00),
            Color(0xFF00FF00),
            Color(0xFF00FFFF),
            Color(0xFF0000FF),
            Color(0xFFFF00FF),
            Color(0xFFFF0000)
        )
    }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .adjustable(
                name = stringResource(R.string.a11y_color_picker_hue),
                state = degrees.format(hue.roundToInt()) + "°",
                current = hue,
                range = 0f..360f,
                shape = RoundedCornerShape(14.dp),
                onChange = onHueChange,
            )
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    val h = (change.position.x / size.width * 360f).coerceIn(0f, 360f)
                    onHueChange(h)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val h = (offset.x / size.width * 360f).coerceIn(0f, 360f)
                    onHueChange(h)
                }
            }
    ) {
        drawRect(brush = Brush.horizontalGradient(hueStops))
        val cx = (hue / 360f) * size.width
        val cy = size.height / 2
        val r = size.height / 2 - 2.dp.toPx()
        drawCircle(
            color = Color.Black.copy(alpha = 0.4f),
            radius = r + 2.dp.toPx(),
            center = Offset(cx, cy),
            style = Stroke(width = 1.dp.toPx())
        )
        drawCircle(
            color = Color.White,
            radius = r,
            center = Offset(cx, cy),
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

/**
 * One of the picker's values as TalkBack and a keyboard take it: a focusable
 * control called [name], read as [state], holding [current] in [range]. A
 * swipe up or down moves it a twentieth of the range (Compose's step for a
 * range with no steps of its own), TalkBack's set-progress sets it, and the
 * left and right arrow keys step it as the swipes do. Up and down, and an
 * arrow that would push past either end, are left to move the focus on, so
 * a keyboard never gets stuck in a control. While it has the keyboard's
 * focus a ring in [shape] shows which one the arrows move: the square's two
 * share its drawing. Each value reaches [onChange] within [range].
 */
private fun Modifier.adjustable(
    name: String,
    state: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    shape: Shape,
    onChange: (Float) -> Unit,
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val ring = MaterialTheme.colorScheme.primary
    val step = (range.endInclusive - range.start) / ADJUSTABLE_STEPS
    this
        .then(if (focused) Modifier.border(2.dp, ring, shape) else Modifier)
        .semantics {
            contentDescription = name
            stateDescription = state
            progressBarRangeInfo = ProgressBarRangeInfo(current.coerceIn(range), range)
            setProgress { target ->
                onChange(target.coerceIn(range))
                true
            }
        }
        .onFocusChanged { focused = it.isFocused }
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            val delta = when (event.key) {
                Key.DirectionRight -> step
                Key.DirectionLeft -> -step
                else -> return@onKeyEvent false
            }
            val next = (current + delta).coerceIn(range)
            if (next == current) return@onKeyEvent false
            onChange(next)
            true
        }
        .focusable()
}

/** How many steps a swipe or an arrow key takes across a value's whole range, as Compose steps a range with none of its own. */
private const val ADJUSTABLE_STEPS = 20

/**
 * Whether the color picker offers the palette's dark variants: in dark mode,
 * as the class table's blocks draw them, but not under TAT, whose pastel is
 * made from the light color in either theme.
 */
internal fun VisualStylePolicy.colorPickerOffersDarkPalette(isDark: Boolean): Boolean =
    isDark && courseColorStyle == VisualStylePolicy.CourseColorStyle.Tint
