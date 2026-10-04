package org.ntust.app.tigerduck.ui.screen.whatsnew

import org.ntust.app.tigerduck.ui.AppState

/**
 * Feature pages, keyed by the versionCode that introduced them. The summary
 * page that follows them comes from `assets/whatsnew.json`, not from here.
 *
 * Add a release's pages under its versionCode, in the order they should
 * appear. Copy is a [WhatsNewText] — Traditional Chinese and English written
 * inline, like the summary JSON, not `app-translation` strings. A user who
 * skipped versions sees every skipped version's pages, oldest first, so keep
 * each page meaningful on its own. A page that's more than a few lines (its
 * own demo, say) goes in a file of its own, as [resetBottomBarPage] does.
 */
object WhatsNewCatalog {
    fun pages(appState: AppState): Map<Int, List<WhatsNewPage>> = mapOf(
        28 to listOf(
            resetBottomBarPage(appState),
        ),
    )
}
