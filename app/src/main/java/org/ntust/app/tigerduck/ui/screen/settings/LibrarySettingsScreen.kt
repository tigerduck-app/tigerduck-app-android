package org.ntust.app.tigerduck.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.sensor.FlipDetector
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.NoTopBarInsets

/**
 * 圖書館及相關功能: the library feature switch and, while it is on,
 * flip-to-library under it. Settings' 其他設定 section links here and to
 * [OtherSettingsScreen].
 *
 * Turning the feature on goes through [LibraryWarningDialog] first; turning
 * it off also drops the library tabs from the bottom bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val libraryEnabled = viewModel.appState.libraryFeatureEnabled
    val flipSensorSupported = remember(context) { FlipDetector.isSupported(context) }
    var showLibraryWarning by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = NoTopBarInsets,
                title = { Text(stringResource(R.string.settings_library_related_features)) },
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
                    Column {
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
                        if (libraryEnabled) {
                            HorizontalDivider()
                            SettingsToggleRow(
                                label = stringResource(R.string.settings_flip_to_library_title),
                                checked = viewModel.appState.flipToLibraryEnabled && flipSensorSupported,
                                enabled = flipSensorSupported,
                                subtitle = if (flipSensorSupported) {
                                    stringResource(R.string.settings_flip_to_library_summary)
                                } else {
                                    stringResource(R.string.settings_flip_to_library_unsupported)
                                },
                                onCheckedChange = { viewModel.appState.flipToLibraryEnabled = it },
                            )
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
}
