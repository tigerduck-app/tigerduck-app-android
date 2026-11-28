package org.ntust.app.tigerduck.ui.screen.announcements

import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.browser.BrowserCleanup
import org.ntust.app.tigerduck.browser.BrowserLinksProfile
import org.ntust.app.tigerduck.browser.FakeWebViewProfiles
import org.ntust.app.tigerduck.ui.screen.browser.InAppBrowser
import org.ntust.app.tigerduck.ui.screen.browser.InAppBrowserActivity
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BulletinSourceLinkTest {
    // An activity, as on screen.
    private val context = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private val url = "https://www.ntust.edu.tw/p/406-1000-1.php"

    @Test
    fun `the system browser setting opens the source in the browser, not an in-app tab`() {
        openBulletinSource(context, url, "system")
        val started = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(url, started.dataString)
        assertFalse(InAppBrowser.opensLinks(started))
    }

    @Test
    fun `the in-app setting opens it in the TigerDuck browser's links profile`() {
        InAppBrowser.canClearEverything = { true }
        InAppBrowser.linksProfile = BrowserLinksProfile(FakeWebViewProfiles())
        BrowserCleanup(context).run { markDone(markPending()) }
        try {
            openBulletinSource(context, url, "inApp")
            val started = shadowOf(context).nextStartedActivity
            assertEquals(InAppBrowserActivity::class.java.name, started.component?.className)
            assertEquals(url, started.getStringExtra(InAppBrowser.EXTRA_URL))
            assertTrue(InAppBrowser.opensLinks(started))
        } finally {
            InAppBrowser.canClearEverything = { false }
            InAppBrowser.linksProfile = BrowserLinksProfile.shared
        }
    }

    @Test
    fun `a source that is not a web page opens nothing, whatever the setting`() {
        listOf("javascript:alert(1)", "intent://x#Intent;scheme=x;end", "tel:0227333141", "file:///sdcard/x", "custom://open").forEach { href ->
            listOf("system", "inApp").forEach { preference ->
                openBulletinSource(context, href, preference)
                assertNull("$href ($preference)", shadowOf(context).nextStartedActivity)
            }
        }
    }

    @Test
    fun `an upper-case scheme is still a web page`() {
        openBulletinSource(context, "HTTPS://www.ntust.edu.tw/", "system")
        assertEquals(Intent.ACTION_VIEW, shadowOf(context).nextStartedActivity.action)
    }
}
