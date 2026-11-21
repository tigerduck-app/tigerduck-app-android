package org.ntust.app.tigerduck.mail.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.ntust.app.tigerduck.mail.openSignIn
import java.util.concurrent.atomic.AtomicInteger

/** The download's progress and the folders it brought whole, which the end of the search results reads (#752). */
class MailIndexCoverageTest {
    private val gaps = AtomicInteger()
    private val me = MailIndexer.Owner("b10000001@mail.ntust.edu.tw", openSignIn(), epoch = 0)
    private var showing = true
    private val coverage = MailIndexCoverage(gaps, shows = { showing }, ownerNow = { me })

    private val shown get() = coverage.progress.value

    @Test
    fun `a folder the download got through shows as whole, and still does in the next download`() {
        coverage.show(me, MailIndexProgress.COUNTING)
        coverage.noteWhole(me, "INBOX", whole = true, gaps = 0)
        assertEquals(setOf("INBOX"), shown?.whole)

        coverage.clear(me, gaps = 0)
        assertNull(shown)
        coverage.show(me, MailIndexProgress.COUNTING)

        assertEquals(setOf("INBOX"), shown?.whole)
    }

    @Test
    fun `a folder found lacking mail is no longer whole`() {
        coverage.show(me, MailIndexProgress(0, 3))
        coverage.noteWhole(me, "INBOX", whole = true, gaps = 0)

        coverage.noteWhole(me, "INBOX", whole = false, gaps = 0)

        assertEquals(emptySet<String>(), shown?.whole)
    }

    @Test
    fun `a gap a search finds takes its folder out, and a download planned before it notes none whole`() {
        coverage.show(me, MailIndexProgress(0, 3))
        coverage.noteWhole(me, "INBOX", whole = true, gaps = 0)
        coverage.noteWhole(me, "寄件備份匣", whole = true, gaps = 0)

        coverage.noteGap("INBOX")
        assertEquals(setOf("寄件備份匣"), shown?.whole)
        coverage.noteWhole(me, "INBOX", whole = true, gaps = 0)
        assertEquals("planned before the gap", setOf("寄件備份匣"), shown?.whole)

        coverage.noteWhole(me, "INBOX", whole = true, gaps = 1)
        assertEquals(setOf("INBOX", "寄件備份匣"), shown?.whole)
    }

    @Test
    fun `a gap with no download under way shows one stopped before its count, its folder not whole`() {
        coverage.show(me, MailIndexProgress.COUNTING)
        coverage.noteWhole(me, "INBOX", whole = true, gaps = 0)
        coverage.noteWhole(me, "寄件備份匣", whole = true, gaps = 0)
        coverage.clear(me, gaps = 0)

        coverage.noteGap("INBOX")

        assertEquals(MailIndexProgress(0, null, running = false, whole = setOf("寄件備份匣")), shown)
    }

    @Test
    fun `a download that stopped keeps the folders it got through`() {
        coverage.show(me, MailIndexProgress(1, 3))
        coverage.noteWhole(me, "INBOX", whole = true, gaps = 0)

        coverage.stop(me)

        assertFalse(shown!!.running)
        assertEquals(setOf("INBOX"), shown?.whole)
    }

    @Test
    fun `the departing student's whole folders go with them`() {
        coverage.show(me, MailIndexProgress.COUNTING)
        coverage.noteWhole(me, "INBOX", whole = true, gaps = 0)

        coverage.clearOf("B10000001@mail.ntust.edu.tw")
        coverage.show(me, MailIndexProgress.COUNTING)

        assertEquals(emptySet<String>(), shown?.whole)
    }

    @Test
    fun `deleting the downloaded mail forgets every whole folder`() {
        coverage.show(me, MailIndexProgress.COUNTING)
        coverage.noteWhole(me, "INBOX", whole = true, gaps = 0)

        coverage.clearAll()
        coverage.show(me, MailIndexProgress.COUNTING)

        assertEquals(emptySet<String>(), shown?.whole)
    }

    @Test
    fun `another mailbox's download starts with no folder whole`() {
        coverage.show(me, MailIndexProgress.COUNTING)
        coverage.noteWhole(me, "INBOX", whole = true, gaps = 0)

        coverage.show(me.copy(epoch = 1), MailIndexProgress.COUNTING)

        assertEquals(emptySet<String>(), shown?.whole)
    }

    @Test
    fun `a download that may no longer show notes nothing whole`() {
        coverage.show(me, MailIndexProgress.COUNTING)
        showing = false

        coverage.noteWhole(me, "INBOX", whole = true, gaps = 0)
        showing = true
        coverage.show(me, MailIndexProgress.COUNTING)

        assertEquals(emptySet<String>(), shown?.whole)
    }
}
