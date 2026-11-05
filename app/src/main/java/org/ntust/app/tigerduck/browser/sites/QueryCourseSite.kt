package org.ntust.app.tigerduck.browser.sites

import okhttp3.HttpUrl

object QueryCourseSite : SchoolSite {
    override val hosts = setOf("querycourse.ntust.edu.tw")
    override val logoutRules = listOf(SiteLogoutRule("/QueryCourse/api//OAuth2/Logout"))
    override val logoutSelectors = logoutRules.flatMap { it.selectors(hosts) }

    override fun signInLink(page: HttpUrl): String? {
        if (page.encodedPath.lowercase() != "/querycourse/") return null
        val back = java.net.URLEncoder.encode(page.toString(), "UTF-8")
            .replace("+", "%20").replace("%21", "!").replace("%27", "'")
            .replace("%28", "(").replace("%29", ")").replace("%7E", "~")
        return "https://querycourse.ntust.edu.tw/QueryCourse/api//OAuth2/Login?returnUrl=$back"
    }
}
