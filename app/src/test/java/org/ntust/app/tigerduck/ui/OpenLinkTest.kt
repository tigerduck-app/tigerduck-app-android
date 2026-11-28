package org.ntust.app.tigerduck.ui

import android.app.Application
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.FileUriExposedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.browser.BrowserCleanup
import org.ntust.app.tigerduck.browser.BrowserLinksProfile
import org.ntust.app.tigerduck.browser.FakeWebViewProfiles
import org.ntust.app.tigerduck.ui.screen.browser.BrowserOpening
import org.ntust.app.tigerduck.ui.screen.browser.InAppBrowser
import org.ntust.app.tigerduck.ui.screen.browser.InAppBrowserActivity
import org.ntust.app.tigerduck.ui.screen.coursemoodle.openMoodleContentLink
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OpenLinkTest {

    private lateinit var context: Application
    private val url = "https://tigerduck.app/privacy-policy"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // Otherwise Robolectric "starts" any intent, whether or not an activity would handle it.
        shadowOf(context).checkActivities(true)
    }

    @After
    fun restore() {
        InAppBrowser.canClearEverything = { false }
        InAppBrowser.linksProfile = BrowserLinksProfile.shared
    }

    /** A phone whose WebView keeps profiles apart and can be cleared, with no clearing owed. */
    private fun linksBrowserCanOpen() {
        InAppBrowser.canClearEverything = { true }
        InAppBrowser.linksProfile = BrowserLinksProfile(FakeWebViewProfiles())
        BrowserCleanup(context).run { markDone(markPending()) }
    }

    /** Whether [intent] starts the links browser, which loads in the links profile, never the app's own. */
    private fun opensLinksBrowser(intent: Intent): Boolean =
        intent.component?.className == InAppBrowserActivity::class.java.name &&
            InAppBrowser.opensLinks(intent) && BrowserOpening.of(intent).profile == BrowserLinksProfile.NAME

    private fun installBrowser() = installHandler(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    /** An app on the phone that takes [intent], so only openLink itself can refuse it. */
    private fun installHandler(intent: Intent) {
        val handler = ComponentName("com.example.handler", "com.example.handler.Main")
        val packages = shadowOf(context.packageManager)
        packages.addActivityIfNotPresent(handler)
        packages.addIntentFilterForActivity(
            handler,
            IntentFilter(intent.action).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                intent.data?.scheme?.let(::addDataScheme)
            },
        )
    }

    private fun started(): Intent? = shadowOf(context).nextStartedActivity

    @Test
    fun `the system browser setting hands the link to the default browser`() {
        installBrowser()
        assertTrue(openLink(context, url, BrowserPreference.SYSTEM))
        val intent = started()!!
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(url, intent.dataString)
        assertFalse("not a Custom Tab", intent.hasExtra(CUSTOM_TAB_SESSION))
    }

    @Test
    fun `the in-app setting opens an https link in the TigerDuck browser's links profile`() {
        installBrowser()
        linksBrowserCanOpen()
        assertTrue(openLink(context, url, BrowserPreference.IN_APP))
        val intent = started()!!
        assertTrue(opensLinksBrowser(intent))
        assertEquals(url, intent.getStringExtra(InAppBrowser.EXTRA_URL))
        assertFalse("not a Custom Tab", intent.hasExtra(CUSTOM_TAB_SESSION))
    }

    @Test
    fun `the in-app setting hands an http link to the default browser`() {
        val http = "http://example.com/page"
        installHandler(Intent(Intent.ACTION_VIEW, Uri.parse(http)))
        linksBrowserCanOpen()
        assertTrue(openLink(context, http, BrowserPreference.IN_APP))
        val intent = started()!!
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(http, intent.dataString)
        assertNull(intent.component)
    }

    @Test
    fun `where the links browser cannot open, the in-app setting opens the default browser, never the app's own profile`() {
        installBrowser()
        listOf(
            "no profiles apart" to { InAppBrowser.linksProfile = BrowserLinksProfile(FakeWebViewProfiles(supported = false)) },
            "a WebView that cannot be cleared" to { InAppBrowser.canClearEverything = { false } },
            "a clearing owed" to { BrowserCleanup(context).markPending() },
        ).forEach { (why, cannotOpen) ->
            linksBrowserCanOpen()
            cannotOpen()
            assertTrue(why, openLink(context, url, BrowserPreference.IN_APP))
            val intent = started()!!
            assertEquals(why, Intent.ACTION_VIEW, intent.action)
            assertEquals(why, url, intent.dataString)
            assertNull(why, intent.component)
        }
    }

    @Test
    fun `with no browser on the phone it reports failure instead of throwing`() {
        listOf(BrowserPreference.SYSTEM, BrowserPreference.IN_APP).forEach { preference ->
            assertFalse(preference, openLink(context, url, preference))
            assertNull(preference, started())
        }
    }

    @Test
    fun `an application context gets a new task, which it cannot start an activity without`() {
        installBrowser()
        listOf(BrowserPreference.SYSTEM, BrowserPreference.IN_APP).forEach { preference ->
            assertTrue(preference, openLink(context, url, preference))
            assertTrue(preference, started()!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        }
    }

    @Test
    fun `a file link is refused, even with an app that would view it`() {
        // Handing a file:// URI to another app throws FileUriExposedException on every
        // supported Android, and no web page needs one.
        val file = "file:///sdcard/Download/notes.pdf"
        installHandler(Intent(Intent.ACTION_VIEW, Uri.parse(file)))
        listOf(BrowserPreference.SYSTEM, BrowserPreference.IN_APP).forEach { preference ->
            assertFalse(preference, openLink(context, file, preference))
            assertNull(preference, started())
        }
    }

    @Test
    fun `a javascript link is refused, even with a browser that would run it`() {
        val script = "javascript:alert(1)"
        installHandler(Intent(Intent.ACTION_VIEW, Uri.parse(script)))
        listOf(BrowserPreference.SYSTEM, BrowserPreference.IN_APP).forEach { preference ->
            assertFalse(preference, openLink(context, script, preference))
            assertNull(preference, started())
        }
    }

    @Test
    fun `a mail link opens a new message rather than a browser`() {
        val mail = "mailto:someone@example.com"
        installHandler(Intent(Intent.ACTION_SENDTO, Uri.parse(mail)))
        listOf(BrowserPreference.SYSTEM, BrowserPreference.IN_APP).forEach { preference ->
            assertTrue(preference, openLink(context, mail, preference))
            val intent = started()!!
            assertEquals(preference, Intent.ACTION_SENDTO, intent.action)
            assertEquals(preference, mail, intent.dataString)
            assertFalse("not a Custom Tab", intent.hasExtra(CUSTOM_TAB_SESSION))
        }
    }

    @Test
    fun `an upper-case scheme still reaches the browser`() {
        // Android matches intent schemes case-sensitively, unlike RFC 3986.
        installBrowser()
        assertTrue(openLink(context, "HTTPS://tigerduck.app/privacy-policy", BrowserPreference.SYSTEM))
        assertEquals(url, started()!!.dataString)
    }

    @Test
    fun `a launch the platform refuses is reported, not thrown`() {
        val refusing = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) = throw FileUriExposedException("refused")

            // An activity may launch through this overload.
            override fun startActivity(intent: Intent, options: Bundle?) = throw FileUriExposedException("refused")
        }
        listOf(BrowserPreference.SYSTEM, BrowserPreference.IN_APP).forEach { preference ->
            assertFalse(preference, openLink(refusing, url, preference))
        }
    }

    @Test
    fun `a Moodle content link to another site goes through the guarded opener`() {
        // A link that is neither mail nor a Moodle page must follow the setting and refuse what
        // openLink refuses, not keep its own copy of the old unguarded code.
        installBrowser()
        linksBrowserCanOpen()
        openMoodleContentLink(context, url, BrowserPreference.IN_APP)
        assertTrue("the links browser", opensLinksBrowser(started()!!))

        val file = "file:///sdcard/Download/notes.pdf"
        installHandler(Intent(Intent.ACTION_VIEW, Uri.parse(file)))
        openMoodleContentLink(context, file, BrowserPreference.SYSTEM)
        assertNull(started())
    }

    // tigerduck-app-android#249: a tap on a link that nothing could open did nothing at all.

    @Test
    fun `a link nothing can open says so`() {
        listOf(BrowserPreference.SYSTEM, BrowserPreference.IN_APP).forEach { preference ->
            ShadowToast.reset()
            assertFalse(preference, openLink(context, url, preference))
            assertEquals(preference, context.getString(R.string.error_link_no_app), ShadowToast.getTextOfLatestToast())
        }
    }

    @Test
    fun `a refused link says so too`() {
        assertFalse(openLink(context, "javascript:alert(1)", BrowserPreference.SYSTEM))
        assertEquals(context.getString(R.string.error_link_no_app), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `a link that opens says nothing`() {
        installBrowser()
        assertTrue(openLink(context, url, BrowserPreference.SYSTEM))
        assertNull(ShadowToast.getLatestToast())
    }

    @Test
    fun `a mail link in Moodle content with no mail app says so`() {
        openMoodleContentLink(context, "mailto:ta@mail.ntust.example", BrowserPreference.SYSTEM)

        assertNull(started())
        assertEquals(context.getString(R.string.error_link_no_app), ShadowToast.getTextOfLatestToast())
    }

    private companion object {
        /** What a Custom Tab's intent carries; none is started any more. */
        const val CUSTOM_TAB_SESSION = "android.support.customtabs.extra.SESSION"
    }
}
