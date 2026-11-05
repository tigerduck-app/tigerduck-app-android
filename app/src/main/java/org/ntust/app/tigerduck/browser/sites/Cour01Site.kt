package org.ntust.app.tigerduck.browser.sites

object Cour01Site : SchoolSite {
    override val hosts = setOf("cour01.ntust.edu.tw")
    override val logoutRules = listOf(SiteLogoutRule("/DMP_student/Logout.aspx"))
    override val logoutSelectors = logoutRules.flatMap { it.selectors(hosts) }
}
