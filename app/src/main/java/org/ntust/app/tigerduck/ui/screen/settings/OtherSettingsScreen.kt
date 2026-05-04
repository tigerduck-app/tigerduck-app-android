package org.ntust.app.tigerduck.ui.screen.settings

import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.ui.component.ContentCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtherSettingsScreen(
    onBack: () -> Unit,
    onNavigateToNotificationSetup: () -> Unit,
    onNavigateToSourceCode: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val invertSlider = viewModel.appState.invertSliderDirection
    val libraryEnabled = viewModel.appState.libraryFeatureEnabled
    val themeMode = viewModel.appState.themeMode
    val browserPreference = viewModel.appState.browserPreference
    val rotationMode = viewModel.appState.rotationMode

    var showLibraryWarning by remember { mutableStateOf(false) }
    var showResetColorsConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_section_other_settings)) },
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
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        ) {
            item {
                ContentCard {
                    SettingsToggleRow(
                        stringResource(R.string.settings_library_related_features),
                        libraryEnabled,
                    ) { enabled ->
                        if (enabled) {
                            showLibraryWarning = true
                        } else {
                            viewModel.appState.libraryFeatureEnabled = false
                            viewModel.appState.configuredTabs =
                                viewModel.appState.configuredTabs.filter { !it.isLibraryRelated }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }

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
                    Column {
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
                        HorizontalDivider()
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
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }

            item {
                ContentCard {
                    SettingsLinkRow(stringResource(R.string.notification_setup_title)) {
                        onNavigateToNotificationSetup()
                    }
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
                    Column {
                        SettingsLinkRow(stringResource(R.string.settings_feedback_bug_report)) {
                            openUrl(
                                context,
                                "https://github.com/tigerduck-app/tigerduck-app-android/issues",
                                browserPreference,
                            )
                        }
                        HorizontalDivider()
                        SettingsLinkRow(stringResource(R.string.settings_privacy_policy)) {
                            openUrl(
                                context,
                                "https://app.ntust.org/tigerduck/privacy",
                                browserPreference,
                            )
                        }
                        HorizontalDivider()
                        SettingsLinkRow(stringResource(R.string.settings_open_source_licenses)) {
                            openUrl(
                                context,
                                "https://github.com/tigerduck-app/tigerduck-app-android/blob/main/LICENSE",
                                browserPreference,
                            )
                        }
                        HorizontalDivider()
                        SettingsLinkRow(stringResource(R.string.settings_view_source_code)) {
                            onNavigateToSourceCode()
                        }
                    }
                }
            }
        }
    }

    if (showLibraryWarning) {
        LibraryWarningDialog(
            onConfirm = {
                viewModel.appState.libraryFeatureEnabled = true
                if (!viewModel.appState.configuredTabs.contains(AppFeature.LIBRARY) &&
                    viewModel.appState.configuredTabs.size < 4
                ) {
                    viewModel.appState.configuredTabs =
                        viewModel.appState.configuredTabs + AppFeature.LIBRARY
                }
                showLibraryWarning = false
            },
            onDismiss = { showLibraryWarning = false },
        )
    }

    if (showResetColorsConfirm) {
        AlertDialog(
            onDismissRequest = { showResetColorsConfirm = false },
            title = { Text(stringResource(R.string.settings_reset_course_colors_confirm_title)) },
            text = { Text(stringResource(R.string.settings_reset_course_colors_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetCourseColors()
                    showResetColorsConfirm = false
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetColorsConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

private fun openUrl(context: Context, url: String, browserPreference: String) {
    val uri = url.toUri()
    if (browserPreference == "inApp") {
        CustomTabsIntent.Builder().build().launchUrl(context, uri)
    } else {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }
}
