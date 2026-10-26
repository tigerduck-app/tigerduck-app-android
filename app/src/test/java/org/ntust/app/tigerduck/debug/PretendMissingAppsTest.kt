package org.ntust.app.tigerduck.debug

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.ui.screen.coursemoodle.defaultBrowserPackage
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.content.IntentFilter

/**
 * [PretendMissingApps], the debug switches that make the app act as on a phone
 * without the Moodle app, a browser, a mail app, an app for a file, or the
 * Play Store.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PretendMissingAppsTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    // The running flavor's package: fdroid's has its own suffix, and its manifest takes the share links too.
    private val own: String get() = context.packageName

    @Before
    fun start() {
        context.getSharedPreferences("debug_outside_apps", Context.MODE_PRIVATE).edit().clear().commit()
        PretendMissingApps.init(context)
    }

    @After
    fun stop() {
        OutsideApp.entries.forEach { PretendMissingApps.setMissing(it, false) }
    }

    private fun view(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri))

    /** A browser, as one registers: an activity that takes every http and https link. */
    private fun installBrowser() {
        val browser = ComponentName("com.example.browser", "Main")
        shadowOf(context.packageManager).addActivityIfNotPresent(browser)
        shadowOf(context.packageManager).addIntentFilterForActivity(
            browser,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("http")
                addDataScheme("https")
            },
        )
    }

    @Test
    fun `each intent is put down to the app it needs`() {
        assertEquals(OutsideApp.MOODLE, PretendMissingApps.needs(view("moodlemobile://https://moodle2.ntust.edu.tw?redirect=/my/"), own))
        assertEquals(OutsideApp.BROWSER, PretendMissingApps.needs(view("https://www.ntust.edu.tw/"), own))
        assertEquals(OutsideApp.MAIL, PretendMissingApps.needs(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:someone@example.com")), own))
        assertEquals(OutsideApp.FILE_VIEWER, PretendMissingApps.needs(view("content://org.ntust.app.tigerduck.mailfiles/a.pdf"), own))
        assertEquals(OutsideApp.PLAY_STORE, PretendMissingApps.needs(view("market://details?id=org.ntust.app.tigerduck"), own))
    }

    @Test
    fun `an intent to the app itself needs nothing from outside`() {
        val inApp = view("https://moodle2.ntust.edu.tw/").setComponent(ComponentName(own, "$own.ui.screen.browser.InAppBrowserActivity"))
        assertNull(PretendMissingApps.needs(inApp, own))
        assertNull(PretendMissingApps.needs(Intent(Intent.ACTION_SEND).setType("text/plain"), own))
    }

    @Test
    fun `a link an app opens itself needs no browser, TigerDuck's own share links included`() {
        // A browser takes any https link; TigerDuck's own manifest takes its share links.
        installBrowser()
        val shareLink = view("https://share.tigerduck.app/classtable/abc")
        PretendMissingApps.setMissing(OutsideApp.BROWSER, true)

        assertNull(PretendMissingApps.needs(shareLink, own, context.packageManager))
        // With no browser, the link goes to the one app that takes it, not to a choice with browsers in it.
        assertEquals(own, PretendMissingApps.filter(shareLink, own, context.packageManager).`package`)
        // A link only browsers take still needs one.
        val page = view("https://www.ntust.edu.tw/")
        assertEquals(OutsideApp.BROWSER, PretendMissingApps.needs(page, own, context.packageManager))
    }

    @Test
    fun `a missing app's intent fails as it would on a phone without it`() {
        PretendMissingApps.setMissing(OutsideApp.MOODLE, true)

        assertThrows(ActivityNotFoundException::class.java) {
            PretendMissingApps.filter(view("moodlemobile://https://moodle2.ntust.edu.tw"), own)
        }
        val web = view("https://moodle2.ntust.edu.tw/")
        assertSame(web, PretendMissingApps.filter(web, own))
    }

    @Test
    fun `a chooser for a missing app is left with nothing to offer`() {
        PretendMissingApps.setMissing(OutsideApp.FILE_VIEWER, true)
        val chooser = Intent.createChooser(view("content://org.ntust.app.tigerduck.mailfiles/a.pdf"), "a.pdf")

        val filtered = PretendMissingApps.filter(chooser, own)

        assertEquals(Intent.ACTION_CHOOSER, filtered.action)
        @Suppress("DEPRECATION")
        val inner = filtered.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertNull(PretendMissingApps.needs(inner, own))
        assertTrue(context.packageManager.queryIntentActivities(inner, 0).isEmpty())
    }

    @Test
    fun `the switches outlast a restart`() {
        PretendMissingApps.setMissing(OutsideApp.BROWSER, true)

        PretendMissingApps.init(context)

        assertEquals(setOf(OutsideApp.BROWSER), PretendMissingApps.missing.value)
    }

    @Test
    fun `with the browser missing, no default browser is found`() {
        installBrowser()
        assertEquals("com.example.browser", defaultBrowserPackage(context.packageManager))

        PretendMissingApps.setMissing(OutsideApp.BROWSER, true)

        assertNull(defaultBrowserPackage(context.packageManager))
    }
}
