package org.ntust.app.tigerduck.ui.navigation

import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import org.ntust.app.tigerduck.ui.openLink
import org.ntust.app.tigerduck.ui.screen.browser.InAppBrowser
import org.ntust.app.tigerduck.ui.screen.emptyclassroom.EmptyClassroomScreen
import org.ntust.app.tigerduck.ui.screen.informationsystem.InformationSystemScreen
import org.ntust.app.tigerduck.ui.screen.score.ScoreScreen

/**
 * The pages that read the school account's own data: signed out, each shows the lock, whose tap
 * goes to [onOpenSignInSettings].
 */
internal fun NavGraphBuilder.schoolAccountPages(browserPreference: () -> String, onOpenSignInSettings: () -> Unit) {
    page(Screen.Score.route) {
        ScoreScreen(onOpenSignInSettings = onOpenSignInSettings)
    }
    page(Screen.InformationSystem.route) {
        val context = LocalContext.current
        InformationSystemScreen(
            // In the TigerDuck browser, which signs each school site in; elsewhere as any link
            // when it cannot open.
            onOpenLink = { _, url ->
                if (!InAppBrowser.open(context, url)) openLink(context, url, browserPreference())
            },
            onOpenSignInSettings = onOpenSignInSettings,
        )
    }
    page(Screen.EmptyClassroom.route) {
        EmptyClassroomScreen(onOpenSignInSettings = onOpenSignInSettings)
    }
}
