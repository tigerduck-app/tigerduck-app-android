package org.ntust.app.tigerduck.ui.screen.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.io.InputStream

/** The Office document's stream, which WebView reads after it's handed over: a read that fails is kept. */
class ErrorRecordingInputStreamTest {

    /** A stream that gives one byte, then fails, as a cloud provider's can part way through. */
    private class FailingAfterOne(val failure: IOException) : InputStream() {
        private var given = false
        override fun read(): Int = if (!given) { given = true; 42 } else throw failure
        override fun read(b: ByteArray, off: Int, len: Int): Int = if (!given) { given = true; b[off] = 42; 1 } else throw failure
    }

    @Test
    fun `a read that fails after the stream opened is kept, and still fails`() {
        val failure = IOException("connection reset")
        var kept: Throwable? = null
        val stream = ErrorRecordingInputStream(FailingAfterOne(failure)) { kept = it }

        assertEquals(1, stream.read(ByteArray(8), 0, 8))
        val thrown = assertThrows(IOException::class.java) { stream.read(ByteArray(8), 0, 8) }

        assertSame(failure, thrown)
        assertSame(failure, kept)
    }

    @Test
    fun `a single-byte read that fails is kept too`() {
        val failure = IOException("gone")
        var kept: Throwable? = null
        val stream = ErrorRecordingInputStream(FailingAfterOne(failure)) { kept = it }

        assertEquals(42, stream.read())
        assertThrows(IOException::class.java) { stream.read() }
        assertSame(failure, kept)
    }
}
