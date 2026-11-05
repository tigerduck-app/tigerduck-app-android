package org.ntust.app.tigerduck.browser.sites

object SchoolMailSite : SchoolSite {
    override val hosts = setOf("mail.ntust.edu.tw")
    override val logoutRules = listOf(SiteLogoutRule("/cgi-bin/logout"))
    override val logoutSelectors = logoutRules.flatMap { it.selectors(hosts) }
}
