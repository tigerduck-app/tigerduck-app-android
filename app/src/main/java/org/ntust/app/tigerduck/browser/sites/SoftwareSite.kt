package org.ntust.app.tigerduck.browser.sites

object SoftwareSite : SchoolSite {
    override val hosts = setOf("software.ntust.edu.tw")
    override val logoutRules = listOf(SiteLogoutRule("/websoftware/logout.aspx"))
    override val logoutSelectors = logoutRules.flatMap { it.selectors(hosts) }
}
