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
 * @param hold holds the keys it is given, and answers what lets them go again.
 * @param failed what a refusal under the sign-in is told as.
 */
internal class MailMarkAllRead(
    private val scope: CoroutineScope,
    private val repository: SchoolMailRepository,
    private val hold: (Set<String>) -> ((Set<String>) -> Unit),
    private val failed: (MailError, SignIn) -> Unit,
) {
    private var running: Job? = null

    fun start(signIn: SignIn, state: SchoolMailListViewModel.UiState) {
        if (running?.isActive == true) return
        val batches = state.displayed
            .filter { !it.summary.flags.seen && it.key !in state.writing && it.uidValidity != null }
            .groupBy { it.folder to it.uidValidity }
        if (batches.isEmpty()) return
        // The keys this press still holds, each let go once: one let go as its batch landed may be
        // held by a later write by now, a move started meanwhile, which must keep its hold.
        val held = batches.values.flatten().mapTo(HashSet()) { it.key }
        val release = hold(held.toSet())
        running = scope.launch {
            try {
                for ((at, rows) in batches) {
                    repository.setSeen(signIn, at.first, at.second, rows.map { it.uid }, seen = true)
                    val landed = rows.mapTo(HashSet()) { it.key }
                    held -= landed
                    release(landed)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(MailErrors.classify(e), signIn)
            } finally {
                release(held.toSet())
            }
        }
    }
}
