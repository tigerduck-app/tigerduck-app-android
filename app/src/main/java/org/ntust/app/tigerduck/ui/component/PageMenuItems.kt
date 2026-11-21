package org.ntust.app.tigerduck.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.theme.tigerDuckSwitchColors

/**
 * The app's pages that have settings of their own, as iOS's `PageSettings` names them: each has a
 * page in Settings, which its ⋮ opens too. The browser's has no ⋮ to open it.
 */
enum class PageSettings { HOME, CLASS_TABLE, BULLETINS, MAIL, LIBRARY, BROWSER, MOODLE }

/** Opens a page's settings. The app's navigation provides it; outside it, nothing opens. */
val LocalOpenPageSettings = staticCompositionLocalOf<(PageSettings) -> Unit> { {} }

/**
 * The ⋮ item that opens [page]'s settings, pushed like every settings page. It closes the menu
 * first, which would otherwise still be open over the page on the way back.
 */
@Composable
fun PageSettingsItem(page: PageSettings, close: () -> Unit) {
    val open = LocalOpenPageSettings.current
    DropdownMenuItem(
        text = { Text(stringResource(R.string.action_settings)) },
        leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
        onClick = {
            close()
            open(page)
        },
    )
}

/**
 * A switch in a page's ⋮, for a setting whose row elsewhere writes the same: the whole item is the
 * switch, so TalkBack reads its label with its state and a tap anywhere on it turns it. The caller
 * closes the menu with the pick, as an iOS menu closes.
 */
@Composable
fun PageMenuSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = {
            // The item handles it; a second target would be read on its own.
            Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = tigerDuckSwitchColors())
        },
        onClick = { onCheckedChange(!checked) },
        enabled = enabled,
        modifier = Modifier.semantics {
            role = Role.Switch
            toggleableState = ToggleableState(checked)
        },
    )
}
