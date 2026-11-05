package org.ntust.app.tigerduck.browser.sites

object StudentInformationSite : SchoolSite {
    override val hosts = setOf("stuinfosys.ntust.edu.tw")
    override val logoutRules = listOf(SiteLogoutRule("/Authenticate/Account/Logout"))
    override val logoutSelectors = logoutRules.flatMap { it.selectors(hosts) }
}
