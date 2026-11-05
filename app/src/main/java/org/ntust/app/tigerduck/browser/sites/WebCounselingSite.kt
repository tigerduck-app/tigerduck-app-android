package org.ntust.app.tigerduck.browser.sites

import okhttp3.HttpUrl

object WebCounselingSite : SchoolSite {
    override val hosts = setOf("sa02.ntust.edu.tw")
    override val logoutRules = listOf(SiteLogoutRule("/WebCounseling/OAuth2/Logout"), SiteLogoutRule("/classroom_user/sign-out"))
    override val logoutSelectors = logoutRules.flatMap { it.selectors(hosts) }

    override fun signInLink(page: HttpUrl): String? {
        if (page.encodedPath.lowercase() != "/webcounseling/") return null
        return "https://sa02.ntust.edu.tw/WebCounseling/OAuth2/Login"
    }
}
