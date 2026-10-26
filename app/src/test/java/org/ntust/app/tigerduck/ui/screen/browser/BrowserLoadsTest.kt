package org.ntust.app.tigerduck.ui.screen.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.browser.BrowserSignIn
import org.ntust.app.tigerduck.browser.BrowserSignIns
import org.ntust.app.tigerduck.browser.CertificateInfo
import org.ntust.app.tigerduck.browser.MoodleBrowserSignIn

/**
 * The page Retry and "Open in browser" name, the error over the page and the page a sign-in goes
 * on to, as the WebView reports a page's loads. A failed load's error comes before its page starts
 * on some WebViews and after it on others.
 */
class BrowserLoadsTest {

    private val state = BrowserPageState()

    /** Moodle, as far as where a sign-in may go on to. */
    private val moodle = object : BrowserSignIn {
        override val siteName = "Moodle"
        override fun handles(url: String) = url.startsWith("https://moodle2.ntust.edu.tw/")
        override fun hasSession(cookieHeader: String?) = false
        override fun isSignInPage(url: String) = url.startsWith(SIGN_IN_PAGE)
        override fun isReturnPage(url: String) = MoodleBrowserSignIn.isMoodleReturnPage(url)
        override fun isSignInLink(url: String) = MoodleBrowserSignIn.isMoodleSignInLink(url)
        override suspend fun signedInUrl(target: String): String? = null
    }
    private val loads = BrowserLoads(state, BrowserSignIns(listOf(moodle)))

    private val unreachable = BrowserError.Unreachable("moodle2.ntust.edu.tw")

    @Test
    fun `an error that comes before its page starts stays up as that page starts`() {
        loads.failed(B, unreachable)
        loads.started(B)
        loads.finished(B)

        assertEquals(unreachable, state.error)
        assertEquals(B, loads.loadingUrl)
    }

    @Test
    fun `an error that comes after its page started stays up`() {
        loads.started(B)
        loads.failed(B, unreachable)
        loads.finished(B)

        assertEquals(unreachable, state.error)
    }

    @Test
    fun `another page starting takes the error down, whichever side of its start the error came`() {
        loads.failed(B, unreachable)
        loads.started(B)
        loads.finished(B)
        // Forward, say: no navigation the browser was asked about.
        loads.started(C)

        assertNull(state.error)
        assertEquals(C, loads.loadingUrl)

        loads.finished(C)
        loads.started(B)
        loads.failed(B, unreachable)
        loads.finished(B)
        loads.started(C)

        assertNull(state.error)
    }

    @Test
    fun `the failed page starting again, once its error page has, takes the error down`() {
        loads.failed(B, unreachable)
        loads.started(B)
        loads.finished(B)

        loads.started(B)

        assertNull(state.error)

        // The error after the start, this time.
        loads.failed(B, unreachable)
        loads.finished(B)
        loads.started(B)

        assertNull(state.error)
    }

    @Test
    fun `a failed load the browser was not asked about is what Retry names`() {
        loads.requested(A)
        loads.started(A)
        loads.finished(A)

        // A form's POST: no shouldOverrideUrlLoading, and the error before the page starts.
        loads.failed(B, unreachable)

        assertEquals(B, loads.loadingUrl)
    }

    @Test
    fun `a page that starts without the browser asked about it is what Retry names`() {
        loads.requested(A)
        loads.started(A)
        loads.finished(A)

        loads.started(B)

        assertEquals(B, loads.loadingUrl)
    }

    @Test
    fun `a sign-in goes on to its page until a page of the site shows`() {
        loads.signInTarget = A
        // The signed-in link, which Moodle bounces back to its sign-in page.
        loads.requested(SIGNED_IN)

        assertEquals(A, loads.signInReturn(SIGN_IN_PAGE))
        // The school's SSO, which is not the site's.
        loads.started(SSO)
        assertEquals(A, loads.signInReturn(SIGN_IN_PAGE))
    }

    @Test
    fun `once a page of the site shows, a later sign-in goes on to the page it came in the way of`() {
        loads.signInTarget = A
        loads.requested(SIGNED_IN)
        loads.started(A)
        loads.finished(A)

        // The student opens another page, and Moodle has signed the browser out meanwhile.
        loads.requested(C)

        assertNull(loads.signInTarget)
        assertEquals(C, loads.signInReturn(SIGN_IN_PAGE))
    }

    @Test
    fun `the site's sign-in link starting does not end the sign-in`() {
        loads.signInTarget = A
        loads.requested(SIGNED_IN)
        // Its error page, say, when the link could not load.
        loads.failed(SIGNED_IN, unreachable)
        loads.started(SIGNED_IN)
        loads.finished(SIGNED_IN)

        assertEquals(A, loads.signInTarget)
        assertEquals(A, loads.signInReturn(SIGN_IN_PAGE))
    }

    @Test
    fun `a sign-in never goes on to the site's sign-in link`() {
        loads.requested(A)
        loads.started(A)
        // As the activity does on each start.
        state.url = A
        loads.finished(A)
        loads.requested(SIGNED_IN)

        assertEquals(A, loads.signInReturn(SIGN_IN_PAGE))
    }

    @Test
    fun `a certificate refused for something on the page leaves the page alone`() {
        loads.requested(A)
        loads.started(A)
        state.loading = true

        assertFalse(loads.certificateFailed(IMAGE, "files.ntust.edu.tw"))

        assertNull(state.error)
        assertEquals(A, loads.loadingUrl)
    }

    @Test
    fun `a certificate refused for the page being loaded shows the certificate error, with that certificate`() {
        val refused = CertificateInfo("moodle2.ntust.edu.tw", null, "Expired CA", null, null, null, "CC:DD")
        loads.requested(B)

        assertTrue(loads.certificateFailed(B, "moodle2.ntust.edu.tw", refused))

        assertEquals(BrowserError.BadCertificate("moodle2.ntust.edu.tw", refused), state.error)
        assertEquals(B, loads.loadingUrl)
    }

    @Test
    fun `a sign-in link goes by the page it signs in to`() {
        loads.signInTarget = A
        loads.requested(SIGNED_IN)
        // Offline, say: its error page.
        loads.failed(SIGNED_IN, unreachable)
        loads.started(SIGNED_IN)

        assertEquals(A, loads.address(SIGNED_IN))
        assertEquals(A, loads.loadingUrl)
        assertEquals(B, loads.address(B))
    }

    @Test
    fun `a sign-in link with no sign-in under way goes by its site`() {
        // Back to its error page, long after.
        loads.started(SIGNED_IN)

        assertEquals("https://moodle2.ntust.edu.tw/", loads.address(SIGNED_IN))
        assertEquals("https://moodle2.ntust.edu.tw/", loads.loadingUrl)
    }

    @Test
    fun `a certificate refused for a form's page, before it starts, is the page's`() {
        loads.requested(A)
        loads.started(A)
        state.url = A
        loads.finished(A)
        // A form's POST to B: its request goes out, and nothing asks the browser about it.
        loads.mainFrameRequested(B)

        assertTrue(loads.certificateFailed(B, "moodle2.ntust.edu.tw"))

        assertEquals(BrowserError.BadCertificate("moodle2.ntust.edu.tw"), state.error)
        assertEquals(B, loads.loadingUrl)
    }

    @Test
    fun `a page starting retires the request before it, so a refused image of that address is not the page's`() {
        // A's request, which redirected to B without the browser being asked.
        loads.mainFrameRequested(A)
        loads.started(B)
        state.url = B
        loads.finished(B)

        assertFalse(loads.certificateFailed(A, "moodle2.ntust.edu.tw"))
        assertNull(state.error)
        assertEquals(B, loads.loadingUrl)
    }

    @Test
    fun `a navigation the browser is asked about retires the request before it`() {
        loads.mainFrameRequested(A)
        loads.requested(B)

        assertFalse(loads.certificateFailed(A, "moodle2.ntust.edu.tw"))
        assertNull(state.error)
    }

    @Test
    fun `a certificate refused for a sign-in link is still the page's`() {
        loads.signInTarget = A
        loads.requested(SIGNED_IN)

        assertTrue(loads.certificateFailed(SIGNED_IN, "moodle2.ntust.edu.tw"))
    }

    private companion object {
        const val SITE_A = "https://stuinfosys.ntust.edu.tw/StudentInformation"
        const val SITE_B = "https://cour01.ntust.edu.tw/StudentDoubleMajor/"
        const val AUTHORIZE = "https://ssoam2.ntust.edu.tw/connect/authorize?client_id=made-up"
        const val SSO_LOGIN = "https://ssoam2.ntust.edu.tw/account/login?ReturnUrl=%2Fconnect%2Fauthorize%3Fclient_id%3Dmade-up"
        const val IMAGE = "https://files.ntust.edu.tw/logo.png"
        const val A = "https://moodle2.ntust.edu.tw/user/edit.php"
        const val B = "https://moodle2.ntust.edu.tw/course/view.php?id=1"
        const val C = "https://moodle2.ntust.edu.tw/course/view.php?id=2"
        const val SIGN_IN_PAGE = "https://moodle2.ntust.edu.tw/login/index.php"
        const val SIGNED_IN = "https://moodle2.ntust.edu.tw/admin/tool/mobile/autologin.php?userid=1&key=k&urltogo=x"
        const val SSO = "https://ssoam2.ntust.edu.tw/nidp/app/login"
    }

    /** The school's sites, as far as where a sign-in may go on to; the sign-in itself never runs here. */
    private val school = org.ntust.app.tigerduck.browser.SchoolBrowserSignIn(
        run = { error("no sign-in here") },
        warm = { false },
        isDemo = { false },
        usable = { true },
        generation = { 0 },
        writeCookie = { _, _ -> },
        flush = {},
    )
    private val schoolLoads = BrowserLoads(state, BrowserSignIns(listOf(moodle, school)))

    @Test
    fun `a school site's sign-in goes on to the page asked for, through SSO's authorize on the way`() {
        schoolLoads.requested(SITE_A)
        schoolLoads.requested(AUTHORIZE)

        assertEquals(SITE_A, schoolLoads.signInReturn(SSO_LOGIN))
    }

    @Test
    fun `from a school page, a sign-in goes on to the page asked for next, not the one shown`() {
        state.url = SITE_A
        schoolLoads.requested(SITE_B)
        schoolLoads.requested(AUTHORIZE)

        assertEquals(SITE_B, schoolLoads.signInReturn(SSO_LOGIN))
    }

    @Test
    fun `a form's POST to a school page is the page asked for`() {
        state.url = SITE_A
        schoolLoads.mainFrameRequested(SITE_B)

        assertEquals(SITE_B, schoolLoads.signInReturn(SSO_LOGIN))
    }

    @Test
    fun `a page no provider signs in leaves no school page asked for`() {
        schoolLoads.requested(SITE_A)
        schoolLoads.requested("https://example.com/")
        schoolLoads.requested(AUTHORIZE)

        assertFalse(schoolLoads.signInReturn(SSO_LOGIN) == SITE_A)
    }
}
