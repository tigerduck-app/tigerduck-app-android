package org.ntust.app.tigerduck.ui.screen.whatsnew

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ntust.app.tigerduck.core.strings.R
import kotlin.math.roundToInt

/** "Make it your size": the app's and the widgets' display and text size, each on its own slider. */
internal fun displaySizePage() = WhatsNewPage.Feature(
    id = "display-size",
    visual = WhatsNewVisual.Custom { animate -> DisplaySizeDemo(animate) },
    title = WhatsNewText(
        en = "Make it your size",
        zhHant = "大小由你調整",
    ),
    body = WhatsNewText(
        en = "In Settings › Other settings › Display and text size, set the display and text size for the app and for widgets, each on its own.",
        zhHant = "在「設定 › 其他設定 › 顯示與文字大小」裡，App 與小工具的顯示和文字大小都能分開調整。",
    ),
)

private val sizeDemoDescription = WhatsNewText(
    en = "Demo: from Settings, Other settings, Display and text size: the display size slider grows the preview, then the text size slider grows its text.",
    zhHant = "示範：從設定的「其他設定」進入「顯示與文字大小」，拉動顯示大小滑桿，預覽跟著放大；再拉動文字大小滑桿，預覽的文字跟著變大。",
)

private const val SIZE_LOOP_MILLIS = 8_000

// The sliders' ranges, as in Settings: display 75–130%, text 80–150%.
private const val DISPLAY_MIN = 75f
private const val DISPLAY_MAX = 130f
private const val TEXT_MIN = 80f
private const val TEXT_MAX = 150f

@Composable
private fun DisplaySizeDemo(animate: Boolean) {
    PathDemo(
        description = sizeDemoDescription.resolve(),
        animate = animate,
        featureMillis = SIZE_LOOP_MILLIS,
        start = PathStart.SettingsGear,
        stops = listOf(
            PathStop(
                title = stringResource(R.string.feature_settings),
                section = stringResource(R.string.settings_section_other_settings),
                rows = listOf(
                    stringResource(R.string.settings_library_related_features),
                    stringResource(R.string.school_mail_account_title),
                    stringResource(R.string.settings_section_other_settings),
                ),
                tapped = 2,
            ),
            PathStop(
                title = stringResource(R.string.settings_section_other_settings),
                section = null,
                rows = listOf(
                    stringResource(R.string.settings_display_size_title),
                    stringResource(R.string.vibration_settings_title),
                ),
                tapped = 0,
                scrolled = false,
            ),
        ),
        // Still: the display size up.
        featureStill = 0.35f,
    ) { t -> DisplaySizeScene(t) }
}

/** Display and text size: display size up and back, then text size up and back, through [t] (0 to 1). */
@Composable
private fun DisplaySizeScene(t: () -> Float) {
    // Kept with their clock, which animations turning on or off replaces.
    val display = remember(t) { { 100f + 20f * (span(t(), 0.10f, 0.28f) - span(t(), 0.42f, 0.52f)) } }
    val text = remember(t) { { 100f + 30f * (span(t(), 0.60f, 0.78f) - span(t(), 0.90f, 0.98f)) } }
    val colors = MaterialTheme.colorScheme
    // The sizes in the sliders' steps of 5%, as the preview takes them: it lays
    // out again at each, as Settings' does, rather than on every frame.
    val displayStep by remember(display) { derivedStateOf { step(display()) } }
    val textStep by remember(text) { derivedStateOf { step(text()) } }
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().background(colors.surfaceContainerLow).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MockText(stringResource(R.string.settings_display_size_title), size = 12.sp, weight = FontWeight.Bold)
            // Room for the preview at its largest, so the sliders under it stay put.
            Box(Modifier.fillMaxWidth().height(112.dp).clipToBounds()) {
                CompositionLocalProvider(
                    LocalDensity provides Density(
                        density.density * displayStep / 100f,
                        density.fontScale * textStep / 100f,
                    ),
                ) {
                    Preview()
                }
            }
            MockText(stringResource(R.string.settings_display_size_app_section), size = 9.sp, weight = FontWeight.SemiBold, color = colors.primary)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.surface).padding(horizontal = 8.dp, vertical = 4.dp)) {
                SizeSlider(stringResource(R.string.settings_display_size_ui), percent = display, min = DISPLAY_MIN, max = DISPLAY_MAX, target = "display slider")
                SizeSlider(stringResource(R.string.settings_display_size_text), percent = text, min = TEXT_MIN, max = TEXT_MAX, target = "text slider")
            }
        }
        // The finger on each slider's thumb, moving with it.
        SliderFingertip(t, percent = display, min = DISPLAY_MIN, max = DISPLAY_MAX, track = "display slider", from = 0.06f, to = 0.55f)
        SliderFingertip(t, percent = text, min = TEXT_MIN, max = TEXT_MAX, track = "text slider", from = 0.56f, to = 0.99f)
    }
}

/** Settings' preview: the next class, then a course with its switch on, at the density it's given. */
@Composable
private fun Preview() {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.surface).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        MockText(stringResource(R.string.widget_next_class), size = 9.sp, weight = FontWeight.SemiBold, color = colors.primary, scales = true)
        MockText(stringResource(R.string.settings_font_size_preview_course_name), size = 12.sp, weight = FontWeight.SemiBold, scales = true)
        MockText("08:10–09:00", size = 9.sp, color = colors.onSurfaceVariant, scales = true)
        HorizontalDivider(Modifier.padding(vertical = 2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MockText(stringResource(R.string.settings_font_size_preview_course_name_alt_1), Modifier.weight(1f), size = 11.sp, scales = true)
            MockSwitch(on = { 1f })
        }
    }
}

/** [percent] in the sliders' steps of 5. */
private fun step(percent: Float): Int = (percent / 5f).roundToInt() * 5

/** A slider: its label and percentage over a track whose thumb sits at [percent] of [min]..[max]. */
@Composable
private fun SizeSlider(label: String, percent: () -> Float, min: Float, max: Float, target: String) {
    val colors = MaterialTheme.colorScheme
    // The percentage in steps of 5, so the label changes as the slider's would, not every frame.
    val shown by remember(percent) { derivedStateOf { step(percent()) } }
    Column(Modifier.padding(vertical = 3.dp)) {
        Row {
            MockText(label, Modifier.weight(1f), size = 10.sp)
            MockText("$shown%", size = 10.sp, color = colors.onSurfaceVariant)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .tapTarget(target)
                .drawBehind {
                    val y = size.height / 2
                    val track = 4.dp.toPx()
                    val at = ((percent() - min) / (max - min)).coerceIn(0f, 1f) * size.width
                    drawRoundRect(colors.surfaceContainerHighest, topLeft = Offset(0f, y - track / 2), size = Size(size.width, track), cornerRadius = CornerRadius(track / 2))
                    drawRoundRect(colors.primary, topLeft = Offset(0f, y - track / 2), size = Size(at, track), cornerRadius = CornerRadius(track / 2))
                    drawCircle(colors.primary, radius = 6.dp.toPx(), center = Offset(at, y))
                },
        )
    }
}

/** The finger on a slider's thumb, at [y], while [from]..[to] of the loop. */
@Composable
private fun SliderFingertip(t: () -> Float, percent: () -> Float, min: Float, max: Float, track: String, from: Float, to: Float) {
    val color = MaterialTheme.colorScheme.onSurface
    val targets = LocalTapTargets.current
    // The track runs across the card: 18dp in from each side of the screen.
    val trackWidth = MockScreenWidth - 36.dp
    var here by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Box(
        Modifier
            .onPlaced { here = it }
            .size(28.dp)
            .graphicsLayer {
                // Measured each frame, from where the fingertip is placed: the screen may be sliding in.
                val center = targets?.center(track, from = here) ?: Offset(-1_000f, -1_000f)
                val x = center.x + trackWidth.toPx() * (((percent() - min) / (max - min)).coerceIn(0f, 1f) - 0.5f)
                translationX = x - 14.dp.toPx()
                translationY = center.y - 14.dp.toPx()
                alpha = window(t(), from, to) * 0.9f
            }
            .clip(CircleShape)
            .background(color.copy(alpha = 0.3f)),
    )
}
