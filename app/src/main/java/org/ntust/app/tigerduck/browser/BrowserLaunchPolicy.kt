package org.ntust.app.tigerduck.browser

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.ntust.app.tigerduck.browser.sites.BrowserSchoolSites

/** The restriction belongs to a browser launch; see [allowlist]. */
object BrowserLaunchPolicy {
    val ssoHosts = setOf("ssoam.ntust.edu.tw", "ssoam2.ntust.edu.tw")

    /**
     * The school's hosts, which every launch in the app's own profile lets in: every host under
     * `ntust.edu.tw` ([BrowserAllowlist.DEFAULT_ENTRIES]) and the domain itself, as the school sites'
     * sign-in takes them, SSO's, and those of the sites the browser knows ([BrowserSchoolSites]),
     * Moodle's among them.
     */
    val schoolEntries: List<AllowlistEntry> = (
        BrowserAllowlist.DEFAULT_ENTRIES +
            (listOf(SCHOOL_DOMAIN) + ssoHosts + BrowserSchoolSites.all.flatMap { it.hosts }).map { AllowlistEntry.Exact(it) }
        ).distinct()

    /** A launch signed in to nothing of the app's, the links browser's: every HTTPS page loads, and HTTP pages ask. */
    val ANY_HTTPS_PAGE = BrowserAllowlist(emptyList(), anyHttpsPage = true)

    /**
     * The restriction of a launch in the app's own profile, which holds the student's school sessions.
     * With none [requested] (Information System's), only the school's pages ([schoolEntries]) and the
     * [firstPage] itself load: another site's page there could send the school's sites requests with the
     * student's cookies. Any other page asks first, then opens as "Open links with" says. A [requested]
     * one gains the first page's host and, where the browser signs in, SSO's.
     */
    fun allowlist(firstPage: String, requested: BrowserAllowlist? = null, signsIn: Boolean = true): BrowserAllowlist {
        val first = firstPage.toHttpUrlOrNull()
        if (requested == null) return BrowserAllowlist(schoolEntries, firstPage = first?.toString())
        val firstHost = first?.host?.lowercase()
        val added = listOfNotNull(firstHost) + if (signsIn) ssoHosts else emptySet()
        return BrowserAllowlist(
            entries = (requested.entries + added.map { AllowlistEntry.Exact(it) }).distinct(),
            allowEverySite = requested.allowEverySite,
            anyPage = requested.anyPage,
            firstPage = first?.toString(),
            anyHttpsPage = requested.anyHttpsPage,
        )
    }

    private const val SCHOOL_DOMAIN = "ntust.edu.tw"
}
