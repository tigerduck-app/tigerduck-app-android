package org.ntust.app.tigerduck.ui.screen.informationsystem

import org.ntust.app.tigerduck.network.model.PortalLink
import org.ntust.app.tigerduck.util.HanScript

/** A link of the UI page with its counterpart on the other page: either side null where none pairs. */
internal data class PortalPair(val shown: PortalLink?, val other: PortalLink?)

/**
 * The Information System's search across the portal's two pages, the Chinese and the English, as
 * Add course's search crosses its two languages (AddCourseSheet): a query matches a link by either
 * page's name, a Simplified one as Traditional, as NTUST names its services in Traditional only. A
 * result shows by the UI page's name and opens the UI page's address, with the other page's name in
 * parentheses when the query's language is not the UI's.
 */
internal object PortalSearch {

    /**
     * [shown], the UI page's links, each with its counterpart among [other]: the link at the same
     * address, the two pages told apart by an `/EN` segment or a language parameter, or else, when a
     * category is left with as many links on each page, the one in the same place. A link of
     * [other] that pairs with none comes after, alone.
     */
    fun pairs(shown: List<PortalLink>, other: List<PortalLink>): List<PortalPair> {
        val left = other.toMutableList()
        // Each address read once: the search pairs again at every keystroke.
        val addresses = other.associateWith { address(it.url) }
        val byAddress = shown.map { link ->
            val key = address(link.url)
            val match = left.firstOrNull { it.category == link.category && addresses.getValue(it) == key }
            match?.let(left::remove)
            link to match
        }
        val unpaired = byAddress.filter { it.second == null }.groupBy({ it.first.category }, { it.first })
        val byPlace = mutableMapOf<PortalLink, PortalLink>()
        for ((category, links) in unpaired) {
            val others = left.filter { it.category == category }
            if (others.size != links.size) continue
            links.zip(others).forEach { (link, counterpart) -> byPlace[link] = counterpart }
            left.removeAll(others)
        }
        return byAddress.map { (link, match) -> PortalPair(link, match ?: byPlace[link]) } +
            left.map { PortalPair(null, it) }
    }

    /**
     * The links [pairs] holds that [query] finds, in order, each as the UI page has it ([uiEnglish]
     * when that is the English page), its name followed by the other page's in parentheses when the
     * query is in the other language. An empty query lists the UI page as it is.
     */
    fun search(pairs: List<PortalPair>, query: String, uiEnglish: Boolean): List<PortalLink> {
        val text = query.trim()
        if (text.isEmpty()) return pairs.mapNotNull { it.shown }
        val queryInChinese = HanScript.containsHan(text)
        val traditional = HanScript.toTraditional(text)
        val bilingual = queryInChinese == uiEnglish
        return pairs.mapNotNull { pair ->
            val chinese = if (uiEnglish) pair.other else pair.shown
            val english = if (uiEnglish) pair.shown else pair.other
            val found = chinese?.name?.let { it.contains(traditional, ignoreCase = true) || it.contains(text, ignoreCase = true) } == true ||
                english?.name?.contains(text, ignoreCase = true) == true
            if (!found) return@mapNotNull null
            val link = pair.shown ?: return@mapNotNull pair.other
            val otherName = pair.other?.name?.takeIf { bilingual && it.isNotBlank() && it != link.name }
            if (otherName == null) link else link.copy(name = "${link.name} ($otherName)")
        }
    }

    // The address both pages share for a service: no `/EN` segment, language parameter or fragment.
    private fun address(url: String): String = url.trim().lowercase()
        .substringBefore('#')
        .replace(Regex("""/en(?=/|\?|$)"""), "")
        .replace(Regex("""([?&])(lang|language|culture|locale)=[^&]*&?"""), "$1")
        .trimEnd('?', '&', '/')
}
