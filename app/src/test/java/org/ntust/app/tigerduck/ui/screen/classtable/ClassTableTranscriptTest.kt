package org.ntust.app.tigerduck.ui.screen.classtable

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.ntust.app.tigerduck.data.model.CourseGrade
import org.ntust.app.tigerduck.data.model.ScoreReport
import org.ntust.app.tigerduck.ui.component.ServerStatus

/**
 * The transcript one class-table fetch reads (iOS `AppServiceBridge.fetchTranscript`,
 * tigerduck-app#890): from the score system, through its day-long cache, at most once for the
 * fetch and the warm of the other terms after it, and recorded as the score system's answer for
 * the account the fetch began under. Made-up data.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClassTableTranscriptTest {

    private val report = ScoreReport.EMPTY.copy(
        student = "TEST00001",
        courses = listOf(CourseGrade(term = "1141", code = "XA0000001", name = "Made up")),
    )
    private val onDisk = report.copy(student = "on disk")
    private var asks = 0
    private var signedIn = true
    private val recorded = mutableListOf<ServerStatus>()

    private fun transcript(answer: suspend () -> ScoreReport = { report }) = ClassTableTranscript(
        fetch = { asks++; answer() },
        cached = { onDisk },
        stillSignedIn = { signedIn },
        record = { recorded += it },
    )

    @Test
    fun `the score system is asked at most once for a fetch and the warm after it`() = runTest {
        val transcript = transcript()

        assertEquals(report, transcript.fetched())
        assertEquals(report, transcript.fetched())
        assertEquals(report, transcript.cachedOnly())
        assertEquals(1, asks)
        assertEquals(listOf(ServerStatus.OK), recorded)
    }

    @Test
    fun `a fetch whose term course selection answered reads only the transcript on disk`() = runTest {
        val transcript = transcript()

        assertEquals(onDisk, transcript.cachedOnly())
        assertEquals(0, asks)
        assertEquals(emptyList<ServerStatus>(), recorded)
    }

    @Test
    fun `asked from the fetch and the warm at once, the score system still answers once`() = runTest {
        val answer = CompletableDeferred<ScoreReport>()
        val transcript = transcript { answer.await() }
        val first = async { transcript.fetched() }
        val second = async { transcript.fetched() }
        advanceUntilIdle()
        answer.complete(report)

        assertEquals(report, first.await())
        assertEquals(report, second.await())
        assertEquals(1, asks)
    }

    @Test
    fun `a failed fetch gives no transcript, is recorded, and is not asked again for the same fetch`() = runTest {
        val transcript = transcript { throw java.io.IOException("unreachable") }

        assertNull(transcript.fetched())
        assertNull(transcript.fetched())
        assertEquals(1, asks)
        assertEquals(listOf(ServerStatus.FAILED), recorded)
    }

    @Test
    fun `an answer for an account that signed out meanwhile reaches no term and records nothing`() = runTest {
        val transcript = transcript {
            signedIn = false
            report
        }

        assertNull(transcript.fetched())
        assertEquals(emptyList<ServerStatus>(), recorded)

        val failing = transcript {
            signedIn = false
            throw java.io.IOException("unreachable")
        }
        assertNull(failing.fetched())
        assertEquals(emptyList<ServerStatus>(), recorded)
    }
}
