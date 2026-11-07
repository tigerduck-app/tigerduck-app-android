package org.ntust.app.tigerduck.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.browser.sites.BrowserSchoolSites
import org.ntust.app.tigerduck.browser.sites.SitePage
import org.ntust.app.tigerduck.browser.sites.SiteSignInForm
import org.ntust.app.tigerduck.shared.tls.BuiltInPins
import org.ntust.app.tigerduck.shared.tls.matching

class BrowserSchoolSitesTest {
    @Test
    fun `each site blocks its logout without blocking the neighboring page`() {
        val pages = listOf(
            "moodle2.ntust.edu.tw" to "/login/logout.php",
            "querycourse.ntust.edu.tw" to "/QueryCourse/api//OAuth2/Logout",
            "sa02.ntust.edu.tw" to "/WebCounseling/OAuth2/Logout",
            "sa.ntust.edu.tw" to "/Dorm/OAuth2/Logout",
            "stuinfosys.ntust.edu.tw" to "/Authenticate/Account/Logout",
            "cardsystem.ntust.edu.tw" to "/std/index.php/user/logout",
            "studentpay.ntust.edu.tw" to "/Logout.aspx",
            "suggestionbox.ntust.edu.tw" to "/logout",
            "cour01.ntust.edu.tw" to "/DMP_student/Logout.aspx",
            "courseselection.ntust.edu.tw" to "/Account/LogOff",
            "i.ntust.edu.tw" to "/Account/Logout",
            "mail.ntust.edu.tw" to "/cgi-bin/logout",
            "software.ntust.edu.tw" to "/websoftware/logout.aspx",
            "ccdoc.ntust.edu.tw" to "/docmgsystem/index.aspx?action=logout",
            "stu.ntust.edu.tw" to "/stueduneed/Logout.aspx",
            "interuniversity.ntust.edu.tw" to "/Account/LogOff",
            "ssoam2.ntust.edu.tw" to "/connect/endsession",
            "ssoam.ntust.edu.tw" to "/AGLogout",
        )
        pages.forEach { (host, path) ->
            val blocked = "https://$host$path"
            val beside = "https://$host" + path.replace("logout", "login", true)
                .replace("logoff", "login", true).replace("endsession", "authorize", true)
            val site = BrowserSchoolSites.forUrl(blocked)!!
            assertTrue(blocked, site.isLogout(blocked))
            assertFalse(beside, site.isLogout(beside))
            assertEquals(blocked, NavigationDecision.Refuse, BrowserRouting.navigation(blocked, BrowserAllowlist(emptyList()).lettingAnyPageIn()))
            assertTrue(blocked, BrowserRouting.blocksRequest(blocked, false, BrowserAllowlist.DEFAULT))
            assertTrue(host, site.logoutSelectors.isNotEmpty())
        }
    }

    @Test
    fun `public portal destinations keep their pages and the generic logout protection`() {
        listOf("www.ntust.edu.tw", "www.lib.ntust.edu.tw", "ae.cge.ntust.edu.tw", "www.cc.ntust.edu.tw").forEach { host ->
            val page = "https://$host/docs/index.html"
            assertFalse(page, BrowserSchoolSites.forUrl(page)!!.isLogout(page))
            assertEquals(NavigationDecision.Load, BrowserRouting.navigation(page, BrowserAllowlist.DEFAULT))
            assertEquals(NavigationDecision.Refuse, BrowserRouting.navigation("https://$host/logout", BrowserAllowlist.DEFAULT))
        }
    }

    @Test
    fun `a site query rule matches its path and action without taking a neighboring route`() {
        val site = BrowserSchoolSites.forUrl("https://ccdoc.ntust.edu.tw/")!!
        assertTrue(site.isLogout("https://ccdoc.ntust.edu.tw/docmgsystem/index.aspx?x=1&action=logout"))
        assertFalse(site.isLogout("https://ccdoc.ntust.edu.tw/docmgsystem/other.aspx?action=logout"))
        assertFalse(site.isLogout("https://ccdoc.ntust.edu.tw/docmgsystem/index.aspx?action=login"))
        assertFalse(site.isLogout("https://ccdoc.ntust.edu.tw/docmgsystem/index.aspx?return=logout"))
        assertFalse(site.isLogout("https://elsewhere.example/docmgsystem/index.aspx?action=logout"))
    }

    @Test
    fun `the three signed-out landing pages open their SSO links`() {
        val links = mapOf(
            "https://querycourse.ntust.edu.tw/QueryCourse/" to
                "https://querycourse.ntust.edu.tw/QueryCourse/api//OAuth2/Login?returnUrl=https%3A%2F%2Fquerycourse.ntust.edu.tw%2FQueryCourse%2F",
            "https://sa02.ntust.edu.tw/WebCounseling/" to "https://sa02.ntust.edu.tw/WebCounseling/OAuth2/Login",
            "https://sa.ntust.edu.tw/Dorm/" to "https://sa.ntust.edu.tw/Dorm/OAuth2/Login",
        )
        links.forEach { (page, link) -> assertEquals(link, BrowserSchoolSites.signInLink(page)) }
        listOf("https://querycourse.ntust.edu.tw/QueryCourse/?CourseNo=CS1001",
            "http://querycourse.ntust.edu.tw/QueryCourse/", "https://sa.ntust.edu.tw/Scholarship/",
            "https://sa02.ntust.edu.tw/WebCounseling/Front_Index/FrontIndex/Index",
            "https://querycourse.ntust.edu.tw.example.com/QueryCourse/").forEach {
            assertNull(it, BrowserSchoolSites.signInLink(it))
        }
    }

    @Test
    fun `the research ethics and social practice sign-ins are filled by their fields' names`() {
        val form = SiteSignInForm(studentId = "UserName", password = "Password")
        assertEquals(form, BrowserSchoolSites.signInForm("https://stuinfosys.ntust.edu.tw/Authenticate/Account/EnrollStudent/ethicscourse"))
        assertEquals(form, BrowserSchoolSites.signInForm("https://StuInfoSys.ntust.edu.tw/Authenticate/SSOAccount/Index/SSOcgesocial?x=1"))
    }

    @Test
    fun `the student card sign-in is filled by its fields' names`() {
        assertEquals(
            SiteSignInForm(studentId = "id", password = "password"),
            BrowserSchoolSites.signInForm("https://cardsystem.ntust.edu.tw/std/index.php/user/login"),
        )
        assertNull(BrowserSchoolSites.signInForm("https://cardsystem.ntust.edu.tw/std/index.php/user/agreement"))
    }

    @Test
    fun `the work-study sign-in is filled by its fields' names`() {
        val form = SiteSignInForm(studentId = "studentno", password = "password")
        assertEquals(form, BrowserSchoolSites.signInForm("https://studentpay.ntust.edu.tw/"))
        // The page the WebView reports for the bare origin, whose location.pathname is "/" too.
        assertEquals(form, BrowserSchoolSites.signInForm("https://studentpay.ntust.edu.tw"))
        assertNull(BrowserSchoolSites.signInForm("https://studentpay.ntust.edu.tw/Default.aspx"))
    }

    @Test
    fun `the suggestion box sign-in gets the student id alone`() {
        assertEquals(
            SiteSignInForm(studentId = "account", password = null),
            BrowserSchoolSites.signInForm("https://suggestionbox.ntust.edu.tw/login"),
        )
        assertNull(BrowserSchoolSites.signInForm("https://suggestionbox.ntust.edu.tw/"))
    }

    @Test
    fun `no other address is filled`() {
        listOf(
            "http://stuinfosys.ntust.edu.tw/Authenticate/Account/EnrollStudent/ethicscourse",
            "https://stuinfosys.ntust.edu.tw:8443/Authenticate/Account/EnrollStudent/ethicscourse",
            "https://user@stuinfosys.ntust.edu.tw/Authenticate/Account/EnrollStudent/ethicscourse",
            "https://:secret@stuinfosys.ntust.edu.tw/Authenticate/Account/EnrollStudent/ethicscourse",
            "https://stuinfosys.ntust.edu.tw/authenticate/account/enrollstudent/ethicscourse",
            "https://stuinfosys.ntust.edu.tw/Authenticate/Account/EnrollStudent/ethicscourse/",
            "https://stuinfosys.ntust.edu.tw/Authenticate/Account/EnrollStudent",
            "https://stuinfosys.ntust.edu.tw./Authenticate/Account/EnrollStudent/ethicscourse",
            "https://stuinfosys.ntust.edu.tw.example.com/Authenticate/Account/EnrollStudent/ethicscourse",
            "https://cardsystem.ntust.edu.tw/Authenticate/Account/EnrollStudent/ethicscourse",
            "https://cardsystem.ntust.edu.tw/std/index.php/user/login/",
            "https://cardsystem.ntust.edu.tw/std/index.php/user/Login",
            "https://studentpay.ntust.edu.tw/login",
            "https://suggestionbox.ntust.edu.tw/login/",
            "https://suggestionbox.ntust.edu.tw/Login",
            "https://ssoam2.ntust.edu.tw/account/login",
            "javascript:alert(1)",
            "not a page",
        ).forEach { assertNull(it, BrowserSchoolSites.signInForm(it)) }
    }

    @Test
    fun `a page is its https origin and its encoded path, as location gives them`() {
        assertEquals(
            SitePage(host = "stuinfosys.ntust.edu.tw", path = "/Authenticate/SSOAccount/Index/SSOcgesocial"),
            BrowserSchoolSites.page("https://StuInfoSys.ntust.edu.tw/Authenticate/SSOAccount/Index/SSOcgesocial?x=1#top"),
        )
        assertEquals("https://stuinfosys.ntust.edu.tw", BrowserSchoolSites.page("https://stuinfosys.ntust.edu.tw/a")?.origin)
        assertEquals("/a%20b", BrowserSchoolSites.page("https://studentpay.ntust.edu.tw/a%20b")?.path)
        // The default port written out is the same origin, which the WebView reports without it.
        assertEquals(
            BrowserSchoolSites.page("https://studentpay.ntust.edu.tw/"),
            BrowserSchoolSites.page("https://studentpay.ntust.edu.tw:443/"),
        )
        listOf("http://studentpay.ntust.edu.tw/", "https://studentpay.ntust.edu.tw:8443/", "https://a@studentpay.ntust.edu.tw/")
            .forEach { assertNull(it, BrowserSchoolSites.page(it)) }
    }

    @Test
    fun `every site whose form gets the password is under a pin set`() {
        val hosts = BrowserSchoolSites.all.filter { site -> site.signInForms.values.any { it.password != null } }.flatMap { it.hosts }
        assertEquals(setOf("stuinfosys.ntust.edu.tw", "cardsystem.ntust.edu.tw", "studentpay.ntust.edu.tw"), hosts.toSet())
        hosts.forEach { assertNotNull(it, BuiltInPins.sets.matching(it)) }
    }

    @Test
    fun `the sites' own sign-in pages show as they are, not as the browser's sign-in screen`() {
        val school = SchoolBrowserSignIn(
            run = { error("no run") }, warm = { false }, usable = { true }, generation = { 0 },
            writeCookie = { _, _ -> }, flush = {},
        )
        listOf(
            "https://stuinfosys.ntust.edu.tw/Authenticate/Account/EnrollStudent/ethicscourse",
            "https://stuinfosys.ntust.edu.tw/Authenticate/SSOAccount/Index/SSOcgesocial",
            "https://cardsystem.ntust.edu.tw/std/index.php/user/login",
            "https://studentpay.ntust.edu.tw/",
            "https://suggestionbox.ntust.edu.tw/login",
        ).forEach { page ->
            assertTrue(page, school.handles(page))
            assertFalse(page, school.isSignInPage(page))
            assertFalse(page, school.signsInAhead(page))
            assertFalse(page, MoodleBrowserSignIn.isMoodleSignInPage(page))
            assertEquals(page, NavigationDecision.Load, BrowserRouting.navigation(page, BrowserAllowlist.DEFAULT))
        }
    }

    @Test
    fun `unknown school sites retain every generic logout rule`() {
        listOf("/AGLogout", "/LogOff.aspx", "/sign-out", "/connect/endsession", "/page?task=logout").forEach {
            assertEquals(NavigationDecision.Refuse, BrowserRouting.navigation("https://unknown.ntust.edu.tw$it", BrowserAllowlist.DEFAULT))
        }
        listOf("/logout.pdf", "/page?return=/logout").forEach {
            assertEquals(NavigationDecision.Load, BrowserRouting.navigation("https://unknown.ntust.edu.tw$it", BrowserAllowlist.DEFAULT))
        }
    }
}
