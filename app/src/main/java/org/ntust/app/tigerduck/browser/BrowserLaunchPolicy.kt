package org.ntust.app.tigerduck.browser

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The restriction belongs to a browser launch; no entries supplied means every HTTPS page loads. */
object BrowserLaunchPolicy {
    val ssoHosts = setOf("ssoam.ntust.edu.tw", "ssoam2.ntust.edu.tw")

    fun allowlist(firstPage: String, requested: BrowserAllowlist? = null, signsIn: Boolean = true): BrowserAllowlist {
        if (requested == null) return BrowserAllowlist(emptyList(), anyHttpsPage = true)
        val firstHost = firstPage.toHttpUrlOrNull()?.host?.lowercase()
        val added = listOfNotNull(firstHost) + if (signsIn) ssoHosts else emptySet()
        return BrowserAllowlist(
            entries = (requested.entries + added.map { AllowlistEntry.Exact(it) }).distinct(),
            allowEverySite = requested.allowEverySite,
            anyPage = requested.anyPage,
            firstPage = firstPage.toHttpUrlOrNull()?.toString(),
            anyHttpsPage = requested.anyHttpsPage,
        )
    }
}
