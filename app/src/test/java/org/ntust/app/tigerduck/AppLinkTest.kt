package org.ntust.app.tigerduck

import android.app.Application
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.ui.navigation.Screen
import org.ntust.app.tigerduck.widget.LibraryShortcutWidget
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What a `tigerduck://` link opens. [MainActivity] is exported, so any app can
 * send one: everything that is not exactly one of the four shapes must come
 * back null.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppLinkTest {

    private fun parse(link: String) = AppLink.parse(Uri.parse(link))

    @Test
    fun `the four links iOS opens parse`() {
        assertEquals(AppLink.Library, parse("tigerduck://library"))
        assertEquals(AppLink.ClassTable, parse("tigerduck://classtable"))
        assertEquals(AppLink.Assignment("900001"), parse("tigerduck://assignment/900001"))
        assertEquals(AppLink.Announcement(42), parse("tigerduck://announcement/42"))
    }

    @Test
    fun `a query or a trailing slash changes nothing`() {
        assertEquals(AppLink.Library, parse("tigerduck://library/"))
        assertEquals(AppLink.ClassTable, parse("tigerduck://classtable?from=shortcut"))
        assertEquals(AppLink.Assignment("7"), parse("tigerduck://assignment/7?x=y#z"))
    }

    @Test
    fun `another scheme or host is not a link`() {
        assertNull(parse("https://library"))
        assertNull(parse("TigerDuck://library"))
        assertNull(parse("tigerduck://Library"))
        assertNull(parse("tigerduck://home"))
        assertNull(parse("tigerduck://server-push/abc"))
        assertNull(parse("tigerduck:library"))
        assertNull(AppLink.parse(null))
    }

    @Test
    fun `an assignment id is ASCII digits and nothing else`() {
        assertNull(parse("tigerduck://assignment"))
        assertNull(parse("tigerduck://assignment/"))
        assertNull(parse("tigerduck://assignment/abc"))
        assertNull(parse("tigerduck://assignment/12a"))
        assertNull(parse("tigerduck://assignment/-12"))
        assertNull(parse("tigerduck://assignment/+12"))
        assertNull(parse("tigerduck://assignment/1.5"))
        // Arabic-Indic digits pass Char.isDigit.
        assertNull(parse("tigerduck://assignment/١٢"))
        // Decoded before it is checked: an encoded slash is still a slash.
        assertNull(parse("tigerduck://assignment/12%2F34"))
        assertNull(parse("tigerduck://assignment/12/34"))
        assertNull(parse("tigerduck://assignment/..%2F..%2Fsettings"))
        assertNull(parse("tigerduck://assignment/https:%2F%2Fexample.com"))
        assertNull(parse("tigerduck://assignment/" + "1".repeat(19)))
        assertEquals(AppLink.Assignment("1".repeat(18)), parse("tigerduck://assignment/" + "1".repeat(18)))
    }

    @Test
    fun `library and class table take no path`() {
        assertNull(parse("tigerduck://library/settings"))
        assertNull(parse("tigerduck://classtable/1131"))
    }

    @Test
    fun `an announcement id must fit an Int and be digits`() {
        assertNull(parse("tigerduck://announcement/-1"))
        assertNull(parse("tigerduck://announcement/9999999999"))
        assertNull(parse("tigerduck://announcement/1/2"))
    }

    @Test
    fun `a link round-trips through its URI`() {
        listOf(
            AppLink.Library,
            AppLink.ClassTable,
            AppLink.Assignment("123"),
            AppLink.Announcement(9),
        ).forEach { assertEquals(it, AppLink.parse(it.uri)) }
    }

    @Test
    fun `only an id a link can carry makes an assignment link`() {
        assertEquals(AppLink.Assignment("55"), AppLink.assignment("55"))
        assertNull(AppLink.assignment("debug-1"))
        assertNull(AppLink.assignment(""))
    }

    @Test
    fun `each screen link maps onto its route`() {
        assertEquals(LibraryShortcutWidget.ROUTE_SENTINEL, startRouteFor(AppLink.Library))
        assertEquals(Screen.ClassTable.route, startRouteFor(AppLink.ClassTable))
        assertEquals(Screen.AnnouncementDetail.route(42), startRouteFor(AppLink.Announcement(42)))
        assertEquals(Screen.Assignment.route("1"), startRouteFor(AppLink.Assignment("1")))
    }

    @Test
    fun `a browser or another app can open each link`() {
        val packageManager = RuntimeEnvironment.getApplication().packageManager
        listOf(
            "tigerduck://library",
            "tigerduck://classtable",
            "tigerduck://assignment/900001",
            "tigerduck://announcement/42",
        ).forEach { link ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
                .addCategory(Intent.CATEGORY_BROWSABLE)
            val resolved = packageManager.resolveActivity(intent, 0)
            assertEquals(link, MainActivity::class.java.name, resolved?.activityInfo?.name)
        }
    }

    @Test
    fun `the app's own tap opens the link in MainActivity`() {
        val context = RuntimeEnvironment.getApplication()

        val intent = AppLink.Assignment("31").activityIntent(context)

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals("tigerduck://assignment/31", intent.dataString)
    }
}
