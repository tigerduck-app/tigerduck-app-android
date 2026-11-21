package org.ntust.app.tigerduck.ui.screen.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** A page's dialogs, one at a time and each answered once, as iOS's `BrowserDialogsTests`. */
class BrowserDialogsTest {

    private var clock = 0L

    /** What [BrowserDialogs] put up, in order; null where it took the last one down. */
    private val shown = mutableListOf<PageDialog?>()
    private val dialogs = BrowserDialogs(show = { shown += it }, now = { clock })

    /** Each dialog's answers, by name: true with the prompt's text for OK, false for Cancel. */
    private val answers = linkedMapOf<String, MutableList<Pair<Boolean, String?>>>()

    private fun dialog(name: String, kind: PageDialog.Kind = PageDialog.Kind.ALERT, defaultValue: String? = null) =
        PageDialog(kind, "dialog-test.ntust.edu.tw", name, defaultValue) { confirmed, value ->
            answers.getOrPut(name) { mutableListOf() } += confirmed to value
        }

    private val limit = BrowserDialogs.IN_A_ROW

    @Test
    fun `dialogs show one at a time in the order the page asked, and each is answered once`() {
        val first = dialog("Submit the form?", PageDialog.Kind.CONFIRM)
        val second = dialog("Which week?", PageDialog.Kind.PROMPT, defaultValue = "Week 1")
        dialogs.ask(first)
        dialogs.ask(second)
        assertEquals(listOf(first), shown)
        assertSame(first, dialogs.shown)

        dialogs.answer(second, confirmed = true, value = "Week 2")
        assertEquals(emptyMap<String, Any>(), answers)
        dialogs.answer(first, confirmed = true, value = null)
        dialogs.answer(first, confirmed = false, value = null)
        assertEquals(mapOf("Submit the form?" to listOf(true to null)), answers)
        assertEquals(listOf(first, second), shown)

        dialogs.answer(second, confirmed = true, value = "Week 2")
        dialogs.cancelAll()
        assertEquals(mapOf("Submit the form?" to listOf(true to null), "Which week?" to listOf(true to "Week 2")), answers)
        assertNull(dialogs.shown)
        assertEquals(listOf(first, second, null), shown)
    }

    @Test
    fun `once a page asked a few dialogs each right after the last was answered, the rest are cancelled unseen until its next page`() {
        val asked = (0..limit).map { dialog("Notice $it") }
        asked.forEach {
            dialogs.ask(it)
            dialogs.answer(it, confirmed = true, value = null)
        }
        assertEquals(asked.take(limit), shown.filterNotNull())
        assertEquals(asked.take(limit).map { it.message to listOf(true to null) } + ("Notice $limit" to listOf(false to null)), answers.toList())

        // Still muted, a while later too.
        clock += BrowserDialogs.IN_A_ROW_GAP_MS * 5
        val later = dialog("Later")
        dialogs.ask(later)
        assertEquals(listOf(false to null), answers["Later"])

        dialogs.pageChanged()
        val next = dialog("Submit the form?", PageDialog.Kind.CONFIRM)
        dialogs.ask(next)
        assertSame(next, shown.last())
    }

    @Test
    fun `dialogs asked while another waits count as asked one right after another`() {
        val together = (0..limit).map { dialog("Frame $it") }
        together.forEach(dialogs::ask)
        assertEquals(mapOf("Frame $limit" to listOf(false to null)), answers)
        together.forEach { dialogs.answer(it, confirmed = true, value = null) }
        assertEquals(together.take(limit), shown.filterNotNull())
        assertEquals(limit + 1, answers.size)
        assertNull(shown.last())
    }

    @Test
    fun `a dialog asked a while after the last answer starts the count again`() {
        val asked = (0 until limit * 2).map { dialog("Step $it", PageDialog.Kind.CONFIRM) }
        asked.forEachIndexed { index, it ->
            if (index == limit) clock += BrowserDialogs.IN_A_ROW_GAP_MS
            dialogs.ask(it)
            dialogs.answer(it, confirmed = true, value = null)
        }
        assertEquals(asked, shown.filterNotNull())
        assertEquals(List(limit * 2) { listOf(true to null) }, answers.values.toList())
    }

    @Test
    fun `a dialog asked just inside the gap is still in a row`() {
        val asked = (0..limit).map { dialog("Notice $it") }
        asked.forEach {
            dialogs.ask(it)
            clock += BrowserDialogs.IN_A_ROW_GAP_MS - 1
            dialogs.answer(it, confirmed = true, value = null)
            clock += BrowserDialogs.IN_A_ROW_GAP_MS - 1
        }
        assertEquals(listOf(false to null), answers["Notice $limit"])
    }

    @Test
    fun `every dialog still waiting is answered as cancelled as the screen goes, and only once`() {
        val asked = (0 until 3).map { dialog("Notice $it") }
        asked.forEach(dialogs::ask)
        dialogs.answer(asked[0], confirmed = true, value = null)

        dialogs.cancelAll()
        dialogs.answer(asked[1], confirmed = true, value = null)
        dialogs.cancelAll()
        assertEquals(
            mapOf("Notice 0" to listOf(true to null), "Notice 1" to listOf(false to null), "Notice 2" to listOf(false to null)),
            answers,
        )
        assertNull(dialogs.shown)
        assertNull(shown.last())
    }

    @Test
    fun `a dialog the screen cannot put up is answered as cancelled, and so is each one after it`() {
        var canShow = true
        lateinit var screen: BrowserDialogs
        screen = BrowserDialogs(show = { if (it != null && !canShow) screen.answer(it, confirmed = false, value = null) }, now = { clock })
        val asked = (0 until 3).map { dialog("Notice $it") }
        asked.forEach(screen::ask)
        canShow = false
        screen.answer(asked[0], confirmed = true, value = null)
        assertEquals(
            listOf("Notice 0" to listOf(true to null), "Notice 1" to listOf(false to null), "Notice 2" to listOf(false to null)),
            answers.toList(),
        )
        assertNull(screen.shown)
    }

    @Test
    fun `the title names the frame that asked, or the page's host for a frame with none`() {
        val page = "https://moodle2.ntust.edu.tw/course/view.php?id=1"
        assertEquals("embedded.ntust.edu.tw", BrowserDialogs.host(frame = "https://embedded.ntust.edu.tw/form", page = page))
        listOf(null, "", "about:srcdoc", "about:blank", "data:text/html,hi").forEach {
            assertEquals(it, "moodle2.ntust.edu.tw", BrowserDialogs.host(frame = it, page = page))
        }
        assertNull(BrowserDialogs.host(frame = "about:srcdoc", page = "about:blank"))
        assertNull(BrowserDialogs.host(frame = null, page = null))
    }
}
