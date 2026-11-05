package org.ntust.app.tigerduck.browser.sites

object PortalSite : SchoolSite {
    override val hosts = setOf("i.ntust.edu.tw")
    override val logoutRules = listOf(SiteLogoutRule("/Account/Logout"))
    override val logoutSelectors = logoutRules.flatMap { it.selectors(hosts) }
}
