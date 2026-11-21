package org.ntust.app.tigerduck.ui.screen.coursemoodle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.preferences.PinMyselfPreference
import org.ntust.app.tigerduck.ui.component.PageMenuSwitch

/**
 * "Pin myself" in the members list's ⋮, above Settings, as iOS's `PageMenuToggle(row: .pinMyself)`
 * (tigerduck-app#1032). Settings has the same switch (`PinMyselfSetting`).
 */
@Composable
internal fun PinMyselfMenuSwitch(pin: PinMyselfPreference, close: () -> Unit) {
    val on by pin.flow.collectAsStateWithLifecycle()
    PageMenuSwitch(stringResource(R.string.course_members_pin_myself), on, onCheckedChange = {
        pin.value = it
        close()
    })
}
