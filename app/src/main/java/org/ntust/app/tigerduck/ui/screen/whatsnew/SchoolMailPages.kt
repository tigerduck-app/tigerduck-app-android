package org.ntust.app.tigerduck.ui.screen.whatsnew

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mail
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.ntust.app.tigerduck.data.model.AppFeature

/**
 * "School Mail is here!" — introduces School Mail in 2.3.0; it shipped in
 * 2.2.0, but few noticed. Says "2.2.0" rather than "the last version"
 * because someone upgrading from further back sees this page too.
 */
internal fun schoolMailPage(tabs: () -> List<AppFeature>) = WhatsNewPage.Feature(
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
    isApplicable = { offersMailPages(tabs()) },
)

/**
 * "Change the bottom bar to the recommended arrangement?" — offers Mail a
 * place in the bottom bar: in Calendar's slot when the bar has Calendar,
 * otherwise appended when there's room. Skipped when Mail is already there,
 * the bar is full without Calendar, or the question [wasAsked] before.
 * Confirm writes the new bar through [setTabs], the same path the tab editor
 * takes; either answer [marks it asked][markAsked].
 *
 * 2.3.0 asked it, and 2.4.0 asks it again of whoever it never reached; a run
 * with both releases shows it once ([WhatsNewFlow.from]).
 */
internal fun mailBottomBarPage(
    tabs: () -> List<AppFeature>,
    setTabs: (List<AppFeature>) -> Unit,
    markAsked: () -> Unit,
    wasAsked: () -> Boolean = { false },
) = WhatsNewPage.OptIn(
    id = "mail-bottom-bar",
    visual = WhatsNewVisual.Custom { MailBottomBarDemo(tabs) },
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
        markAsked()
        recommendedTabsWithMail(tabs())?.let(setTabs)
    },
    onDecline = markAsked,
    isApplicable = { !wasAsked() && recommendedTabsWithMail(tabs()) != null },
)

/**
 * Whether the install last showed What's New for 2.3.0 or 2.3.1 (versionCodes 28 and 29), whose
 * run asked the Mail question: it counts as asked whatever the answer, or the bar since.
 */
internal fun ranRelease23x(lastSeen: Int): Boolean = lastSeen in 28..29

/**
 * The Mail pages — the introduction and the bottom-bar question — are only
 * for a bar without Mail; someone who already put it there has found it.
 */
internal fun offersMailPages(configured: List<AppFeature>): Boolean =
    AppFeature.SCHOOL_MAIL !in configured

/**
 * The bar with Mail added: Calendar's slot taken over when [visible] has
 * Calendar, otherwise Mail appended when there's room. Null when Mail is
 * already there or there's nowhere to put it.
 */
internal fun recommendedTabsWithMail(visible: List<AppFeature>): List<AppFeature>? = when {
    !AppFeature.SCHOOL_MAIL.isImplemented || AppFeature.SCHOOL_MAIL in visible -> null
    AppFeature.CALENDAR in visible ->
        visible.map { if (it == AppFeature.CALENDAR) AppFeature.SCHOOL_MAIL else it }
    visible.size < AppFeature.MAX_CUSTOM_TABS -> visible + AppFeature.SCHOOL_MAIL
    else -> null
}

/** The user's bar above the one with Mail; captured once, so confirming doesn't redraw it mid-slide. */
@Composable
private fun MailBottomBarDemo(tabs: () -> List<AppFeature>) {
    val current = remember { tabs() }
    BottomBarChangeDemo(before = current, after = recommendedTabsWithMail(current) ?: current)
}
