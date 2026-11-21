package org.ntust.app.tigerduck.mail.index

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * How far the download into the search index has come, for the screens: the sync dot's row, and
 * the end of the search results, which also reads the folders it brought whole ([whole]). One
 * mailbox's at a time, by address and index epoch.
 *
 * [gapsFound] is the repository's count of searches whose catch-up left mail out, moved on here.
 * [shows] says whether an [MailIndexer.Owner]'s download may still show anything: the index still
 * its own, and its student not gone. [ownerNow] reads whose the index is now.
 */
internal class MailIndexCoverage(
    private val gapsFound: AtomicInteger,
    private val shows: (MailIndexer.Owner) -> Boolean,
    private val ownerNow: () -> MailIndexer.Owner?,
) {
    private val _progress = MutableStateFlow<MailIndexProgress?>(null)
    val progress: StateFlow<MailIndexProgress?> = _progress.asStateFlow()

    // Under lock: the mailbox, by address and index epoch, whose download the progress tells of.
    // A sign-in of the same student again keeps it, as it keeps the downloaded mail.
    private val lock = Any()
    private var progressMailbox: Pair<String, Int>? = null

    // Under lock: the mailbox, as [progressMailbox], whose folders [whole] names.
    private var wholeMailbox: Pair<String, Int>? = null
    private val _whole = MutableStateFlow<Set<String>>(emptySet())

    /**
     * The folders the downloads have brought whole: every mail the server had when one last planned
     * them is downloaded, and no search has found one lacking mail since. They outlive the progress,
     * which a download that got through clears; only the mailbox's own leaving, or the downloaded
     * mail's deletion, forgets them.
     */
    val whole: StateFlow<Set<String>> = _whole.asStateFlow()

    private fun MailIndexer.Owner.mailbox() = address to epoch

    // [progress] of [owner]'s download, while the index is still its own and its student has not
    // left: a sign-in tells of the student leaving before it ends their sign-in, and a download of
    // theirs still running must not show progress in between.
    fun show(owner: MailIndexer.Owner, progress: MailIndexProgress): Unit = synchronized(lock) {
        if (!shows(owner)) return
        progressMailbox = owner.mailbox()
        _progress.value = progress
    }

    // [owner]'s progress, stopped, left on screen while mail is missing from search (#587):
    // unless the mailbox's progress was cleared since, by a sign-out or the mail's deletion.
    fun stop(owner: MailIndexer.Owner): Unit = synchronized(lock) {
        if (progressMailbox != owner.mailbox()) return
        _progress.value = _progress.value?.copy(running = false)
    }

    // [folder] of [owner]'s holds every mail the server had as a pass planned once a search had
    // found [gaps] gaps ([gapsFound]) saw it, or not. Not whole after a gap since: that pass did
    // not see the mail the search found missing. Under the lock [noteGap] moves the count under.
    fun noteWhole(owner: MailIndexer.Owner, folder: String, whole: Boolean, gaps: Int): Unit = synchronized(lock) {
        if (!shows(owner) || (whole && gapsFound.get() != gaps)) return
        val before = if (wholeMailbox == owner.mailbox()) _whole.value else emptySet()
        wholeMailbox = owner.mailbox()
        _whole.value = if (whole) before + folder else before - folder
    }

    // No progress: [owner]'s download got through with no gap found since its last pass planned
    // ([gaps]), asked under the lock a gap is published under; its mailbox left ([clearOf]); or
    // the downloaded mail went ([clearAll], every mailbox). Only that mailbox's: another's signed
    // in since stays.
    fun clear(owner: MailIndexer.Owner, gaps: Int) =
        clearWhere { it == owner.mailbox() && gapsFound.get() == gaps }

    // A sign-out with no one signed in ([departing] null) has no mailbox to clear.
    fun clearOf(departing: String?) {
        if (departing == null) return
        clearWhere(forget = true) { it.first == departing.lowercase() }
    }

    fun clearAll() = clearWhere(forget = true) { true }

    // A search found mail missing in [folder], which is no longer whole: the row says so, stopped,
    // until a download gets through, where it said OK; a download under way keeps its own. Under
    // the lock the last pass's clear asks [gapsFound] under, so it cannot clear past this gap.
    fun noteGap(folder: String): Unit = synchronized(lock) {
        gapsFound.incrementAndGet()
        _whole.value -= folder
        if (_progress.value != null) return
        val owner = ownerNow() ?: return
        progressMailbox = owner.mailbox()
        _progress.value = MailIndexProgress.COUNTING.copy(running = false)
    }

    // The folders whole go with the mailbox's progress where it left ([forget]), but not where its
    // download got through: every folder is whole then.
    private fun clearWhere(forget: Boolean = false, mailbox: (Pair<String, Int>) -> Boolean): Unit = synchronized(lock) {
        if (forget && wholeMailbox?.let(mailbox) == true) {
            wholeMailbox = null
            _whole.value = emptySet()
        }
        val shown = progressMailbox ?: return
        if (!mailbox(shown)) return
        progressMailbox = null
        _progress.value = null
    }
}
