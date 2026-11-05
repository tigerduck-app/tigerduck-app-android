package org.ntust.app.tigerduck.ui.screen.coursemoodle

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.component.PageMenu
import org.ntust.app.tigerduck.ui.component.PageScaffold
import org.ntust.app.tigerduck.ui.component.PageScope
import org.ntust.app.tigerduck.ui.component.PageSettings
import org.ntust.app.tigerduck.ui.component.PageSettingsItem

/**
 * The shell of one of a course's Moodle lists, as iOS's `moodleListChrome`: its title on one line,
 * the way back, "Open in Moodle" for [moodlePath] ([OpenInMoodleAction]) and a ⋮ with Settings,
 * where the Moodle rows sit.
 */
@Composable
internal fun MoodleListScaffold(
    title: String,
    onBack: () -> Unit,
    moodlePath: String?,
    willOpenPage: () -> Unit = {},
    content: @Composable PageScope.(PaddingValues) -> Unit,
) = PageScaffold(
    title = title,
    onBack = onBack,
    oneLineTitle = true,
    actions = { OpenInMoodleAction(moodlePath, willOpenPage) },
    menu = PageMenu(label = stringResource(R.string.action_more_actions)) { close ->
        PageSettingsItem(PageSettings.MOODLE, close)
    },
    content = content,
)
