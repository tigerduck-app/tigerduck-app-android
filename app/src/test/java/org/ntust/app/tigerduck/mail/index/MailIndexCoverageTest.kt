package org.ntust.app.tigerduck.mail.index

import org.junit.Assert.assertEquals
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

    private val whole get() = coverage.whole.value

    @Test
    fun `a folder the download got through is whole, and stays so once the download is done`() {
        coverage.show(me, MailIndexProgress.COUNTING)
        coverage.noteWhole(me, "INBOX", whole = true, gaps = 0)
        assertEquals(setOf("INBOX"), whole)

        coverage.clear(me, gaps = 0)

        assertNull(coverage.progress.value)
        assertEquals(setOf("INBOX"), whole)
    }

    @Test
    fun `a folder found lacking mail is no longer whole`() {
        coverage.noteWhole(me, "INBOX", whole = true, gaps = 0)

        coverage.noteWhole(me, "INBOX", whole = false, gaps = 0)

        assertEquals(emptySet<String>(), whole)
    }

    @Test
    fun `a gap a search finds takes its folder out, and a download planned before it notes none whole`() {
        coverage.noteWhole(me, "INBOX", whole = true, gaps = 0)
        coverage.noteWhole(me, "寄件備份匣", whole = true, gaps = 0)

        coverage.noteGap("INBOX")
        assertEquals(setOf("寄件備份匣"), whole)
        coverage.noteWhole(me, "INBOX", whole = true, gaps = 0)
        assertEquals("planned before the gap", setOf("寄件備份匣"), whole)

        coverage.noteWhole(me, "INBOX", whole = true, gaps = 1)
        assertEquals(setOf("INBOX", "寄件備份匣"), whole)
    }

    @Test
    fun `a gap with no download under way shows one stopped before its count`() {
        coverage.noteGap("INBOX")

        assertEquals(MailIndexProgress(0, null, running = false), coverage.progress.value)
    }

    @Test
    fun `the departing student's whole folders go with them`() {
        coverage.noteWhole(me, "INBOX", whole = true, gaps = 0)

        coverage.clearOf("B10000001@mail.ntust.edu.tw")

        assertEquals(emptySet<String>(), whole)
    }

    @Test
    fun `another student leaving keeps this one's whole folders`() {
        coverage.noteWhole(me, "INBOX", whole = true, gaps = 0)

        coverage.clearOf("b10000002@mail.ntust.edu.tw")

        assertEquals(setOf("INBOX"), whole)
    }

    @Test
    fun `deleting the downloaded mail forgets every whole folder`() {
        coverage.noteWhole(me, "INBOX", whole = true, gaps = 0)

        coverage.clearAll()

        assertEquals(emptySet<String>(), whole)
    }

    @Test
    fun `another mailbox's download starts with no folder whole`() {
        coverage.noteWhole(me, "INBOX", whole = true, gaps = 0)

        coverage.noteWhole(me.copy(epoch = 1), "寄件備份匣", whole = true, gaps = 0)

        assertEquals(setOf("寄件備份匣"), whole)
    }

    @Test
    fun `a download that may no longer show notes nothing whole`() {
        showing = false

        coverage.noteWhole(me, "INBOX", whole = true, gaps = 0)

        assertEquals(emptySet<String>(), whole)
    }
}
