package org.ntust.app.tigerduck.browser.sites

object StudentServicesSite : SchoolSite {
    override val hosts = setOf("stu.ntust.edu.tw")
    override val logoutRules = listOf(SiteLogoutRule("/stueduneed/Logout.aspx"))
    override val logoutSelectors = logoutRules.flatMap { it.selectors(hosts) }
}
