package org.ntust.app.tigerduck.browser.sites

object ComputerCenterSite : SchoolSite {
    override val hosts = setOf("www.cc.ntust.edu.tw")
    override val logoutRules = emptyList<SiteLogoutRule>()
    override val logoutSelectors = logoutRules.flatMap { it.selectors(hosts) }
}
