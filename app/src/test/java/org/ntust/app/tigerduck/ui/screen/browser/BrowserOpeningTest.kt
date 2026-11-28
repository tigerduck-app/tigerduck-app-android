package org.ntust.app.tigerduck.ui.screen.browser

import android.app.Application
import android.content.Intent
import android.webkit.CookieManager
import android.webkit.WebView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.browser.BrowserCleanup
import org.ntust.app.tigerduck.browser.BrowserLinksProfile
import org.ntust.app.tigerduck.browser.BrowserRiskProfiles
import org.ntust.app.tigerduck.browser.BrowserRouting
import org.ntust.app.tigerduck.browser.BrowserSignIn
import org.ntust.app.tigerduck.browser.BrowserSignIns
import org.ntust.app.tigerduck.browser.CertificateRefusal
import org.ntust.app.tigerduck.browser.FakeWebViewProfiles
import org.ntust.app.tigerduck.browser.NavigationDecision
import org.ntust.app.tigerduck.browser.SchoolBrowserSignIn
import org.ntust.app.tigerduck.ui.screen.coursemoodle.MoodleWebPages
import org.ntust.app.tigerduck.ui.screen.coursemoodle.editOwnProfile
import org.ntust.app.tigerduck.ui.screen.informationsystem.openInformationSystemLink
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What a browser may use of the app's, by how it was opened: a link the app was handed loads in the
 * links profile, signed in to nothing; the app's own launches keep the app's profile and its sign-ins.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BrowserOpeningTest {

    private val context: Application = RuntimeEnvironment.getApplication()

    private fun started(): Intent = shadowOf(context).nextStartedActivity!!

    /** The app's providers, standing for Moodle's and the school sites': each covers every school page. */
    private val appSignIns = BrowserSignIns(listOf(object : BrowserSignIn {
        override val siteName = "NTUST"
        override fun handles(url: String) = SchoolBrowserSignIn.isSchool(url)
        override fun hasSession(cookieHeader: String?) = false
        override fun isSignInPage(url: String) = false
        override fun isReturnPage(url: String) = true
        override fun isSignInLink(url: String) = false
        override suspend fun signedInUrl(target: String): String? = null
    }))

    private val school = listOf(
        "https://moodle2.ntust.edu.tw/my/",
        "https://i.ntust.edu.tw/student",
        "https://ssoam2.ntust.edu.tw/account/login",
        "https://stuinfosys.ntust.edu.tw/StuScoreQueryServ/StuScoreQuery",
    )

    @Before
    fun canOpen() {
        InAppBrowser.canClearEverything = { true }
        InAppBrowser.linksProfile = BrowserLinksProfile(FakeWebViewProfiles())
        BrowserCleanup(context).run { markDone(markPending()) }
    }

    @After
    fun restore() {
        InAppBrowser.canClearEverything = { false }
        InAppBrowser.linksProfile = BrowserLinksProfile.shared
        InAppBrowser.riskProfiles = BrowserRiskProfiles.shared
    }

    private fun linksLaunch(url: String = "https://example.com/news"): Intent {
        assertTrue(InAppBrowser.openLink(context, url))
        return started()
    }

    @Test
    fun `a link the app was handed loads in the links profile, never the app's own`() {
        val opening = BrowserOpening.of(linksLaunch())

        assertTrue(opening.links)
        assertEquals(BrowserLinksProfile.NAME, opening.profile)
    }

    @Test
    fun `the links browser has no sign-in for a school or Moodle page, fills nothing and readies no cookie`() {
        val opening = BrowserOpening.of(linksLaunch())

        school.forEach { assertNull(it, opening.signIns(appSignIns).forUrl(it)) }
        // signsIn gates the site fill (BrowserSiteSignIn) and SchoolSiteCookies.prepare.
        assertFalse(opening.signsIn)
    }

    @Test
    fun `the links browser keeps its profile's cookies, and its downloads carry those`() {
        val opening = BrowserOpening.of(linksLaunch())
        val web = WebView(context)
        var asked: WebView? = null

        opening.cookieManager(web) { asked = it; CookieManager.getInstance() }
        assertSame(web, asked)

        asked = null
        assertNotNull(opening.downloadCookies(web) { asked = it; CookieManager.getInstance() })
        assertSame(web, asked)
    }

    @Test
    fun `the links browser loads every https page, and hands http ones outside`() {
        val policy = InAppBrowser.allowlistFor(linksLaunch())

        listOf("https://example.com/", "https://outside.example:8443/a", "https://i.ntust.edu.tw/").forEach {
            assertEquals(it, NavigationDecision.Load, BrowserRouting.navigation(it, policy))
        }
        listOf("http://example.com/", "http://i.ntust.edu.tw/").forEach {
            assertEquals(it, NavigationDecision.OpenOutside(it), BrowserRouting.navigation(it, policy))
        }
    }

    @Test
    fun `a page the links browser opens at risk loads in its own profile, not the links one`() {
        InAppBrowser.riskProfiles = BrowserRiskProfiles(FakeWebViewProfiles())
        val opener = linksLaunch()

        assertTrue(InAppBrowser.openAtRisk(context, opener, CertificateRefusal("https://example.com/", "d".repeat(64)), null))

        val opening = BrowserOpening.of(started())
        assertTrue(opening.profile!!.startsWith(BrowserRiskProfiles.PREFIX))
        assertNull(opening.downloadCookies(WebView(context)) { CookieManager.getInstance() })
    }

    @Test
    fun `only an https link opens the links browser`() {
        listOf("http://example.com/", "javascript:alert(1)", "file:///sdcard/a", "intent://x#Intent;end", "mailto:a@b.example").forEach {
            assertFalse(it, InAppBrowser.openLink(context, it))
            assertNull(it, shadowOf(context).nextStartedActivity)
        }
    }

    @Test
    fun `where the WebView keeps no profile apart, or the browser cannot open, no links browser opens`() {
        InAppBrowser.linksProfile = BrowserLinksProfile(FakeWebViewProfiles(supported = false))
        assertFalse(InAppBrowser.openLink(context, "https://example.com/"))

        InAppBrowser.linksProfile = BrowserLinksProfile(FakeWebViewProfiles())
        InAppBrowser.canClearEverything = { false }
        assertFalse(InAppBrowser.openLink(context, "https://example.com/"))

        InAppBrowser.canClearEverything = { true }
        BrowserCleanup(context).markPending()
        assertFalse(InAppBrowser.openLink(context, "https://example.com/"))

        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test
    fun `the app's own launches keep the app's profile, its sign-ins and its cookies`() {
        openInformationSystemLink(context, "https://i.ntust.edu.tw/student") { error("opened elsewhere") }
        val informationSystem = started()
        MoodleWebPages.inAppFirst { _, _, _ -> error("opened elsewhere") }.open(context, "https://moodle2.ntust.edu.tw/my/", null)
        val moodle = started()
        editOwnProfile(context) { _, _ -> error("opened elsewhere") }
        val profileEditor = started()

        listOf(informationSystem, moodle, profileEditor).map(BrowserOpening::of).forEach { opening ->
            assertFalse(opening.links)
            assertNull("the app's own profile", opening.profile)
            assertTrue(opening.signsIn)
            assertSame(appSignIns, opening.signIns(appSignIns))
            var asked = false
            assertSame(CookieManager.getInstance(), opening.cookieManager(WebView(context)) { asked = true; CookieManager.getInstance() })
            assertFalse(asked)
            assertNull("the app's own cookies, as the transfer reads them", opening.downloadCookies(WebView(context)) { error("not its profile's") })
        }
    }
}
