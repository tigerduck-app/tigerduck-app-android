package org.ntust.app.tigerduck.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.ui.haptics.HapticScenario
import org.ntust.app.tigerduck.ui.haptics.Haptics

private val STRENGTH_PRESETS = listOf(
    R.string.haptic_preset_subtle to 30,
    R.string.haptic_preset_medium to 60,
    R.string.haptic_preset_strong to 100,
)

private val LENGTH_PRESETS = listOf(
    R.string.haptic_preset_short to 8,
    R.string.haptic_preset_medium to 20,
    R.string.haptic_preset_long to 40,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VibrationSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val appState = viewModel.appState

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.vibration_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        ) {
            item(key = "motor_warning") {
                Text(
                    text = stringResource(R.string.vibration_settings_motor_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Spacer(Modifier.height(16.dp))
            }

            HapticScenario.tunable.forEach { scenario ->
                item(key = scenario.name) {
                    ScenarioCard(
                        scenarioLabel = stringResource(scenario.labelRes),
                        strength = appState.hapticStrength(scenario),
                        durationMs = appState.hapticDurationMs(scenario),
                        onStrengthChange = { appState.setHapticStrength(scenario, it) },
                        onDurationChange = { appState.setHapticDurationMs(scenario, it) },
                        onPreview = { s, d -> Haptics.previewCustom(context, s, d) },
                        onRevert = {
                            appState.resetHapticToDefault(scenario)
                            Haptics.previewCustom(
                                context,
                                scenario.defaultStrengthPct,
                                scenario.defaultDurationMs,
                            )
                        },
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun ScenarioCard(
    scenarioLabel: String,
    strength: Int,
    durationMs: Int,
    onStrengthChange: (Int) -> Unit,
    onDurationChange: (Int) -> Unit,
    onPreview: (Int, Int) -> Unit,
    onRevert: () -> Unit,
) {
    var liveStrength by remember(strength) { mutableIntStateOf(strength) }
    var liveDuration by remember(durationMs) { mutableIntStateOf(durationMs) }

    org.ntust.app.tigerduck.ui.component.ContentCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    scenarioLabel,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRevert) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.vibration_settings_revert))
                }
            }

            SliderSection(
                label = stringResource(R.string.vibration_settings_section_strength_pct),
                value = liveStrength,
                valueRange = 0f..100f,
                steps = 19,
                valueLabel = "$liveStrength",
                presets = STRENGTH_PRESETS,
                onPresetTap = { presetValue ->
                    liveStrength = presetValue
                    onStrengthChange(presetValue)
                    onPreview(presetValue, liveDuration)
                },
                onValueChange = { liveStrength = it },
                onValueChangeFinished = {
                    onStrengthChange(liveStrength)
                    onPreview(liveStrength, liveDuration)
                },
            )

            SliderSection(
                label = stringResource(R.string.vibration_settings_section_length_ms),
                value = liveDuration,
                valueRange = AppPreferences.MIN_TUNABLE_HAPTIC_DURATION_MS.toFloat()
                        ..AppPreferences.MAX_TUNABLE_HAPTIC_DURATION_MS.toFloat(),
                steps = (AppPreferences.MAX_TUNABLE_HAPTIC_DURATION_MS
                        - AppPreferences.MIN_TUNABLE_HAPTIC_DURATION_MS) - 1,
                valueLabel = "$liveDuration ms",
                presets = LENGTH_PRESETS,
                onPresetTap = { presetValue ->
                    val clamped = presetValue.coerceIn(
                        AppPreferences.MIN_TUNABLE_HAPTIC_DURATION_MS,
                        AppPreferences.MAX_TUNABLE_HAPTIC_DURATION_MS,
                    )
                    liveDuration = clamped
                    onDurationChange(clamped)
                    onPreview(liveStrength, clamped)
                },
                onValueChange = { liveDuration = it },
                onValueChangeFinished = {
                    onDurationChange(liveDuration)
                    onPreview(liveStrength, liveDuration)
                },
            )
        }
    }
}

@Composable
private fun SliderSection(
    label: String,
    value: Int,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueLabel: String,
    presets: List<Pair<Int, Int>>,
    onPresetTap: (Int) -> Unit,
    onValueChange: (Int) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                valueLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { (labelRes, presetValue) ->
                AssistChip(
                    onClick = { onPresetTap(presetValue) },
                    label = {
                        Text(
                            stringResource(labelRes),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
        )
    }
}
