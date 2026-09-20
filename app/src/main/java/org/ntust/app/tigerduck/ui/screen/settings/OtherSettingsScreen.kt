package org.ntust.app.tigerduck.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.NoTopBarInsets
import org.ntust.app.tigerduck.ui.component.TigerDuckDialog
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

/**
 * 其他設定, one card per group: 課程字體大小, 反轉滑條方向, 螢幕翻轉, 震動,
 * API 端點, 顏色主題 with 重新分配課表顏色, 使用分析. iOS has the same page
 * without 震動, 螢幕翻轉, 顏色主題 and 使用分析, which it does not offer.
 * The library switches live on [LibrarySettingsScreen], the other entry in
 * Settings' 其他設定 section, and the links out on [AboutOthersScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtherSettingsScreen(
    onBack: () -> Unit,
    onNavigateToApiEndpoint: () -> Unit,
    onNavigateToVibration: () -> Unit,
    onNavigateToCourseNameSize: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    var analyticsEnabled by remember { mutableStateOf(viewModel.prefs.analyticsEnabled) }
    val invertSlider = viewModel.appState.invertSliderDirection
    val themeMode = viewModel.appState.themeMode
    val rotationMode = viewModel.appState.rotationMode
    val courseNameScale = viewModel.appState.courseNameScale

    var showResetColorsConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = NoTopBarInsets,
                title = { Text(stringResource(R.string.settings_section_other_settings)) },
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
            item {
                ContentCard {
                    SettingsLinkRowWithValue(
                        label = stringResource(R.string.settings_font_size_title),
                        value = "%.2f×".format(courseNameScale),
                        onClick = onNavigateToCourseNameSize,
                    )
                }
            }
            item {
                Text(
                    stringResource(R.string.settings_font_size_summary),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                        .copy(alpha = ContentAlpha.SECONDARY),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }

            item { Spacer(Modifier.height(8.dp)) }

            item {
                ContentCard {
                    SettingsToggleRow(
                        stringResource(R.string.settings_invert_slider_direction),
                        invertSlider,
                    ) { viewModel.appState.invertSliderDirection = it }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }

            item {
                ContentCard {
                    val automatic = stringResource(R.string.common_automatic)
                    val enabledStr = stringResource(R.string.common_enabled)
                    val disabledStr = stringResource(R.string.common_disabled)
                    SettingsPickerRow(
                        label = stringResource(R.string.settings_screen_rotation),
                        value = when (rotationMode) {
                            "enabled" -> enabledStr
                            "disabled" -> disabledStr
                            else -> automatic
                        },
                        options = listOf(
                            "auto" to automatic,
                            "enabled" to enabledStr,
                            "disabled" to disabledStr,
                        ),
                        selectedKey = rotationMode,
                        onSelect = { viewModel.appState.rotationMode = it },
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }

            item {
                ContentCard {
                    SettingsLinkRow(stringResource(R.string.vibration_settings_title)) {
                        onNavigateToVibration()
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }

            item {
                ContentCard {
                    SettingsLinkRow(stringResource(R.string.settings_api_endpoint)) {
                        onNavigateToApiEndpoint()
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }

            item {
                ContentCard {
                    Column {
                        SettingsPickerRow(
                            label = stringResource(R.string.settings_color_theme),
                            value = when (themeMode) {
                                "dark" -> stringResource(R.string.settings_theme_dark)
                                "light" -> stringResource(R.string.settings_theme_light)
                                else -> stringResource(R.string.settings_theme_system)
                            },
                            options = listOf(
                                "system" to stringResource(R.string.settings_theme_system),
                                "dark" to stringResource(R.string.settings_theme_dark),
                                "light" to stringResource(R.string.settings_theme_light),
                            ),
                            selectedKey = themeMode,
                            onSelect = { viewModel.appState.themeMode = it },
                        )
                        HorizontalDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(SettingRowHeight)
                                .clickable { showResetColorsConfirm = true }
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.settings_reset_course_colors),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }

            item {
                ContentCard {
                    SettingsToggleRow(
                        stringResource(R.string.settings_analytics_enabled),
                        analyticsEnabled,
                    ) {
                        analyticsEnabled = it
                        viewModel.setAnalyticsEnabled(it)
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.settings_analytics_description),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                        .copy(alpha = ContentAlpha.SECONDARY),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }
    }

    if (showResetColorsConfirm) {
        TigerDuckDialog(
            onDismissRequest = { showResetColorsConfirm = false },
            title = stringResource(R.string.settings_reset_course_colors_confirm_title),
            message = stringResource(R.string.settings_reset_course_colors_confirm_message),
            confirmText = stringResource(R.string.action_confirm),
            onConfirm = {
                viewModel.resetCourseColors()
                showResetColorsConfirm = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showResetColorsConfirm = false },
        )
    }
}
