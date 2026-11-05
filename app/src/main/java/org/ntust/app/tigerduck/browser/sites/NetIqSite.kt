package org.ntust.app.tigerduck.browser.sites

object NetIqSite : SchoolSite {
    override val hosts = setOf("ssoam.ntust.edu.tw")
    override val logoutRules = listOf(SiteLogoutRule("/nidp/app/logout"), SiteLogoutRule("/AGLogout"))
    override val logoutSelectors = logoutRules.flatMap { it.selectors(hosts) }
}
