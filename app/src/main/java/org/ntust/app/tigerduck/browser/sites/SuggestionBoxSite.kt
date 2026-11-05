package org.ntust.app.tigerduck.browser.sites

object SuggestionBoxSite : SchoolSite {
    override val hosts = setOf("suggestionbox.ntust.edu.tw")
    override val logoutRules = listOf(SiteLogoutRule("/logout"))
    override val logoutSelectors = logoutRules.flatMap { it.selectors(hosts) }
}
