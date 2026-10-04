package org.ntust.app.tigerduck.ui.screen.whatsnew

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mail
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.ui.AppState

/**
 * "School Mail is here!" — introduces School Mail in 2.3.0; it shipped in
 * 2.2.0, but few noticed. Says "2.2.0" rather than "the last version"
 * because someone upgrading from further back sees this page too.
 */
internal fun schoolMailPage(appState: AppState) = WhatsNewPage.Feature(
    id = "school-mail",
    visual = WhatsNewVisual.Icon(Icons.Filled.Mail, WhatsNewEffect.Bounce),
    title = WhatsNewText(
        en = "School Mail is here!",
        zhHant = "校園信箱來了！",
    ),
    body = WhatsNewText(
        en = "Since version 2.2.0, you can read, search and send your NTUST mail right in TigerDuck.",
        zhHant = "從 2.2.0 版開始，可以直接在 TigerDuck 收發、搜尋臺科大信件。",
    ),
    isApplicable = { offersMailPages(appState.configuredTabs) },
)

/**
 * "Change the bottom bar to the recommended arrangement?" — offers Mail a
 * place in the bottom bar: in Calendar's slot when the bar has Calendar,
 * otherwise appended when there's room. Skipped when Mail is already there
 * or the bar is full without Calendar. Confirm writes the new bar through
 * [AppState.configuredTabs], the same path the tab editor takes.
 */
internal fun mailBottomBarPage(appState: AppState) = WhatsNewPage.OptIn(
    id = "mail-bottom-bar",
    visual = WhatsNewVisual.Custom { MailBottomBarDemo(appState) },
    title = WhatsNewText(
        en = "Change the bottom bar to the recommended arrangement?",
        zhHant = "要將底部功能列改為建議的排列嗎？",
    ),
    body = WhatsNewText(
        en = "Mail gets its own tab, one tap away. Anything that moves off the bar stays in More, and you can customize it again anytime in Settings.",
        zhHant = "信箱會有自己的位置，一點就到。移出功能列的項目仍可在「更多」中找到，之後也隨時可以在設定中重新自訂。",
    ),
    confirmLabel = WhatsNewText(en = "Apply Recommended Arrangement", zhHant = "套用建議排列"),
    declineLabel = WhatsNewText(en = "Keep Mine", zhHant = "保留目前設定"),
    apply = {
        recommendedTabsWithMail(appState.visibleTabs())?.let { appState.configuredTabs = it }
    },
    isApplicable = { recommendedTabsWithMail(appState.visibleTabs()) != null },
)

/**
 * The Mail pages — the introduction and the bottom-bar question — are only
 * for a bar without Mail; someone who already put it there has found it.
 * Mail is never hidden by an opt-in, so the stored list answers for the bar
 * on screen.
 */
internal fun offersMailPages(configured: List<AppFeature>): Boolean =
    AppFeature.SCHOOL_MAIL !in configured

/**
 * The bar with Mail added: Calendar's slot taken over when [visible] has
 * Calendar, otherwise Mail appended when there's room. Null when Mail is
 * already there or there's nowhere to put it.
 *
 * Works on the bar the user sees (see [visibleBottomBarTabs]): a library tab
 * hidden by the library opt-in neither takes a slot nor survives the change.
 */
internal fun recommendedTabsWithMail(visible: List<AppFeature>): List<AppFeature>? = when {
    !AppFeature.SCHOOL_MAIL.isImplemented || AppFeature.SCHOOL_MAIL in visible -> null
    AppFeature.CALENDAR in visible ->
        visible.map { if (it == AppFeature.CALENDAR) AppFeature.SCHOOL_MAIL else it }
    visible.size < AppFeature.MAX_CUSTOM_TABS -> visible + AppFeature.SCHOOL_MAIL
    else -> null
}

/**
 * The tabs the bottom bar actually shows for [configured]: library tabs
 * drop out while the library opt-in is off, as in `AppNavigation`.
 */
internal fun visibleBottomBarTabs(
    configured: List<AppFeature>,
    libraryEnabled: Boolean,
): List<AppFeature> = configured.filter { !it.isLibraryRelated || libraryEnabled }

private fun AppState.visibleTabs(): List<AppFeature> =
    visibleBottomBarTabs(configuredTabs, libraryFeatureEnabled)

/** The user's bar above the one with Mail; captured once, so confirming doesn't redraw it mid-slide. */
@Composable
private fun MailBottomBarDemo(appState: AppState) {
    val current = remember { appState.visibleTabs() }
    BottomBarChangeDemo(before = current, after = recommendedTabsWithMail(current) ?: current)
}
