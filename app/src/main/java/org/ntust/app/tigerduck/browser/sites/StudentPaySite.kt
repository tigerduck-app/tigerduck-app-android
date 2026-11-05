package org.ntust.app.tigerduck.browser.sites

object StudentPaySite : SchoolSite {
    override val hosts = setOf("studentpay.ntust.edu.tw")
    override val logoutRules = listOf(SiteLogoutRule("/Logout.aspx"))
    override val logoutSelectors = logoutRules.flatMap { it.selectors(hosts) }
}
