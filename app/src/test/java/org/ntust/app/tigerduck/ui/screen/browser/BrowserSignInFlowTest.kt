package org.ntust.app.tigerduck.ui.screen.browser

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.browser.BrowserSignIn
import org.ntust.app.tigerduck.browser.BrowserSignIns
import org.ntust.app.tigerduck.browser.MoodleBrowserSignIn
import org.ntust.app.tigerduck.ui.AppProcessing

/** The sign-in screen: when it signs in by itself, when it says it failed, and which page a sign-in goes on to. */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserSignInFlowTest {

    private val state = BrowserPageState()
    private lateinit var loads: BrowserLoads
    private val loaded = mutableListOf<String>()
    private var stops = 0

    /** A site whose links can be had every [intervalMs], from [firstDueAtMs] on; [works] says whether one comes. */
    private inner class Site(
        var firstDueAtMs: Long = 0,
        val intervalMs: Long = 360_000,
        var works: Boolean = true,
        /** How long a link takes to come. */
        val takesMs: Long = 0,
        /** The app's own sign-in to the site, where it has one: whether it worked. */
        var appSignIn: (suspend () -> Boolean)? = null,
    ) : BrowserSignIn {
        lateinit var clock: () -> Long
        val asked = mutableListOf<String>()
        /** When the app's own sign-in ran. */
        val appRuns = mutableListOf<Long>()
        /** The pages the app's own sign-in was told it goes on to. */
        val appTargets = mutableListOf<String>()
        /** Which pages a sign-in page reached from signs in by itself. */
        var signsInFromPage: (String?) -> Boolean = { true }
        private var dueAt = firstDueAtMs

        override val siteName = "Moodle"
        override fun handles(url: String) = url.startsWith("https://moodle2.ntust.edu.tw/")
        override fun hasSession(cookieHeader: String?) = false
        override fun isSignInPage(url: String) = url.startsWith(SIGN_IN_PAGE)
        override fun isReturnPage(url: String) = MoodleBrowserSignIn.isMoodleReturnPage(url)
        override fun isSignInLink(url: String) = url.startsWith(SIGNED_IN)
        override suspend fun signedInUrl(target: String): String? {
            asked += target
            delay(takesMs)
            if (msUntilRetry() > 0 || !works) return null
            dueAt = clock() + intervalMs
            return "$SIGNED_IN$target"
        }
        fun msUntilRetry() = (dueAt - clock()).coerceAtLeast(0)
        // As Moodle's: by itself once every 6 minutes, by hand at once.
        override fun maySignInWithoutLink(byHand: Boolean) =
            appSignIn != null && (byHand || appRuns.lastOrNull()?.let { clock() - it >= 360_000 } ?: true)
        override suspend fun signInWithoutLink(byHand: Boolean): Boolean {
            appRuns += clock()
            return appSignIn!!()
        }
        override suspend fun signInWithoutLink(target: String, byHand: Boolean): Boolean {
            appTargets += target
            return signInWithoutLink(byHand)
        }
        override fun signsInFrom(page: String?) = signsInFromPage(page)
    }

    private val processing = AppProcessing()

    private fun TestScope.flow(site: Site): BrowserSignInFlow {
        site.clock = { testScheduler.currentTime }
        val signIns = BrowserSignIns(listOf(site))
        loads = BrowserLoads(state, signIns)
        return BrowserSignInFlow(
            state, loads, signIns, backgroundScope,
            load = { url ->
                // As the activity's load: the browser asks the page to go.
                loads.requested(url)
                loaded += url
            },
            stopLoading = { stops++ },
            processing = processing,
        )
    }

    @Test
    fun `the site's sign-in page with a link due signs in at once, back to the page it came in the way of`() = runTest {
        val site = Site()
        val flow = flow(site)
        loads.requested(PAGE)

        flow.signInPageReached(site, SIGN_IN_PAGE)
        runCurrent()

        assertEquals(1, stops)
        assertEquals(listOf("$SIGNED_IN$PAGE"), loaded)
        assertNull(state.signIn)
    }

    @Test
    fun `a sign-in page another site sent the browser to waits for the student's Retry`() = runTest {
        val site = Site().apply { signsInFromPage = { it == null || it.startsWith("https://moodle2.ntust.edu.tw/") } }
        val flow = flow(site)
        loads.requested(PAGE)

        flow.signInPageReached(site, SIGN_IN_PAGE, from = "https://example.com/")
        runCurrent()

        assertEquals(1, stops)
        assertEquals(emptyList<String>(), site.asked)
        assertEquals(emptyList<String>(), loaded)
        assertEquals(PAGE, state.signIn?.target)
        assertEquals(false, state.signIn?.working)

        flow.retry()
        runCurrent()
        assertEquals(listOf("$SIGNED_IN$PAGE"), loaded)
        assertNull(state.signIn)
    }

    @Test
    fun `a sign-in page reached from the site's own page signs in by itself`() = runTest {
        val site = Site().apply { signsInFromPage = { it == null || it.startsWith("https://moodle2.ntust.edu.tw/") } }
        val flow = flow(site)
        loads.requested(PAGE)

        flow.signInPageReached(site, SIGN_IN_PAGE, from = OTHER_PAGE)
        runCurrent()

        assertEquals(listOf("$SIGNED_IN$PAGE"), loaded)
    }

    @Test
    fun `the app's own sign-in is told the page it goes on to`() = runTest {
        val site = Site(works = false, appSignIn = { true })
        val flow = flow(site)
        loads.requested(PAGE)

        flow.signInPageReached(site, SIGN_IN_PAGE)
        runCurrent()

        assertEquals(listOf(PAGE), site.appTargets)
        assertEquals(listOf(PAGE), loaded)
    }

    @Test
    fun `with no link due and no other way in, the screen says the sign-in failed and tries nothing more by itself`() = runTest {
        val site = Site(firstDueAtMs = 300_000)
        val flow = flow(site)
        loads.requested(PAGE)

        flow.signInPageReached(site, SIGN_IN_PAGE)
        runCurrent()
        assertEquals(PAGE, state.signIn?.target)
        assertEquals(false, state.signIn?.working)

        // Past the next link's time: no countdown ran to it.
        advanceTimeBy(400_000)
        runCurrent()

        assertEquals("asked once, at the sign-in page", listOf(PAGE), site.asked)
        assertEquals(emptyList<String>(), loaded)
        assertEquals(false, state.signIn?.working)
    }

    @Test
    fun `Retry once a link is due signs in with it`() = runTest {
        val site = Site(firstDueAtMs = 300_000)
        val flow = flow(site)
        loads.requested(PAGE)
        flow.signInPageReached(site, SIGN_IN_PAGE)
        runCurrent()

        advanceTimeBy(300_000)
        flow.retry()
        runCurrent()

        assertEquals(listOf("$SIGNED_IN$PAGE"), loaded)
        assertNull(state.signIn)
    }

    @Test
    fun `a sign-in that fails leaves the screen up and does not keep trying by itself`() = runTest {
        // A link due that never comes: an account with no Moodle private token, say.
        val site = Site(works = false)
        val flow = flow(site)
        loads.requested(PAGE)

        flow.signInPageReached(site, SIGN_IN_PAGE)
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(listOf(PAGE), site.asked)
        assertEquals(false, state.signIn?.working)
        assertEquals(emptyList<String>(), loaded)
    }

    @Test
    fun `a site that bounces its link back to its sign-in page ends on the failed screen rather than loop`() = runTest {
        val site = Site()
        val flow = flow(site)
        flow.signIn(site, PAGE)
        runCurrent()

        // Moodle did not take the link, which the WebView may report as a page start first.
        loads.started(loaded.last())
        loads.finished(loaded.last())
        flow.signInPageReached(site, SIGN_IN_PAGE)
        advanceTimeBy(60_000)
        runCurrent()

        // Asked again at the sign-in page, where Moodle's 6 minutes have not passed.
        assertEquals(listOf(PAGE, PAGE), site.asked)
        assertEquals(PAGE, state.signIn?.target)
        assertEquals(false, state.signIn?.working)
        assertEquals(listOf("$SIGNED_IN$PAGE"), loaded)
    }

    @Test
    fun `the sign-in page reported twice while a link is on its way asks for one link`() = runTest {
        val site = Site(takesMs = 1_000)
        val flow = flow(site)
        loads.requested(PAGE)

        flow.signInPageReached(site, SIGN_IN_PAGE)
        runCurrent()
        // The same navigation, through the page's start as well.
        flow.signInPageReached(site, SIGN_IN_PAGE)
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(listOf(PAGE), site.asked)
        assertEquals(listOf("$SIGNED_IN$PAGE"), loaded)
    }

    @Test
    fun `once signed in, a later sign-in goes on to the page it came in the way of, not the first`() = runTest {
        val site = Site(firstDueAtMs = 0, intervalMs = 1_000)
        val flow = flow(site)
        flow.signIn(site, PAGE)
        runCurrent()
        loads.started(PAGE)
        loads.finished(PAGE)

        // Later the student opens another page, and Moodle has signed the browser out meanwhile.
        advanceTimeBy(5_000)
        loads.requested(OTHER_PAGE)
        flow.signInPageReached(site, SIGN_IN_PAGE)
        runCurrent()

        assertEquals(listOf(PAGE, OTHER_PAGE), site.asked)
        assertEquals("$SIGNED_IN$OTHER_PAGE", loaded.last())
    }

    @Test
    fun `leaving the sign-in while its link is on its way loads nothing and forgets it`() = runTest {
        val site = Site(takesMs = 1_000)
        val flow = flow(site)
        loads.requested(PAGE)
        flow.signInPageReached(site, SIGN_IN_PAGE)
        runCurrent()

        // Back, to the page before.
        flow.leave()
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(emptyList<String>(), loaded)
        assertNull(state.signIn)
        assertNull(loads.signInTarget)
    }

    @Test
    fun `leaving the failed screen forgets it`() = runTest {
        val site = Site(firstDueAtMs = 3_000)
        val flow = flow(site)
        loads.requested(PAGE)
        flow.signInPageReached(site, SIGN_IN_PAGE)
        runCurrent()

        flow.leave()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(listOf(PAGE), site.asked)
        assertNull(state.signIn)
        assertNull(loads.signInTarget)
    }

    @Test
    fun `cancelling stops a try on its way, as the activity's Main dispatcher runs it`() = runTest(UnconfinedTestDispatcher()) {
        // As the activity's Main.immediate: a launch runs at once, up to its first wait.
        val site = Site(takesMs = 1_000)
        val flow = flow(site)

        flow.signIn(site, PAGE)
        flow.cancel()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(listOf(PAGE), site.asked)
        assertEquals(emptyList<String>(), loaded)
    }

    // --- the app's own sign-in, when no link can be had ---

    @Test
    fun `with a link due, the app never signs in itself`() = runTest {
        val site = Site(appSignIn = { true })
        val flow = flow(site)

        flow.signIn(site, PAGE)
        runCurrent()

        assertEquals(listOf("$SIGNED_IN$PAGE"), loaded)
        assertTrue(site.appRuns.isEmpty())
    }

    @Test
    fun `with no link to be had, the app signs in itself behind the spinner, then loads the page`() = runTest {
        val site = Site(firstDueAtMs = 300_000, appSignIn = { delay(2_000); true })
        val flow = flow(site)

        flow.signIn(site, PAGE)
        runCurrent()
        assertEquals("the spinner is held while it runs", 1, processing.holds.value)
        assertEquals(emptyList<String>(), loaded)

        advanceTimeBy(2_001)
        runCurrent()

        assertEquals(listOf(PAGE), loaded)
        assertNull(state.signIn)
        assertEquals(0, processing.holds.value)
    }

    @Test
    fun `Moodle's sign-in page with no link due tries the app's own sign-in`() = runTest {
        val site = Site(firstDueAtMs = 300_000, appSignIn = { true })
        val flow = flow(site)
        loads.requested(OTHER_PAGE)

        flow.signInPageReached(site, SIGN_IN_PAGE)
        runCurrent()

        assertEquals(listOf(OTHER_PAGE), loaded)
        assertEquals(1, site.appRuns.size)
    }

    @Test
    fun `an app sign-in that fails shows the screen, and is not tried again by itself`() = runTest {
        val site = Site(firstDueAtMs = 300_000, appSignIn = { false })
        val flow = flow(site)
        loads.requested(PAGE)

        flow.signInPageReached(site, SIGN_IN_PAGE)
        runCurrent()
        assertEquals(PAGE, state.signIn?.target)
        assertEquals(false, state.signIn?.working)
        assertEquals(0, processing.holds.value)

        flow.signInPageReached(site, SIGN_IN_PAGE)
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(1, site.appRuns.size)
        assertEquals(emptyList<String>(), loaded)
    }

    @Test
    fun `a page that bounces back after the app's sign-in shows the screen rather than loop`() = runTest {
        val site = Site(firstDueAtMs = 300_000, appSignIn = { true })
        val flow = flow(site)
        flow.signIn(site, PAGE)
        runCurrent()
        assertEquals(listOf(PAGE), loaded)

        // Moodle did not keep the session: the page starts, then sends the browser to sign in.
        loads.started(PAGE)
        loads.finished(PAGE)
        flow.signInPageReached(site, SIGN_IN_PAGE)
        runCurrent()

        assertEquals(1, site.appRuns.size)
        assertEquals(PAGE, state.signIn?.target)
        assertEquals(listOf(PAGE), loaded)
    }

    @Test
    fun `Retry by hand may try the app's own sign-in once more`() = runTest {
        val site = Site(firstDueAtMs = 300_000, appSignIn = { false })
        val flow = flow(site)
        loads.requested(PAGE)
        flow.signInPageReached(site, SIGN_IN_PAGE)
        runCurrent()

        site.appSignIn = { true }
        flow.retry()
        runCurrent()

        assertEquals(2, site.appRuns.size)
        assertEquals(listOf(PAGE), loaded)
    }

    private companion object {
        const val PAGE = "https://moodle2.ntust.edu.tw/user/edit.php"
        const val OTHER_PAGE = "https://moodle2.ntust.edu.tw/course/view.php?id=2"
        const val SIGN_IN_PAGE = "https://moodle2.ntust.edu.tw/login/index.php"
        const val SIGNED_IN = "https://moodle2.ntust.edu.tw/admin/tool/mobile/autologin.php?key=k&urltogo="
    }
}
