package org.ntust.app.tigerduck.browser.sites

object CardSystemSite : SchoolSite {
    override val hosts = setOf("cardsystem.ntust.edu.tw")
    override val logoutRules = listOf(SiteLogoutRule("/std/index.php/user/logout"))
    override val logoutSelectors = logoutRules.flatMap { it.selectors(hosts) }
    override val signInForms = mapOf(
        "/std/index.php/user/login" to SiteSignInForm(studentId = "id", password = "password"),
    )
}
