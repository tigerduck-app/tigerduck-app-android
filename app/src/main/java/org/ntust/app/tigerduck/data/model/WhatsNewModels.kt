package org.ntust.app.tigerduck.data.model

/**
 * One localized "What's new" block, deserialized by Gson from
 * `assets/whatsnew.json`.
 *
 * Fields are nullable as defensive practice for a Gson-deserialized class
 * (see CLAUDE.md). This asset ships inside the APK and always matches the app
 * version, so the upgrade-crash pattern does not apply — but [WhatsNewContent]
 * still REQUIRES a `-keep` rule in `proguard-rules.pro` so R8 does not rename
 * its fields out from under Gson (the `data.model.**` wildcard covers it).
 *
 * A block lists its rows either as [items] (headline, supporting text and an
 * icon — the summary page's row shape) or, in entries written before the
 * paged What's New, as plain [highlights] strings. [items] wins when both are
 * present.
 */
data class WhatsNewContent(
    val title: String? = null,
    val items: List<WhatsNewItemContent>? = null,
    val highlights: List<String>? = null,
)

/**
 * One summary row inside [WhatsNewContent.items]. [icon] is a name looked up
 * in `WhatsNewIcons`; an unknown or missing name falls back to a neutral
 * glyph. Either [title] or [body] may be left out, not both.
 */
data class WhatsNewItemContent(
    val title: String? = null,
    val body: String? = null,
    val icon: String? = null,
)

/**
 * The summary page as the UI renders it: a [WhatsNewContent] that survived
 * locale selection and blank-filtering, with legacy highlights folded into
 * [items].
 */
data class WhatsNewSummary(
    val versionCode: Int,
    val title: String,
    val items: List<WhatsNewSummaryItem>,
)

/**
 * A row of [WhatsNewSummary]. A legacy highlight becomes a row with only a
 * [body]: it was written as a sentence, not a headline.
 */
data class WhatsNewSummaryItem(
    val title: String?,
    val body: String?,
    val icon: String?,
)
