package org.ntust.app.tigerduck

import org.ntust.app.tigerduck.mail.MailRoutes
import org.ntust.app.tigerduck.ui.navigation.Screen
import org.ntust.app.tigerduck.widget.LibraryShortcutWidget

/**
 * The [EXTRA] the app's own notifications put on a [MainActivity] intent to
 * open a screen, and the routes it may name.
 *
 * MainActivity is exported, so the extra can arrive on any app's intent too.
 * [accepted] lets through exactly the routes the app itself sends — School
 * Mail's notifications, and the class table and Library widgets as an older
 * build drew them — and drops everything else, so no other screen can be
 * reached this way and a route the NavHost does not have never reaches it.
 * None of those senders puts a `tigerduck://` link or
 * [android.content.Intent.CATEGORY_BROWSABLE] on its intent, so
 * `startRouteOf` reads the extra only from an intent with neither: a link
 * from a browser or another app opens the link alone. A URI of another
 * scheme, such as the `glance-action:` one Glance puts on an older widget's
 * tap, leaves the extra to be read.
 */
object StartRoute {
    const val EXTRA = "start_route"

    private val FIXED = setOf(
        // BaseClassTableWidget and LibraryShortcutWidget open tigerduck://
        // links now, but a widget an older build drew keeps its tap, extra
        // and all, until the launcher has it redrawn. Both screens open from
        // a link anyway.
        Screen.ClassTable.route,
        LibraryShortcutWidget.ROUTE_SENTINEL,
        // AndroidMailNotifier's summary, and the developer menu's preview of it
        MailRoutes.LIST,
    )

    /**
     * AndroidMailNotifier's per-mail tap, [MailRoutes.message]: a folder as
     * URLEncoder writes it, then a UID, which must fit the Long the NavHost
     * reads it into.
     */
    private val MAIL_MESSAGE = Regex("""schoolMail/message/(?:[A-Za-z0-9._*\-]|%[0-9A-Fa-f]{2})+/(\d{1,19})""")

    /** [route] when it is one the app sends, else null. */
    fun accepted(route: String?): String? =
        route?.takeIf { it in FIXED || isMailMessage(it) }

    private fun isMailMessage(route: String): Boolean =
        MAIL_MESSAGE.matchEntire(route)?.groupValues?.get(1)?.toLongOrNull() != null
}
