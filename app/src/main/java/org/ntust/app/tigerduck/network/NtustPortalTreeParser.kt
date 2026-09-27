package org.ntust.app.tigerduck.network

import org.jsoup.Jsoup
import org.ntust.app.tigerduck.network.model.PortalCategory
import org.ntust.app.tigerduck.network.model.PortalLink

/**
 * Parses NTUST's own student-portal page (`i.ntust.edu.tw/student`, or its
 * `/EN/student` variant) into a flat list of [PortalLink]s. The page renders
 * a `<div id="service">` whose direct children are one `<div id="service-N">`
 * per category, each containing the category's `<a>` links.
 */
object NtustPortalTreeParser {
    fun parse(html: String, baseUrl: String): List<PortalLink> {
        val doc = runCatching { Jsoup.parse(html, baseUrl) }.getOrNull() ?: return emptyList()
        val serviceDiv = doc.selectFirst("#service") ?: return emptyList()
        return serviceDiv.children()
            .filter { it.id() != "commonly-used-service" }
            .flatMap { categoryDiv ->
                val category = PortalCategory.fromServiceId(categoryDiv.id()) ?: return@flatMap emptyList()
                categoryDiv.select("a[href]").mapNotNull { a ->
                    val href = a.attr("abs:href")
                    val name = a.text().trim()
                    if (href.isBlank() || name.isBlank()) null else PortalLink(category, name, href)
                }
            }
    }
}
