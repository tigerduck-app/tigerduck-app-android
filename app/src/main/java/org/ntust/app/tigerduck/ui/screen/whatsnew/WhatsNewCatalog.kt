package org.ntust.app.tigerduck.ui.screen.whatsnew

import org.ntust.app.tigerduck.ui.AppState

/**
 * Feature pages, keyed by the versionCode that introduced them. The summary
 * page that follows them comes from `assets/whatsnew.json`, not from here.
 *
 * Add a release's pages under its versionCode, in the order they should
 * appear. Copy goes through `app-translation` like any other string. A user
 * who skipped versions sees every skipped version's pages, oldest first, so
 * keep each page meaningful on its own. For example:
 *
 * ```
 * 28 to listOf(
 *     WhatsNewPage.OptIn(
 *         id = "recommended-tabs",
 *         visual = WhatsNewVisual.Custom { animate -> TabSwapDemo(animate) },
 *         title = WhatsNewText.Res(R.string.whats_new_tabs_title),
 *         body = WhatsNewText.Res(R.string.whats_new_tabs_body),
 *         confirmLabel = WhatsNewText.Res(R.string.whats_new_tabs_confirm),
 *         declineLabel = WhatsNewText.Res(R.string.whats_new_tabs_decline),
 *         apply = { appState.applyRecommendedTabs() },
 *         isApplicable = { !appState.hasRecommendedTabs },
 *     ),
 * )
 * ```
 */
object WhatsNewCatalog {
    fun pages(@Suppress("UNUSED_PARAMETER") appState: AppState): Map<Int, List<WhatsNewPage>> =
        emptyMap()
}
