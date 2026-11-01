package org.ntust.app.tigerduck.ui.screen.mail

import org.ntust.app.tigerduck.mail.MailError
import org.ntust.app.tigerduck.mail.model.MailPage
import org.ntust.app.tigerduck.mail.model.MailRow

/**
 * What [readFolderPages] got: the pages of the folders that answered, the folders that did not
 * ([failed], including those never asked after an error that would fail them all), and the
 * failure to report.
 */
internal class FolderPages(val pages: List<Pair<String, MailPage>>, val failed: Set<String>, val failure: MailError?)

/**
 * [read] for each of [folders] in turn. A folder that fails leaves the others' pages, since its
 * own folder can be slow or broken while the rest are fine. An error for which [endsAll] holds (a
 * rejected password, a lost connection, an untrusted certificate) stops at that folder, as every
 * one after it would fail the same way, and is the failure reported. One for which [isDropped]
 * holds -- a request let go unsent, an account that has left -- ends the whole read by rethrowing.
 */
internal suspend fun readFolderPages(
    folders: List<String>,
    isDropped: (MailError) -> Boolean,
    endsAll: (MailError) -> Boolean,
    read: suspend (String) -> MailPage,
): FolderPages {
    val pages = mutableListOf<Pair<String, MailPage>>()
    val failed = mutableSetOf<String>()
    var failure: MailError? = null
    for (folder in folders) {
        if (failure != null && endsAll(failure)) {
            failed += folder
            continue
        }
        try {
            pages += folder to read(folder)
        } catch (e: MailError) {
            if (isDropped(e)) throw e
            failed += folder
            if (failure == null || endsAll(e)) failure = e
        }
    }
    return FolderPages(pages, failed, failure)
}

/**
 * [page], an older page of [folder], merged into [rows]: every row it lists wins, and a row of
 * [folder] inside the page's UID range that it does not list was read, moved or deleted
 * elsewhere and goes. Rows outside that range, and every other folder's, stay.
 *
 * The range overlaps rows already listed whenever mail below the cursor was expunged, as the
 * sequence numbers it was taken from have shifted down since. A single folder's order is the
 * server's, UID descending, which re-sorting keeps.
 */
internal fun mergeOlderPage(rows: List<MailRow>, folder: String, page: MailPage): List<MailRow> {
    val window = page.windowUids
        ?: page.messages.takeIf { it.isNotEmpty() }?.let { m -> m.minOf { it.uid }..m.maxOf { it.uid } }
    val kept = if (window == null) rows else rows.filterNot { it.folder == folder && it.uid in window }
    val fresh = page.messages.map { MailRow(folder, it, page.uidValidity) }
    val freshKeys = fresh.mapTo(HashSet()) { it.key }
    return kept.filterNot { it.key in freshKeys } + fresh
}

internal fun cursorsOf(pages: List<Pair<String, MailPage>>): Map<String, Int> =
    pages.mapNotNull { (folder, page) -> page.nextBeforeSeq?.let { folder to it } }.toMap()
