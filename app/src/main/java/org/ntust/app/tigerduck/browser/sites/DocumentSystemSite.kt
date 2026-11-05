package org.ntust.app.tigerduck.browser.sites

object DocumentSystemSite : SchoolSite {
    override val hosts = setOf("ccdoc.ntust.edu.tw")
    override val logoutRules = listOf(SiteLogoutRule("/docmgsystem/index.aspx", "action", "logout"))
    override val logoutSelectors = logoutRules.flatMap { it.selectors(hosts) }
}
