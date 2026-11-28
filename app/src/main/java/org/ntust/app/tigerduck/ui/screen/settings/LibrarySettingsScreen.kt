package org.ntust.app.tigerduck.ui.screen.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.PageScaffold
import org.ntust.app.tigerduck.ui.component.PageSettings
import org.ntust.app.tigerduck.ui.navigation.FlipToLibraryGate

/**
 * The library's page of Settings: flip-to-library, on a phone that can sense the flip, which
 * reads off and says to sign in while no library account is. Elsewhere the page is hidden, and
 * closes ([PageSettingsScreen]). The library's sign-out is the Account section's, and its ⋮'s.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibrarySettingsScreen(
    state: SettingsState,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    PageScaffold(
        title = stringResource(PageSettings.LIBRARY.title),
        onBack = onBack,
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        ) {
            if (SettingsRow.FLIP_TO_LIBRARY.isShown(state)) {
                item {
                    ContentCard {
                        SettingsToggleRow(
                            label = SettingsRow.FLIP_TO_LIBRARY.label(),
                            checked = FlipToLibraryGate.switchReadsOn(
                                enabled = viewModel.appState.flipToLibraryEnabled,
                                signedInToLibrary = state.librarySignedIn,
                            ),
                            closedReason = SettingsRow.FLIP_TO_LIBRARY.closedText(state),
                            onCheckedChange = { viewModel.appState.setFlipToLibraryFromSettings(it) },
                        )
                    }
                }
                // As iOS's footer: what the flip does, and that it rests while the library is closed.
                item {
                    PageFootnote(
                        stringResource(R.string.settings_flip_to_library_summary) + "\n" +
                            stringResource(R.string.settings_flip_to_library_closed_note),
                    )
                }
            }
        }
    }
}
