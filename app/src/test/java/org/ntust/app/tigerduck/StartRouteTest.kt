package org.ntust.app.tigerduck

import android.app.Application
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.mail.MailRoutes
import org.ntust.app.tigerduck.ui.navigation.Screen
import org.ntust.app.tigerduck.widget.LibraryShortcutWidget
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which `start_route` extras MainActivity follows. It is exported, so any app
 * can put one on an intent: only the routes the app's own widgets and
 * notifications use may come through, and anything else must be dropped,
 * never handed to the NavHost.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class StartRouteTest {

    private fun routeFor(extra: String?, link: String? = null): String? = startRouteOf(
        Intent().apply {
            if (extra != null) putExtra("start_route", extra)
            if (link != null) data = Uri.parse(link)
        }
    )

    @Test
    fun `the routes the app's widgets and notifications use come through`() {
        assertEquals(Screen.ClassTable.route, routeFor(Screen.ClassTable.route))
        assertEquals(LibraryShortcutWidget.ROUTE_SENTINEL, routeFor(LibraryShortcutWidget.ROUTE_SENTINEL))
        assertEquals(MailRoutes.LIST, routeFor(MailRoutes.LIST))
        for (folder in listOf("INBOX", "INBOX.Sent", "[Gmail]/Sent Mail", "寄件備份")) {
            val route = MailRoutes.message(folder, 42)
            assertEquals(folder, route, routeFor(route))
        }
    }

    @Test
    fun `any other route is dropped`() {
        listOf(
            Screen.Settings.route,
            Screen.Debug.route,
            Screen.NotificationDebug.route,
            Screen.ApiEndpointDebug.route,
            Screen.CloudSync.route,
            Screen.AnnouncementDetail.route(1),
            MailRoutes.compose(org.ntust.app.tigerduck.mail.ComposeMode.NEW),
            MailRoutes.SETTINGS,
            "no-such-route",
            "classTable/x",
            "schoolMail/message/INBOX/abc",
            "schoolMail/message/INBOX/-1",
            "schoolMail/message/a/b/1",
            "schoolMail/message//1",
            "schoolMail/message/%ZZ/1",
            "schoolMail/message/INBOX/1?x=1",
            // Past Long.MAX_VALUE: the NavHost cannot read the UID and throws.
            "schoolMail/message/INBOX/9223372036854775808",
            "schoolMail/message/INBOX/9999999999999999999",
            "schoolMail/message/INBOX/12345678901234567890",
            "",
        ).forEach { assertNull(it, routeFor(it)) }
    }

    @Test
    fun `the largest UID a Long holds still comes through`() {
        val route = "schoolMail/message/INBOX/9223372036854775807"
        assertEquals(route, routeFor(route))
    }

    @Test
    fun `a link from a browser or another app cannot carry a route`() {
        val browsable = Intent(Intent.ACTION_VIEW, Uri.parse("tigerduck://library"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .putExtra("start_route", MailRoutes.LIST)
        assertEquals(LibraryShortcutWidget.ROUTE_SENTINEL, startRouteOf(browsable))

        val browsableNoLink = Intent().addCategory(Intent.CATEGORY_BROWSABLE)
            .putExtra("start_route", Screen.ClassTable.route)
        assertNull(startRouteOf(browsableNoLink))

        assertEquals(
            "an intent with a link opens the link",
            Screen.ClassTable.route,
            routeFor(MailRoutes.LIST, link = "tigerduck://classtable"),
        )
    }

    @Test
    fun `a URI that is not a tigerduck link leaves the extra to open`() {
        // What Glance puts on a widget intent that has no data of its own.
        val glance = "glance-action:/CALLBACK?appWidgetId=1&viewId=2&extraData="
        assertEquals(MailRoutes.LIST, routeFor(MailRoutes.LIST, link = glance))
        assertNull("the extra is still checked", routeFor(Screen.Settings.route, link = glance))
    }

    @Test
    fun `a widget an older build drew still opens its screen`() {
        val glance = "glance-action:/CALLBACK?appWidgetId=7&viewId=3&extraData="
        assertEquals(Screen.ClassTable.route, routeFor(Screen.ClassTable.route, link = glance))
        assertEquals(LibraryShortcutWidget.ROUTE_SENTINEL, routeFor(LibraryShortcutWidget.ROUTE_SENTINEL, link = glance))
    }

    @Test
    fun `a browser's intent carries no route, whatever its link`() {
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .putExtra("start_route", MailRoutes.LIST)
        assertNull(startRouteOf(web))
    }

    @Test
    fun `a dropped route leaves the intent's link to open`() {
        assertEquals(Screen.ClassTable.route, routeFor("debug", link = "tigerduck://classtable"))
        assertNull(routeFor(null, link = "tigerduck://home"))
    }
}
