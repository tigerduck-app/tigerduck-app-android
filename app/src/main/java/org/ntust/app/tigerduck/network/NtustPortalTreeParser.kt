package org.ntust.app.tigerduck.network

import org.jsoup.Jsoup
import org.ntust.app.tigerduck.network.model.PortalCategory
import org.ntust.app.tigerduck.network.model.PortalLink
import java.net.URI

/**
 * Parses NTUST's own student-portal page (`i.ntust.edu.tw/student`, or its
 * `/EN/student` variant) into a flat list of [PortalLink]s. The page renders
 * a `<div id="service">` whose direct children are one `<div id="service-N">`
 * per category, each containing the category's `<a>` links — the same shape
 * TAT's `NTUSTConnector.getSubSystem()` scrapes on the Flutter side.
 */
object NtustPortalTreeParser {
    /**
     * The portal's own "學術研究倫理課程報名" (Research Ethics Course Registration) link still
     * points at this host, but its server presents a TLS certificate for `*.cge.ntust.edu.tw`
     * instead — the SAN list doesn't cover [STALE_HOST] itself, so hostname verification
     * correctly rejects it on every attempt (SSLPeerUnverifiedException). Confirmed live: both
     * hostnames resolve to the same IP, so the service itself moved to [CORRECT_HOST] and this
     * is a stale link on NTUST's own page, not a TLS problem we could fix by adding a pin.
     * NTUST is not going to retire this link on our schedule, so links to it are corrected here
     * rather than left to fail every time a student taps it.
     */
    private const val STALE_HOST = "ae.ntust.edu.tw"
    private const val CORRECT_HOST = "ae.cge.ntust.edu.tw"

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
                    if (href.isBlank() || name.isBlank()) null else PortalLink(category, name, fixStaleHost(href))
                }
            }
    }

    /** Swaps only the host. Rebuilt from the raw (still-encoded) components: the decoded
     *  getters would turn an escaped `%26` or `%2F` into a real query separator or path
     *  segment, changing which resource the link opens. */
    private fun fixStaleHost(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        if (uri.host != STALE_HOST) return url
        return buildString {
            append(uri.scheme).append("://")
            uri.rawUserInfo?.let { append(it).append('@') }
            append(CORRECT_HOST)
            if (uri.port != -1) append(':').append(uri.port)
            uri.rawPath?.let { append(it) }
            uri.rawQuery?.let { append('?').append(it) }
            uri.rawFragment?.let { append('#').append(it) }
        }
    }
}
