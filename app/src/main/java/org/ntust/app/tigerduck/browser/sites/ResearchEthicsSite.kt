package org.ntust.app.tigerduck.browser.sites

object ResearchEthicsSite : SchoolSite {
    override val hosts = setOf("ae.cge.ntust.edu.tw")
    override val logoutRules = emptyList<SiteLogoutRule>()
    override val logoutSelectors = logoutRules.flatMap { it.selectors(hosts) }
}
