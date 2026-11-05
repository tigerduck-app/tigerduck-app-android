package org.ntust.app.tigerduck.browser.sites

object LibrarySite : SchoolSite {
    override val hosts = setOf("www.lib.ntust.edu.tw")
    override val logoutRules = emptyList<SiteLogoutRule>()
    override val logoutSelectors = logoutRules.flatMap { it.selectors(hosts) }
}
