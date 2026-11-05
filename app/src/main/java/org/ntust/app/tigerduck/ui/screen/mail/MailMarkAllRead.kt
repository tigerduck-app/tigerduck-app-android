package org.ntust.app.tigerduck.ui.screen.mail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.mail.MailError
import org.ntust.app.tigerduck.mail.MailErrors
import org.ntust.app.tigerduck.mail.SchoolMailRepository
import org.ntust.app.tigerduck.shared.account.SignIn

/**
 * The unread filter's Mark all as read on School Mail's list: the unread rows shown, one batch of
 * each folder ([SchoolMailRepository.setSeen]), as the account of the sign-in it began under and
 * under the UIDVALIDITY each row was read under. A row without one is left, as no write of a row
 * goes without one.
 *
 * One runs at a time: a press while one runs does nothing. Its rows are held in
 * [SchoolMailListViewModel.UiState.writing] from the press to their batch's end, as a delete's or
 * a move's row is, so no swipe or second press sends a STORE that crosses it. They change through
 * the repository's feed as each batch lands; a refusal ends it, said once, and leaves the rows
 * still to go as they are, on the list as on the server.
 *
 * A row whose delete or move is under way is that write's to decide, and left out: its question on
 * the way from the server or up, or the write out. Held, its confirmed write would do nothing.
 *
 * @param hold holds the keys it is given, and answers what lets them go again.
 * @param failed what a refusal under the sign-in is told as, with the folder whose batch it ended,
 *   which the list reads again where the STORE may have marked some of its mail.
 */
internal class MailMarkAllRead(
    private val scope: CoroutineScope,
    private val repository: SchoolMailRepository,
    private val hold: (Set<String>) -> ((Set<String>) -> Unit),
    private val failed: (MailError, SignIn, Collection<String>) -> Unit,
) {
    private var running: Job? = null

    /** [asked]: the row whose delete or move question is on its way from the server, if one is. */
    fun start(signIn: SignIn, state: SchoolMailListViewModel.UiState, asked: String?) {
        if (running?.isActive == true) return
        val deciding = state.writing + listOfNotNull(asked, state.pendingDelete?.row?.key, state.pendingMove?.row?.key)
        val batches = state.displayed
            .filter { !it.summary.flags.seen && it.key !in deciding && it.uidValidity != null }
            .groupBy { it.folder to it.uidValidity }
        if (batches.isEmpty()) return
        // The keys this press still holds, each let go once: one let go as its batch landed may be
        // held by a later write by now, a move started meanwhile, which must keep its hold.
        val held = batches.values.flatten().mapTo(HashSet()) { it.key }
        val release = hold(held.toSet())
        running = scope.launch {
            var sending: String? = null
            try {
                for ((at, rows) in batches) {
                    sending = at.first
                    repository.setSeen(signIn, at.first, at.second, rows.map { it.uid }, seen = true)
                    val landed = rows.mapTo(HashSet()) { it.key }
                    held -= landed
                    release(landed)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(MailErrors.classify(e), signIn, listOfNotNull(sending))
            } finally {
                release(held.toSet())
            }
        }
    }
}
