package org.ntust.app.tigerduck.browser.sites

object SsoSite : SchoolSite {
    override val hosts = setOf("ssoam2.ntust.edu.tw")
    override val logoutRules = listOf(SiteLogoutRule("/connect/endsession"))
    override val logoutSelectors = logoutRules.flatMap { it.selectors(hosts) }
}
