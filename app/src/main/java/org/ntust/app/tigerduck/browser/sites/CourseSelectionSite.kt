package org.ntust.app.tigerduck.browser.sites

object CourseSelectionSite : SchoolSite {
    override val hosts = setOf("courseselection.ntust.edu.tw")
    override val logoutRules = listOf(SiteLogoutRule("/Account/LogOff"))
    override val logoutSelectors = logoutRules.flatMap { it.selectors(hosts) }
}
