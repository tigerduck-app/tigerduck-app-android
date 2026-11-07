package org.ntust.app.tigerduck.browser.sites

object StudentInformationSite : SchoolSite {
    override val hosts = setOf("stuinfosys.ntust.edu.tw")
    override val logoutRules = listOf(SiteLogoutRule("/Authenticate/Account/Logout"))
    override val logoutSelectors = logoutRules.flatMap { it.selectors(hosts) }
    override val signInForms = mapOf(
        // The research ethics course and the social practice course sign in here, with an image CAPTCHA.
        "/Authenticate/Account/EnrollStudent/ethicscourse" to SiteSignInForm(studentId = "UserName", password = "Password"),
        "/Authenticate/SSOAccount/Index/SSOcgesocial" to SiteSignInForm(studentId = "UserName", password = "Password"),
    )
}
