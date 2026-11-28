package org.ntust.app.tigerduck.ui.screen.settings

import java.text.Normalizer

/** A row search found, named by its section or page and its title: `Page › Setting`, or `Section › Sub-page › Setting`. */
internal data class SettingsSearchResult(val row: SettingsRow, val path: List<String>) {
    val text: String get() = path.joinToString(" › ")
}

/** Settings' search, as iOS's `SettingsSearch`: [results] reads the catalog's titles through `label`. */
internal object SettingsSearch {
    /**
     * The shown rows whose title or section or page name holds every word of [query], ignoring
     * case and character width. A blank query finds none.
     */
    fun results(query: String, state: SettingsState, label: (Int) -> String): List<SettingsSearchResult> {
        val words = folded(query).split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return SettingsRow.entries.mapNotNull { row ->
            if (!row.isShown(state)) return@mapNotNull null
            val path = path(row, label)
            val names = path.map(::folded)
            if (words.all { word -> names.any { word in it } }) SettingsSearchResult(row, path) else null
        }
    }

    /**
     * The row's section or page, then its title; for a row on a sub-page, its section, the
     * sub-page, then its title. A name the one before it reads the same as is said once, as
     * TigerSync's section and its one row, or Other settings and its sub-page.
     */
    fun path(row: SettingsRow, label: (Int) -> String): List<String> {
        val place = row.place
        val names = if (place is SettingsPlace.SubPage) listOf(place.subPage.section.title, place.title) else listOf(place.title)
        return (names + row.title).map(label).fold(emptyList()) { said, name -> if (said.lastOrNull() == name) said else said + name }
    }

    private val WHITESPACE = Regex("\\s+")

    /**
     * [text] as the search compares it, as School Mail's recipient search does: NFKC, which turns
     * what a Chinese keyboard types in full-width mode (`ＡＰＩ`, its wide space) into its ASCII
     * form, then each character lowercased through its uppercase.
     */
    private fun folded(text: String): String = buildString {
        Normalizer.normalize(text, Normalizer.Form.NFKC).forEach { append(it.uppercaseChar().lowercaseChar()) }
    }
}
