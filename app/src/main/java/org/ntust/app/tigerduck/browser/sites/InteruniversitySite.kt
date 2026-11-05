package org.ntust.app.tigerduck.browser.sites

object InteruniversitySite : SchoolSite {
    override val hosts = setOf("interuniversity.ntust.edu.tw")
    override val logoutRules = listOf(SiteLogoutRule("/Account/LogOff"))
    override val logoutSelectors = logoutRules.flatMap { it.selectors(hosts) }
}
