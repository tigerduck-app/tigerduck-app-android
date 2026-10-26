package org.ntust.app.tigerduck.ui.screen.classtable.sharing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.ui.screen.classtable.sharing.ClassTableImportModel.ScanAnswer

/**
 * A code kept in front of the camera is passed on once, and again only after it left the picture,
 * unless the import answered that it waits, as while a dialog is over the scanner: then it is
 * passed on again at the next frame.
 */
class RepeatedQrCodeFilterTest {

    @Test
    fun `a code read on frame after frame passes once`() {
        val filter = RepeatedQrCodeFilter()

        assertTrue(filter.isNew("A", 0))
        assertFalse(filter.isNew("A", 33))
        assertFalse(filter.isNew("A", 1_000))
        assertFalse(filter.isNew("A", 1_900))
    }

    @Test
    fun `a code out of view for over a second passes again, and other codes pass meanwhile`() {
        val filter = RepeatedQrCodeFilter()

        assertTrue(filter.isNew("A", 0))
        assertTrue(filter.isNew("B", 500))
        assertTrue(filter.isNew("A", 1_001))
        assertFalse(filter.isNew("B", 1_400))
    }

    @Test
    fun `a code released passes again on the next frame, and is held again after that`() {
        val filter = RepeatedQrCodeFilter()

        assertTrue(filter.isNew("A", 0))
        filter.release("A")
        assertTrue(filter.isNew("A", 33))
        assertFalse(filter.isNew("A", 66))
    }

    /** Posts held until [drain] answers them, as the main thread runs them after the camera's thread. */
    private class Queue {
        val posted = ArrayDeque<Runnable>()
        fun post(runnable: Runnable) { posted.addLast(runnable) }
        fun drain() { while (posted.isNotEmpty()) posted.removeFirst().run() }
    }

    private fun handOff(answer: (String) -> ScanAnswer): Pair<QrCodeHandOff, MutableList<String>> {
        val heard = mutableListOf<String>()
        return QrCodeHandOff(RepeatedQrCodeFilter(), post = Runnable::run) { text -> heard += text; answer(text) } to heard
    }

    @Test
    fun `a code in view while Enter link is open scans as soon as the dialog closes`() {
        var dialogOpen = true
        val taken = mutableListOf<String>()
        val (handOff, _) = handOff { text -> if (dialogOpen) ScanAnswer.BUSY else ScanAnswer.TAKEN.also { taken += text } }

        for (frame in 0 until 60) handOff.read("A", frame * 33L)
        assertTrue(taken.isEmpty())

        dialogOpen = false
        handOff.read("A", 60 * 33L)
        assertEquals(listOf("A"), taken)

        // Taken: held while it stays in view, so cancelling its confirm step does not reopen it.
        for (frame in 61 until 120) handOff.read("A", frame * 33L)
        assertEquals(listOf("A"), taken)
    }

    @Test
    fun `the code the import holds stays held, as back from the background with its confirm step open`() {
        var confirming = false
        val taken = mutableListOf<String>()
        val (handOff, _) = handOff { text ->
            if (confirming) ScanAnswer.HOLDING else ScanAnswer.TAKEN.also { taken += text; confirming = true }
        }

        handOff.read("A", 0)
        // Two seconds in the background, then A still in front of the camera, its step open.
        for (frame in 0 until 30) handOff.read("A", 2_000 + frame * 33L)
        confirming = false
        for (frame in 30 until 60) handOff.read("A", 2_000 + frame * 33L)

        // Cancelling the step does not reopen it while A stays in view.
        assertEquals(listOf("A"), taken)
    }

    @Test
    fun `a code taken before, out of view for a while, scans once a dialog that kept it waiting closes`() {
        var dialogOpen = false
        val taken = mutableListOf<String>()
        val (handOff, _) = handOff { text -> if (dialogOpen) ScanAnswer.BUSY else ScanAnswer.TAKEN.also { taken += text } }

        handOff.read("A", 0)
        // Its step cancelled, A out of view for five seconds, then back while Enter link is open.
        dialogOpen = true
        handOff.read("A", 5_000)
        dialogOpen = false
        handOff.read("A", 5_033)

        assertEquals(listOf("A", "A"), taken)
    }

    @Test
    fun `another app's code is handed over once while it stays in view`() {
        val (handOff, heard) = handOff { ScanAnswer.NOT_SHARE_CODE }

        for (frame in 0 until 60) handOff.read("WIFI:S:home;;", frame * 33L)

        assertEquals(listOf("WIFI:S:home;;"), heard)
    }

    @Test
    fun `frames read before the main thread answers post once, and a code that waits posts again after`() {
        val queue = Queue()
        var answers = 0
        val handOff = QrCodeHandOff(RepeatedQrCodeFilter(), post = queue::post) { answers++; ScanAnswer.BUSY }

        handOff.read("A", 0)
        handOff.read("A", 33)
        handOff.read("A", 66)
        assertEquals(1, queue.posted.size)

        queue.drain()
        assertEquals(1, answers)
        handOff.read("A", 99)
        assertEquals(1, queue.posted.size)
    }

    @Test
    fun `nothing is handed over once the camera is let go, even what was already posted`() {
        val queue = Queue()
        var answers = 0
        val handOff = QrCodeHandOff(RepeatedQrCodeFilter(), post = queue::post) { answers++; ScanAnswer.TAKEN }

        handOff.read("A", 0)
        handOff.close()
        queue.drain()
        handOff.read("B", 33)
        queue.drain()

        assertEquals(0, answers)
    }
}
