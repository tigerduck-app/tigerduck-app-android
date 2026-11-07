package org.ntust.app.tigerduck.browser.sites

object SuggestionBoxSite : SchoolSite {
    override val hosts = setOf("suggestionbox.ntust.edu.tw")
    override val logoutRules = listOf(SiteLogoutRule("/logout"))
    override val logoutSelectors = logoutRules.flatMap { it.selectors(hosts) }
    // Its sign-in page says its password is the career development site's, not the school's.
    override val signInForms = mapOf("/login" to SiteSignInForm(studentId = "account", password = null))
}
