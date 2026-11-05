package org.ntust.app.tigerduck.ui.screen.library

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.sensor.FlipDetector
import org.ntust.app.tigerduck.ui.component.PageMenu
import org.ntust.app.tigerduck.ui.component.PageMenuSwitch
import org.ntust.app.tigerduck.ui.component.PageSettings
import org.ntust.app.tigerduck.ui.component.PageSettingsItem
import org.ntust.app.tigerduck.ui.navigation.FlipToLibraryGate
import org.ntust.app.tigerduck.ui.screen.settings.SettingsViewModel

/**
 * The library's ⋮, as iOS's: Flip to show QR, Settings, then Sign out of library in red. Each is
 * what Settings does, through the view model the library's settings page and Settings' account row
 * use.
 */
@Composable
internal fun rememberLibraryMenu(settings: SettingsViewModel = hiltViewModel()): PageMenu? {
    val signedIn by settings.isLibraryLoggedIn.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val flipSupported = remember(context) { FlipDetector.isSupported(context) }
    return libraryMenu(
        signedIn = signedIn,
        flipSupported = flipSupported,
        flipOn = { FlipToLibraryGate.switchReadsOn(settings.appState.flipToLibraryEnabled, signedIn) },
        onFlip = settings.appState::setFlipToLibraryFromSettings,
        onSignOut = settings::logoutLibrary,
    )
}

/** Signed out of the library there is nothing to change, and no menu. [flipOn] is read as it opens. */
@Composable
internal fun libraryMenu(
    signedIn: Boolean,
    flipSupported: Boolean,
    flipOn: () -> Boolean,
    onFlip: (Boolean) -> Unit,
    onSignOut: () -> Unit,
): PageMenu? {
    if (!signedIn) return null
    return PageMenu(label = stringResource(R.string.action_more_actions)) { close ->
        LibraryMenuItems(flipSupported, flipOn(), onFlip, onSignOut, close)
    }
}

/**
 * The menu's items, signed in. A phone that cannot sense the flip has neither the switch nor the
 * settings page, which holds only it: Settings leaves that page's row out on such a phone too.
 */
@Composable
private fun ColumnScope.LibraryMenuItems(
    flipSupported: Boolean,
    flipOn: Boolean,
    onFlip: (Boolean) -> Unit,
    onSignOut: () -> Unit,
    close: () -> Unit,
) {
    if (flipSupported) {
        PageMenuSwitch(stringResource(R.string.settings_library_flip_to_show_qr), flipOn, onCheckedChange = {
            close()
            onFlip(it)
        })
        HorizontalDivider()
        PageSettingsItem(PageSettings.LIBRARY, close)
        HorizontalDivider()
    }
    val red = MaterialTheme.colorScheme.error
    DropdownMenuItem(
        text = { Text(stringResource(R.string.settings_library_sign_out), color = red) },
        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = red) },
        onClick = {
            close()
            onSignOut()
        },
    )
}
