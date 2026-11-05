package org.ntust.app.tigerduck.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserLaunchPolicyTest {
    @Test
    fun `no restriction loads any HTTPS page`() {
        val policy = BrowserLaunchPolicy.allowlist("https://first.example/")
        listOf("https://outside.example/", "https://outside.example:8443/").forEach {
            assertEquals(NavigationDecision.Load, BrowserRouting.navigation(it, policy))
        }
    }

    @Test
    fun `no restriction asks before opening HTTP outside`() {
        val policy = BrowserLaunchPolicy.allowlist("https://first.example/")
        listOf("http://first.example/", "http://outside.example:8080/").forEach {
            assertEquals(NavigationDecision.OpenOutside(it), BrowserRouting.navigation(it, policy))
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
