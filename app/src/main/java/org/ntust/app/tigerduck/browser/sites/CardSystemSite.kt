package org.ntust.app.tigerduck.browser.sites

object CardSystemSite : SchoolSite {
    override val hosts = setOf("cardsystem.ntust.edu.tw")
    override val logoutRules = listOf(SiteLogoutRule("/std/index.php/user/logout"))
    override val logoutSelectors = logoutRules.flatMap { it.selectors(hosts) }
}
