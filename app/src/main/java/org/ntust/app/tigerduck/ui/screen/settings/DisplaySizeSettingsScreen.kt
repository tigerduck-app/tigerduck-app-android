package org.ntust.app.tigerduck.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.preferences.DisplayScale
import org.ntust.app.tigerduck.ui.AppScale
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.NoTopBarInsets
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.haptics.Haptics
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

/**
 * 顯示與文字大小: the app's display size and text size, the widgets' two,
 * and the class table's course name size, whose page it opens. The app's
 * sizes apply when a slider is let go, as a new Activity; until then the
 * preview at the top draws at the size the slider is on.
 */
@Composable
fun DisplaySizeSettingsScreen(
    onBack: () -> Unit,
    onNavigateToCourseNameSize: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val appState = viewModel.appState
    DisplaySizeContent(
        picked = DisplayScale.entries.associateWith(appState::displayScale),
        courseNameScale = appState.courseNameScale,
        onCommit = appState::setDisplayScale,
        onStep = { Haptics.sliderStep(context) },
        onBack = onBack,
        onNavigateToCourseNameSize = onNavigateToCourseNameSize,
    )
}

/**
 * [DisplaySizeSettingsScreen] without its view model. [picked] is each size
 * as stored, [onCommit] stores one, and [onStep] runs each time a slider
 * moves onto another step.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DisplaySizeContent(
    picked: Map<DisplayScale, Float>,
    courseNameScale: Float,
    onCommit: (DisplayScale, Float) -> Unit,
    onStep: () -> Unit,
    onBack: () -> Unit,
    onNavigateToCourseNameSize: () -> Unit,
) {
    // Where each slider is while it is dragged; a size is stored only when
    // it is let go, which for the app's makes a new Activity.
    val dragged = remember(picked) { mutableStateMapOf<DisplayScale, Float>().apply { putAll(picked) } }
    fun current(scale: DisplayScale) = dragged[scale] ?: DisplayScale.DEFAULT

    @Composable
    fun slider(scale: DisplayScale, label: String) = ScaleSlider(
        label = label,
        scale = scale,
        value = current(scale),
        onChange = {
            dragged[scale] = it
            onStep()
        },
        onFinished = { onCommit(scale, current(scale)) },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = NoTopBarInsets,
                title = { Text(stringResource(R.string.settings_display_size_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                expandedHeight = SubSettingsBarHeight,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        ) {
            item { SectionHeader(stringResource(R.string.settings_font_size_preview_section)) }
            item {
                // The application's context keeps the phone's own sizes, which
                // the Activity's are laid over.
                val phone = LocalContext.current.applicationContext.resources
                val preview = previewDensity(
                    systemDensity = phone.displayMetrics.density,
                    systemFontScale = phone.configuration.fontScale,
                    ui = current(DisplayScale.AppUi),
                    text = current(DisplayScale.AppText),
                )
                ContentCard {
                    CompositionLocalProvider(LocalDensity provides preview) {
                        DisplaySizePreview()
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.settings_display_size_app_section)) }
            item {
                ContentCard {
                    Column {
                        slider(DisplayScale.AppUi, stringResource(R.string.settings_display_size_ui))
                        HorizontalDivider()
                        slider(DisplayScale.AppText, stringResource(R.string.settings_display_size_text))
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.settings_display_size_widget_section)) }
            item {
                ContentCard {
                    Column {
                        slider(DisplayScale.WidgetUi, stringResource(R.string.settings_display_size_ui))
                        HorizontalDivider()
                        slider(DisplayScale.WidgetText, stringResource(R.string.settings_display_size_text))
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.feature_class_table)) }
            item {
                ContentCard {
                    SettingsLinkRowWithValue(
                        label = stringResource(R.string.settings_font_size_title),
                        value = scaleLabel(courseNameScale),
                        onClick = onNavigateToCourseNameSize,
                    )
                }
            }
            item { Footnote(stringResource(R.string.settings_font_size_summary)) }

            item { Spacer(Modifier.height(16.dp)) }
            item {
                val allDefault = DisplayScale.entries.all { current(it) == DisplayScale.DEFAULT }
                ContentCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = SettingRowHeight)
                            .clickable(enabled = !allDefault) {
                                DisplayScale.entries.forEach {
                                    dragged[it] = DisplayScale.DEFAULT
                                    onCommit(it, DisplayScale.DEFAULT)
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            stringResource(R.string.settings_font_size_reset_button),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                                .copy(alpha = if (allDefault) ContentAlpha.DISABLED else 1f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * One size's slider over its steps: [label] and the size as a percentage
 * above it, the smaller and larger ends at its sides. TalkBack reads the
 * percentage, not the step's position.
 */
@Composable
private fun ScaleSlider(
    label: String,
    scale: DisplayScale,
    value: Float,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    val percent = percentLabel(value, LocalConfiguration.current.locales[0])
    val secondary = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(percent, style = MaterialTheme.typography.bodySmall, color = secondary)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ZoomOut, contentDescription = null, tint = secondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Slider(
                value = scale.stepIndex(value).toFloat(),
                onValueChange = { position ->
                    val step = scale.steps[position.roundToInt().coerceIn(0, scale.steps.lastIndex)]
                    if (step != value) onChange(step)
                },
                onValueChangeFinished = onFinished,
                valueRange = 0f..scale.steps.lastIndex.toFloat(),
                // The values between the two ends.
                steps = scale.steps.size - 2,
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        contentDescription = label
                        stateDescription = percent
                    },
            )
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Outlined.ZoomIn, contentDescription = null, tint = secondary, modifier = Modifier.size(24.dp))
        }
    }
}

/** A sample of what the app draws, a class and a switch, for the sizes' preview. */
@Composable
private fun DisplaySizePreview() {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            stringResource(R.string.widget_next_class),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.settings_font_size_preview_course_name),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "08:10–09:00",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
        )
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SettingRowHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.settings_font_size_preview_course_name_alt_1),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = true, onCheckedChange = null)
        }
    }
}

@Composable
private fun Footnote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
        textAlign = TextAlign.Start,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
}

/** [scale] as a percentage in [locale]'s way of writing one: 100%, 100 %, %100. */
internal fun percentLabel(scale: Float, locale: Locale): String =
    NumberFormat.getPercentInstance(locale).format(scale.toDouble())

/**
 * The density the preview draws at for the [ui] and [text] sizes a slider is
 * on: over the phone's own, [systemDensity] and [systemFontScale], as the
 * app's configuration will be, the font scale capped as [AppScale.fontScale]
 * caps it.
 */
internal fun previewDensity(systemDensity: Float, systemFontScale: Float, ui: Float, text: Float): Density =
    Density(systemDensity * ui, AppScale(ui, text).fontScale(systemFontScale))
