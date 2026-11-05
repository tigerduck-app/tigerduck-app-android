package org.ntust.app.tigerduck.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.browser.sites.BrowserSchoolSites

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
    fun `unknown school sites retain every generic logout rule`() {
        listOf("/AGLogout", "/LogOff.aspx", "/sign-out", "/connect/endsession", "/page?task=logout").forEach {
            assertEquals(NavigationDecision.Refuse, BrowserRouting.navigation("https://unknown.ntust.edu.tw$it", BrowserAllowlist.DEFAULT))
        }
        listOf("/logout.pdf", "/page?return=/logout").forEach {
            assertEquals(NavigationDecision.Load, BrowserRouting.navigation("https://unknown.ntust.edu.tw$it", BrowserAllowlist.DEFAULT))
        }
    }
}
