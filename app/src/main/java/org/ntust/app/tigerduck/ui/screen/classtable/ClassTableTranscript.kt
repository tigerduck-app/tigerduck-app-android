// The transcript one class-table fetch reads, shared with the warm of the
// other terms after it. Past terms are built from it, and the term course
// selection serves falls back on it when course selection gives nothing.

package org.ntust.app.tigerduck.ui.screen.classtable

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.data.model.ScoreReport
import org.ntust.app.tigerduck.ui.component.ServerStatus
import org.ntust.app.tigerduck.util.logSummary

/**
 * Asked of the score system at most once, through its day-long cache and
 * never forced, so a pull adds no score-system sign-in; the answer, or the
 * failure, is recorded as the score system's for the account the fetch began
 * under. An answer that account no longer holds reaches no term.
 *
 * As iOS `AppServiceBridge.fetchTranscript` (tigerduck-app#890).
 */
internal class ClassTableTranscript(
    /** The score system's report, from its day-long cache when fresh; throws when it cannot be had. */
    private val fetch: suspend () -> ScoreReport,
    /** The report on disk, whatever its age, without asking the score system. */
    private val cached: suspend () -> ScoreReport?,
    /** Whether the account the fetch began under is still the one signed in. */
    private val stillSignedIn: () -> Boolean,
    /** The score system's status, for the sync dot. */
    private val record: (ServerStatus) -> Unit,
) {
    private val lock = Mutex()
    private var asked = false
    private var report: ScoreReport? = null

    /** The transcript from the score system, asked once; null when that failed. */
    suspend fun fetched(): ScoreReport? = lock.withLock {
        if (!asked) {
            report = ask()
            asked = true
        }
        report
    }

    /**
     * The transcript without asking the score system, for a fetch whose term
     * course selection answered: the one asked for already, else the one on
     * disk.
     */
    suspend fun cachedOnly(): ScoreReport? = lock.withLock { if (asked) report else cached() }

    private suspend fun ask(): ScoreReport? {
        val answer = try {
            fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch the transcript: ${e.logSummary()}")
            if (stillSignedIn()) record(ServerStatus.FAILED)
            return null
        }
        if (!stillSignedIn()) return null
        record(ServerStatus.OK)
        return answer
    }

    private companion object {
        const val TAG = "ClassTableTranscript"
    }
}
