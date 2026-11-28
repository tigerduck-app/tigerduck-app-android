package org.ntust.app.tigerduck.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.browser.sites.BrowserSchoolSites

class BrowserLaunchPolicyTest {
    private val informationSystem = BrowserLaunchPolicy.allowlist("https://i.ntust.edu.tw/student")

    @Test
    fun `with no restriction, a launch in the app's own profile sends another site's page outside, through the question`() {
        listOf(
            "https://example.com/",
            "https://outside.example:8443/",
            "https://ntust.edu.tw.example.com/",
            "https://evilntust.edu.tw/",
            "http://example.com/",
            "http://i.ntust.edu.tw/student",
        ).forEach {
            assertEquals(it, NavigationDecision.OpenOutside(it), BrowserRouting.navigation(it, informationSystem))
        }
    }

    @Test
    fun `with no restriction, every school page still loads, on any of the school's hosts`() {
        val school = listOf(
            "https://www.lib.ntust.edu.tw/",
            "https://moodle2.ntust.edu.tw/my/",
            "https://ssoam.ntust.edu.tw/nidp/app/login",
            "https://ssoam2.ntust.edu.tw/account/login",
            "https://ntust.edu.tw/",
            "https://a.b.ntust.edu.tw/x",
        ) + BrowserSchoolSites.all.flatMap { it.hosts }.map { "https://$it/" }
        school.forEach { assertEquals(it, NavigationDecision.Load, BrowserRouting.navigation(it, informationSystem)) }
    }

    @Test
    fun `with no restriction, the first page always loads, and no other page of its site`() {
        val policy = BrowserLaunchPolicy.allowlist("https://first.example:8443/start?x=1")

        assertEquals(NavigationDecision.Load, BrowserRouting.navigation("https://first.example:8443/start?x=1", policy))
        listOf("https://first.example/next", "https://first.example:8443/other").forEach {
            assertEquals(it, NavigationDecision.OpenOutside(it), BrowserRouting.navigation(it, policy))
        }
    }

    @Test
    fun `with no restriction, a form's POST to another site is stopped before it is sent, and a school site's goes`() {
        assertTrue(BrowserRouting.blocksRequest("https://example.com/collect", isForMainFrame = true, informationSystem))
        assertFalse(BrowserRouting.blocksRequest("https://courseselection.ntust.edu.tw/Save", isForMainFrame = true, informationSystem))
    }

    @Test
    fun `the links browser lets a school or Moodle logout through, which ends only that profile's own session`() {
        val logouts = listOf(
            "https://i.ntust.edu.tw/Account/Logout",
            "https://moodle2.ntust.edu.tw/login/logout.php?sesskey=x",
            "https://ssoam2.ntust.edu.tw/connect/endsession",
        )
        logouts.forEach {
            assertEquals(it, NavigationDecision.Load, BrowserRouting.navigation(it, BrowserLaunchPolicy.LINKS))
            assertFalse(it, BrowserRouting.blocksRequest(it, isForMainFrame = false, BrowserLaunchPolicy.LINKS))
            assertFalse(it, BrowserRouting.isRefusedLogout(it, BrowserLaunchPolicy.LINKS))
        }
        // A browser that holds the app's sessions refuses them still, and so does one opened at risk.
        listOf(informationSystem, BrowserAllowlist.DEFAULT, BrowserLaunchPolicy.ANY_HTTPS_PAGE).forEach { policy ->
            logouts.forEach {
                assertEquals(it, NavigationDecision.Refuse, BrowserRouting.navigation(it, policy))
                assertTrue(it, BrowserRouting.blocksRequest(it, isForMainFrame = false, policy))
                assertTrue(it, BrowserRouting.isRefusedLogout(it, policy))
            }
        }
    }

    @Test
    fun `a launch signed in to nothing of the app's loads any HTTPS page and asks before HTTP`() {
        listOf(BrowserLaunchPolicy.ANY_HTTPS_PAGE, BrowserLaunchPolicy.LINKS).forEach { policy ->
            listOf("https://outside.example/", "https://outside.example:8443/").forEach {
                assertEquals(NavigationDecision.Load, BrowserRouting.navigation(it, policy))
            }
            listOf("http://first.example/", "http://outside.example:8080/").forEach {
                assertEquals(NavigationDecision.OpenOutside(it), BrowserRouting.navigation(it, policy))
            }
            assertFalse(BrowserRouting.blocksRequest("https://example.com/collect", isForMainFrame = true, policy))
        }
    }

    @Test
    fun `a caller restricts pages with entries but the first host always loads`() {
        val requested = BrowserAllowlist(listOf(AllowlistEntry.Exact("allowed.example")))
        val policy = BrowserLaunchPolicy.allowlist("https://first.example/start", requested, signsIn = false)
        assertTrue(policy.allows("https://allowed.example/"))
        assertTrue(policy.allows("https://first.example/next"))
        assertFalse(policy.allows("https://outside.example/"))
        assertEquals(NavigationDecision.OpenOutside("https://outside.example/"),
            BrowserRouting.navigation("https://outside.example/", policy))
        assertFalse(requested.allows("https://first.example/"))
    }

    @Test
    fun `the first page on an alternate HTTPS port is never refused by its restriction`() {
        val policy = BrowserLaunchPolicy.allowlist("https://first.example:8443/start", BrowserAllowlist(emptyList()))
        assertEquals(NavigationDecision.Load, BrowserRouting.navigation("https://first.example:8443/start", policy))
        assertTrue(policy.allows("https://first.example/next"))
        assertFalse(policy.allows("https://outside.example:8443/start"))
    }

    @Test
    fun `automatic sign-in adds both SSO hosts only to its launch`() {
        val requested = BrowserAllowlist(emptyList())
        val signedIn = BrowserLaunchPolicy.allowlist("https://querycourse.ntust.edu.tw/QueryCourse/", requested)
        val plain = BrowserLaunchPolicy.allowlist("https://first.example/", requested, signsIn = false)
        listOf("https://ssoam.ntust.edu.tw/nidp/app/login", "https://ssoam2.ntust.edu.tw/connect/authorize").forEach {
            assertEquals(NavigationDecision.Load, BrowserRouting.navigation(it, signedIn))
            assertFalse(plain.allows(it))
        }
    }
}
