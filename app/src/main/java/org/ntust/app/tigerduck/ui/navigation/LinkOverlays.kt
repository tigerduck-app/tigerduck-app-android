package org.ntust.app.tigerduck.ui.navigation

import androidx.navigation.NavController

/**
 * What a link from a reminder, a bulletin, School Mail, a widget or the Live Update closes over
 * the pages on its way to another page, so none of it is left over the page the link opens, as
 * on iOS (tigerduck-app/tigerduck-app#672). Each belongs to the page the link leaves, and closes
 * as its own close does:
 *
 * - the course sheet, which would otherwise come back over its page on the way back;
 * - the Class Table tab's menu;
 * - the school sign-in sheet, as a tap outside it does, with the password typed into it; a
 *   sign-in under way holds it open until it ends
 *   ([org.ntust.app.tigerduck.ui.screen.signin.signInSheetClosesForLink]);
 * - the class table import, as its Close does, unless the link is to the class table: the import
 *   is that page's, and a share link has just opened one over it. A lookup under way saves
 *   nothing, as when a share link takes the place of an import. A person already being written,
 *   which Close cannot stop either, keeps it up until saved, or until it says the write failed,
 *   and then leaves the link's page on screen rather than show the class table
 *   ([org.ntust.app.tigerduck.ui.screen.classtable.sharing.ClassTableImportModel.staysForLink]);
 * - the user's share sheet the import scanner handed over to, which is no page's and would
 *   cover the link's page, or a share link's import.
 *
 * The app's own prompts stay, as they are no page's: What's New, an update, a first-use question,
 * an operator's message, a permission warning, a build too old for the server and the reset. A
 * mail being written is asked first, by [followLink].
 */
internal class LinkOverlays(
    private val closeCourseSheet: () -> Unit,
    private val closeTabMenu: () -> Unit,
    private val closeSignInSheet: () -> Unit,
    private val closeImport: () -> Unit,
    private val closeShareSheet: () -> Unit,
) {
    fun closeFor(target: String) {
        closeCourseSheet()
        closeTabMenu()
        closeSignInSheet()
        closeShareSheet()
        if (target != Screen.ClassTable.route) closeImport()
    }
}

/**
 * Follows a link to [target]. A link to the page on screen leaves it, and what is over it, as
 * they are. Anywhere else, the page on screen is asked first, through [leaveGuard], as a mail
 * being written asks whether to save its draft; once it lets go, [overlays] close, and [open]
 * opens the link's page.
 */
internal fun NavController.followLink(target: String, leaveGuard: LeaveGuard, overlays: LinkOverlays, open: () -> Unit) {
    if (currentBackStackEntry.showsPage(target)) return
    leaveGuard.leave {
        overlays.closeFor(target)
        open()
    }
}
