package org.ntust.app.tigerduck.ui.screen.browser

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A page's alert, confirm and prompt dialogs, as the iPhone's (tigerduck-app#986): one at a time in
 * the order the page asked, each answered exactly once, as the page waits on its answer. [show]
 * puts the first one waiting up, or takes the last one down with null.
 *
 * A dialog asked while another waits, or within [IN_A_ROW_GAP_MS] of the last answer, is asked in a
 * row. Past [IN_A_ROW] in a row the page is muted: every dialog it asks is cancelled unseen until its
 * next page ([pageChanged]).
 */
class BrowserDialogs(
    private val show: (PageDialog?) -> Unit,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val waiting = ArrayDeque<PageDialog>()
    private var lastAnswer: Long? = null
    private var inARow = 0
    private var muted = false

    /** The dialog up now: the first one waiting. */
    val shown: PageDialog? get() = waiting.firstOrNull()

    fun ask(dialog: PageDialog) {
        val followsOn = waiting.isNotEmpty() || lastAnswer?.let { now() - it < IN_A_ROW_GAP_MS } == true
        inARow = if (followsOn) inARow + 1 else 1
        if (inARow > IN_A_ROW) muted = true
        if (muted) return dialog.answer(false, null)
        waiting.addLast(dialog)
        if (waiting.size == 1) show(dialog)
    }

    /** Answers [dialog] when it is the one up, OK ([confirmed]) with a prompt's [value] or Cancel, and puts the next one up. */
    fun answer(dialog: PageDialog, confirmed: Boolean, value: String?) {
        if (dialog !== shown) return
        lastAnswer = now()
        waiting.removeFirst()
        dialog.answer(confirmed, value)
        show(shown)
    }

    /** A new page in the main frame: its dialogs are counted afresh. */
    fun pageChanged() {
        lastAnswer = null
        inARow = 0
        muted = false
    }

    /** Answers every dialog waiting as cancelled, as the screen goes. */
    fun cancelAll() {
        if (waiting.isEmpty()) return
        val cancelled = waiting.toList()
        waiting.clear()
        show(null)
        cancelled.forEach { it.answer(false, null) }
    }

    companion object {
        /** A form asks a confirm and an alert or two in a row; a page looping on alert() never stops. */
        const val IN_A_ROW = 3

        /** A page asking again this soon after an answer leaves no moment to reach the browser's Close. */
        const val IN_A_ROW_GAP_MS = 2_000L

        /**
         * The host a dialog is titled with: the asking [frame]'s, or the [page]'s for a frame with none
         * (about:srcdoc, about:blank, data:), as Chrome and Safari name it; null with neither.
         */
        fun host(frame: String?, page: String?): String? =
            listOf(frame, page).firstNotNullOfOrNull { url -> url?.toHttpUrlOrNull()?.host?.takeIf { it.isNotEmpty() } }
    }
}
