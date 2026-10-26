package org.ntust.app.tigerduck.browser

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.network.BrowserSiteSession
import org.ntust.app.tigerduck.network.SsoLoginError

/** The school's sites' sign-in in the TigerDuck browser: which pages it takes, and what it hands the browser. */
@OptIn(ExperimentalCoroutinesApi::class)
class SchoolBrowserSignInTest {

    private val written = mutableListOf<Pair<String, String>>()
    private var flushes = 0
    private val runs = mutableListOf<String>()
    private val warms = mutableListOf<String>()
    private var warmWorks = true
    private var generation = 7
    private var usable = true
    private var demo = false
    private var clock = 1_000_000L

    private val site = "https://$SITE_HOST/".toHttpUrl()
    private val session = listOf(Cookie.parse(site, "$SITE_COOKIE=site-made-up; Path=/; Secure; HttpOnly")!!)

    /** What a run does: hands back [session], signed in through SSO, unless set otherwise. */
    private var answer: suspend (String) -> BrowserSiteSession = { BrowserSiteSession(session, signedIn = true) }

    private fun signIn(timeoutMs: Long = 15_000) = SchoolBrowserSignIn(
        run = { target -> runs += target; answer(target) },
        warm = { target -> warms += target; warmWorks },
        isDemo = { demo },
        usable = { usable },
        generation = { generation },
        writeCookie = { url, value -> written += url to value },
        flush = { flushes++ },
        timeoutMs = timeoutMs,
        now = { clock },
    )

    @Test
    fun `it takes the school's sites and SSO's, not Moodle's nor anyone else's`() {
        val school = signIn()
        listOf(PAGE, "https://ntust.edu.tw/", "https://$SSO_HOST/account/login", "https://ssoam.ntust.edu.tw/nidp/app/login")
            .forEach { assertTrue(it, school.handles(it)) }
        listOf("https://moodle2.ntust.edu.tw/my/", "http://$SITE_HOST/", "https://example.com/", "https://evilntust.edu.tw/", "https://ntust.edu.tw.example.com/")
            .forEach { assertFalse(it, school.handles(it)) }
    }

    @Test
    fun `ssoam2's sign-in form is the sign-in page, for any site but Moodle`() {
        val school = signIn()
        assertTrue(school.isSignInPage("https://$SSO_HOST/account/login?ReturnUrl=%2Fconnect%2Fauthorize%2Fcallback%3Fclient_id%3Dstuinfosys"))
        assertTrue(school.isSignInPage("https://$SSO_HOST/Account/Login"))
        // Moodle's OIDC sign-in, which its own provider takes.
        assertFalse(school.isSignInPage("https://$SSO_HOST/account/login?ReturnUrl=%2Fconnect%2Fauthorize%2Fcallback%3Fredirect_uri%3Dhttps%253A%252F%252Fmoodle2.ntust.edu.tw%252Fauth%252Foidc%252F"))
        // Its authorize passes a session through; NetIQ's page and a site's own form are the student's.
        assertFalse(school.isSignInPage("https://$SSO_HOST/connect/authorize?client_id=stuinfosys"))
        assertFalse(school.isSignInPage("https://ssoam.ntust.edu.tw/nidp/app/login"))
        assertFalse(school.isSignInPage("https://cardsystem.ntust.edu.tw/std/index.php/user/login"))
    }

    @Test
    fun `a sign-in goes on to a school site's page, never SSO's`() {
        val school = signIn()
        assertTrue(school.isReturnPage(PAGE))
        assertFalse(school.isReturnPage("https://$SSO_HOST/account/login"))
        assertFalse(school.isReturnPage("https://moodle2.ntust.edu.tw/my/"))
    }

    @Test
    fun `it signs in by itself from a page the app opened or a school page, not from another site`() {
        val school = signIn()
        assertTrue(school.signsInFrom(null))
        assertTrue(school.signsInFrom("https://i.ntust.edu.tw/student"))
        assertFalse(school.signsInFrom("https://example.com/"))
        assertFalse(school.signsInFrom("http://$SITE_HOST/"))
    }

    @Test
    fun `a school page loads as it is, with no sign-in ahead`() {
        assertFalse(signIn().signsInAhead(PAGE))
    }

    @Test
    fun `the site's cookies go into the browser over https alone, and are flushed`() = runTest {
        val school = signIn()
        assertTrue(school.signInWithoutLink(PAGE, byHand = false))

        assertEquals(listOf(PAGE), runs)
        assertEquals(listOf("https://$SITE_HOST/"), written.map { it.first })
        assertTrue(written[0].second.startsWith("$SITE_COOKIE=site-made-up;"))
        assertTrue("secure", written[0].second.contains("secure"))
        assertEquals(1, flushes)
    }

    @Test
    fun `the browser is signed in to a site while it holds what it was handed for it`() = runTest {
        val school = signIn()
        assertFalse(school.hasSession(PAGE, null))
        school.signInWithoutLink(PAGE, byHand = false)

        assertTrue(school.hasSession(PAGE, "a=1; $SITE_COOKIE=site-made-up"))
        assertFalse("cookie gone", school.hasSession(PAGE, "a=1"))
        assertFalse("another value", school.hasSession(PAGE, "$SITE_COOKIE=anon-made-up"))
        assertFalse("emptied", school.hasSession(PAGE, "$SITE_COOKIE="))
        assertFalse("another site", school.hasSession("https://cour01.ntust.edu.tw/x", "$SITE_COOKIE=site-made-up"))
        // After the browser was cleared, nothing it was handed is its any more.
        generation++
        assertFalse(school.hasSession(PAGE, "$SITE_COOKIE=site-made-up"))
    }

    @Test
    fun `a page that asked for no sign-in leaves the browser not signed in there`() = runTest {
        val school = signIn()
        answer = { BrowserSiteSession(session, signedIn = false) }
        assertTrue(school.signInWithoutLink(PAGE, byHand = false))

        assertEquals("its cookies still go in", 1, written.size)
        assertFalse(school.hasSession(PAGE, "$SITE_COOKIE=site-made-up"))
    }

    @Test
    fun `with no SSO session the app signs in to SSO by the way of the site and runs once more`() = runTest {
        var first = true
        answer = { if (first) { first = false; throw SsoLoginError.SessionMissing() } else BrowserSiteSession(session, signedIn = true) }

        assertTrue(signIn().signInWithoutLink(PAGE, byHand = false))
        assertEquals(listOf(PAGE), warms)
        assertEquals(2, runs.size)
        assertEquals(1, written.size)
    }

    @Test
    fun `a sign-in to SSO that fails ends it, with no second run`() = runTest {
        warmWorks = false
        answer = { throw SsoLoginError.SessionMissing() }

        assertFalse(signIn().signInWithoutLink(PAGE, byHand = false))
        assertEquals(1, warms.size)
        assertEquals(1, runs.size)
        assertTrue(written.isEmpty())
    }

    @Test
    fun `NetIQ's page, which only a person signs in to, ends it with no sign-in to SSO`() = runTest {
        answer = { throw SsoLoginError.InteractiveLoginRequired() }

        assertFalse(signIn().signInWithoutLink(PAGE, byHand = false))
        assertEquals(0, warms.size)
        assertTrue(written.isEmpty())
    }

    @Test
    fun `by itself a site is signed in once a minute, a failed run included, and by hand at once`() = runTest {
        val school = signIn()
        answer = { throw java.io.IOException("made-up failure") }
        assertFalse(school.signInWithoutLink(PAGE, byHand = false))

        assertFalse(school.maySignInWithoutLink(PAGE, byHand = false))
        assertTrue("another site", school.maySignInWithoutLink("https://cour01.ntust.edu.tw/x", byHand = false))
        assertTrue(school.maySignInWithoutLink(PAGE, byHand = true))
        clock += SchoolBrowserSignIn.INTERVAL_MS
        assertTrue(school.maySignInWithoutLink(PAGE, byHand = false))
    }

    @Test
    fun `nothing runs for the demo account, while the browser cannot be used, or for SSO's own page`() = runTest {
        demo = true
        assertFalse(signIn().signInWithoutLink(PAGE, byHand = true))
        demo = false
        usable = false
        assertFalse(signIn().signInWithoutLink(PAGE, byHand = true))
        usable = true
        assertFalse(signIn().signInWithoutLink("https://$SSO_HOST/account/login", byHand = true))
        assertTrue(runs.isEmpty())
    }

    @Test
    fun `a run a sign-out overtook keeps nothing`() = runTest {
        answer = { generation++; BrowserSiteSession(session, signedIn = true) }

        assertFalse(signIn().signInWithoutLink(PAGE, byHand = false))
        assertTrue(written.isEmpty())
        assertEquals(0, flushes)
    }

    @Test
    fun `a run that takes too long keeps nothing`() = runTest {
        answer = { delay(20_000); BrowserSiteSession(session, signedIn = true) }

        assertFalse(signIn(timeoutMs = 15_000).signInWithoutLink(PAGE, byHand = false))
        assertTrue(written.isEmpty())
    }

    private companion object {
        const val SITE_HOST = "stuinfosys.ntust.edu.tw"
        const val PAGE = "https://$SITE_HOST/StudentInformation"
        const val SITE_COOKIE = ".AspNetCore.Cookies"
        const val SSO_HOST = "ssoam2.ntust.edu.tw"
    }
}
