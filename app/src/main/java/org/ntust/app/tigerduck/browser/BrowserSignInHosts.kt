package org.ntust.app.tigerduck.browser

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.browser.sites.BrowserSchoolSites
import org.ntust.app.tigerduck.emptyclassroom.ClassroomUsageService
import org.ntust.app.tigerduck.network.NtustPortalService
import org.ntust.app.tigerduck.network.moodle.MoodleWebservice
import org.ntust.app.tigerduck.shared.LibraryApi
import org.ntust.app.tigerduck.shared.tls.pinHostName
import org.ntust.app.tigerduck.ui.screen.mail.WEBMAIL_URL

/**
 * The hosts the app signs in to, as the iPhone's `BrowserPinnedHosts` lists them
 * (tigerduck-app#958): those its own services reach, SSO among them; the school sites the browser
 * fills the stored password in, stuinfosys among them, whose scores the app reads; and those whose
 * sign-in link goes through SSO. Built from those services' and sites' own addresses. Any part of
 * an address there may carry a credential, so a page of one opened at the student's risk opens only
 * at its front page ([CertificateRefusal.pageToOpen]).
 *
 * The iPhone's browser checks TLS pins for these hosts. Android's WebView has no way to refuse a
 * chain the system trusts, so here the list decides only that.
 */
object BrowserSignInHosts {
    private val services = listOf(
        MoodleWebservice.SITE,
        "https://${NtustPortalService.PORTAL_HOST}/",
        WEBMAIL_URL,
        LibraryApi.BASE_URL,
        "https://${ClassroomUsageService.HOST}/",
        AuthService.SSO_SERVICE_URL,
    ).mapNotNull { it.toHttpUrlOrNull()?.host }

    /** The sites the browser gives the stored password to, in a form it fills. */
    private val passwordSites = BrowserSchoolSites.all.filter { site -> site.signInForms.values.any { it.password != null } }

    /** The sites whose sign-in link goes through SSO. */
    private val signInLinkSites = BrowserSchoolSites.all.filter { it.signInLinkPage != null }

    val hosts: Set<String> = (services + BrowserLaunchPolicy.ssoHosts + (passwordSites + signInLinkSites).flatMap { it.hosts })
        .map(::pinHostName)
        .toSet()

    /** Whether the app signs in to [host], matched as the pin matcher matches it. */
    operator fun contains(host: String): Boolean = pinHostName(host) in hosts
}
