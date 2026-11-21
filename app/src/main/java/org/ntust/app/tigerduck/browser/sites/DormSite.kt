package org.ntust.app.tigerduck.browser.sites

import okhttp3.HttpUrl

object DormSite : SchoolSite {
    override val hosts = setOf("sa.ntust.edu.tw")
    override val logoutRules = listOf(SiteLogoutRule("/Dorm/OAuth2/Logout"))
    override val logoutSelectors = logoutRules.flatMap { it.selectors(hosts) }

    override val signInLinkPage = "/dorm/"

    override fun signInLink(page: HttpUrl): String? {
        if (page.encodedPath.lowercase() != signInLinkPage) return null
        return "https://sa.ntust.edu.tw/Dorm/OAuth2/Login"
    }
}
