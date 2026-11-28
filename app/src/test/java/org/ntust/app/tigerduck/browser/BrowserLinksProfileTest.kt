package org.ntust.app.tigerduck.browser

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one profile the links the app was handed open in, signed in to nothing of the app's, and its clearing. */
class BrowserLinksProfileTest {

    @Test
    fun `its clearing deletes everything the links profile keeps, and only that`() = runTest {
        val profiles = FakeWebViewProfiles()

        val clearing = BrowserLinksProfile(profiles).clear(timeoutMs = 1_000)

        assertEquals(listOf(BrowserLinksProfile.NAME), profiles.cleared)
        assertTrue(clearing.inTime)
        assertTrue(clearing.settled())
    }

    @Test
    fun `its name is neither the app's own profile nor one opened at risk, which a sweep deletes`() {
        assertFalse(BrowserLinksProfile.NAME.equals("Default", ignoreCase = true))
        assertFalse(BrowserLinksProfile.NAME.startsWith(BrowserRiskProfiles.PREFIX))
    }

    @Test
    fun `where the WebView keeps no profile apart there is nothing of it to clear`() = runTest {
        val profiles = FakeWebViewProfiles(supported = false)

        val clearing = BrowserLinksProfile(profiles).clear(timeoutMs = 1_000)

        assertTrue(profiles.cleared.isEmpty())
        assertTrue(clearing.inTime)
    }

    @Test
    fun `a clearing that failed is not done, so a sign-out's clearing stays owed`() = runTest {
        val failing = object : WebViewProfiles by FakeWebViewProfiles() {
            override fun clear(name: String, done: () -> Unit) = throw UnsupportedOperationException("older WebView")
        }

        val clearing = BrowserLinksProfile(failing).clear(timeoutMs = 1_000)

        assertFalse(clearing.inTime)
        assertFalse(clearing.settled())
    }

    @Test
    fun `a clearing that has not reported is not in time, and is settled once it does`() = runTest {
        var report: (() -> Unit)? = null
        val late = object : WebViewProfiles by FakeWebViewProfiles() {
            override fun clear(name: String, done: () -> Unit) {
                report = done
            }
        }

        val clearing = BrowserLinksProfile(late).clear(timeoutMs = 10)
        assertFalse(clearing.inTime)
        assertFalse("the browser stays closed meanwhile", BrowserSession.usable)

        report!!.invoke()
        assertTrue(clearing.settled())
        assertTrue(BrowserSession.usable)
    }
}
