package org.ntust.app.tigerduck.ui.screen.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.sensor.FlipDetector
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.NoTopBarInsets
import org.ntust.app.tigerduck.ui.navigation.FlipToLibraryGate

/**
 * 圖書館及相關功能: flip-to-library. Settings' 其他設定 section links here,
 * on a phone that can sense the flip, and to [OtherSettingsScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val flipSensorSupported = remember(context) { FlipDetector.isSupported(context) }
    val signedIn by viewModel.isLibraryLoggedIn.collectAsStateWithLifecycle()

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
                    SettingsToggleRow(
                        label = stringResource(R.string.settings_flip_to_library_title),
                        checked = flipSensorSupported && FlipToLibraryGate.switchReadsOn(
                            enabled = viewModel.appState.flipToLibraryEnabled,
                            signedInToLibrary = signedIn,
                        ),
                        enabled = flipSensorSupported && FlipToLibraryGate.switchTakesInput(signedIn),
                        subtitle = if (flipSensorSupported) {
                            stringResource(R.string.settings_flip_to_library_summary)
                        } else {
                            stringResource(R.string.settings_flip_to_library_unsupported)
                        },
                        disabledStateDescription = if (flipSensorSupported) {
                            stringResource(R.string.library_status_not_signed_in)
                        } else {
                            null
                        },
                        onCheckedChange = { viewModel.appState.setFlipToLibraryFromSettings(it) },
                    )
                }
            }
        }
    }
}
