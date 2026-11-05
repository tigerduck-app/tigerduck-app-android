package org.ntust.app.tigerduck.browser.sites

object UniversitySite : SchoolSite {
    override val hosts = setOf("www.ntust.edu.tw")
    override val logoutRules = emptyList<SiteLogoutRule>()
    override val logoutSelectors = logoutRules.flatMap { it.selectors(hosts) }
}
