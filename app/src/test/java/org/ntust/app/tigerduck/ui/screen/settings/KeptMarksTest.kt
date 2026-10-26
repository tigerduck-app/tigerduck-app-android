package org.ntust.app.tigerduck.ui.screen.settings

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.ui.screen.home.AssignmentOverrideReconciler
import org.ntust.app.tigerduck.ui.screen.home.AssignmentOverrideReconciler.STATUS_COMPLETED
import org.ntust.app.tigerduck.ui.screen.home.AssignmentOverrideReconciler.STATUS_IGNORED
import org.ntust.app.tigerduck.ui.screen.home.AssignmentOverrideReconciler.STATUS_NONE

class KeptMarksTest {

    @Test
    fun `each mark goes out as this phone has it when its request is made`() = runTest {
        var version = 0
        var marks = mapOf("1" to STATUS_IGNORED, "2" to STATUS_NONE, "3" to STATUS_COMPLETED)
        val sent = mutableListOf<Pair<Int, String>>()

        sendKeptMarks(
            marksVersion = { version },
            marksNow = { marks },
            send = { id, status ->
                sent += id to status
                // Taps on Home while the first request is out: "2" marked
                // done, "3" unmarked.
                if (id == 1) {
                    marks = mapOf("1" to STATUS_IGNORED, "2" to STATUS_COMPLETED)
                    version++
                }
            },
            keep = { _, _ -> },
        )

        assertEquals(listOf(1 to STATUS_IGNORED, 2 to STATUS_COMPLETED), sent)
    }

    @Test
    fun `the marks are read again only when they have changed`() = runTest {
        var version = 0
        var reads = 0
        var requests = 0

        sendKeptMarks(
            marksVersion = { version },
            marksNow = { reads++; (1..200).associate { it.toString() to STATUS_IGNORED } },
            send = { id, _ ->
                requests++
                if (id == 100) version++
            },
            keep = { _, _ -> },
        )

        assertEquals(200, requests)
        assertEquals(2, reads)
    }

    @Test
    fun `a failed request does not stop the others`() = runTest {
        val sent = mutableListOf<Int>()

        sendKeptMarks(
            marksVersion = { 0 },
            marksNow = { mapOf("1" to STATUS_IGNORED, "x" to STATUS_IGNORED, "2" to STATUS_NONE) },
            send = { id, _ ->
                sent += id
                if (id == 1) error("HTTP 500")
            },
            keep = { _, _ -> },
        )

        assertEquals(listOf(1, 2), sent)
    }

    @Test
    fun `a mark that fails to go up is kept to go up later`() = runTest {
        val kept = mutableListOf<Pair<String, String>>()

        sendKeptMarks(
            marksVersion = { 0 },
            marksNow = { mapOf("1" to STATUS_IGNORED, "2" to STATUS_NONE, "3" to STATUS_COMPLETED) },
            send = { id, _ -> if (id != 3) throw java.io.IOException("offline") },
            keep = { id, status -> kept += id to status },
        )

        // Lost otherwise: once the hold goes, the next sync takes the server,
        // which never got them, as the truth.
        assertEquals(listOf("1" to STATUS_IGNORED, "2" to STATUS_NONE), kept)
    }

    private fun snapshot(ignored: Set<String>) = BackendSyncResult(
        ignoredIds = ignored,
        completedIds = emptySet(),
        courseOverrides = emptyList(),
        serverCourseNos = emptySet(),
        currentRevision = 1,
    )

    @Test
    fun `with no network Use Local still clears what this phone unmarked, from the check's reading`() = runTest {
        // Unignored here while sync was off; the server still has it ignored.
        val checked = snapshot(ignored = setOf("9"))

        val server = serverForUseLocal(fetch = { throw java.io.IOException("offline") }, checked = checked)
        val marks = AssignmentOverrideReconciler.keepLocal(
            localIgnored = emptySet(),
            localCompleted = emptySet(),
            serverIgnored = server?.ignoredIds.orEmpty(),
            serverCompleted = server?.completedIds.orEmpty(),
        )

        assertEquals(mapOf("9" to STATUS_NONE), marks)
    }

    @Test
    fun `a fresh reading is preferred to the check's`() = runTest {
        val fresh = snapshot(ignored = setOf("1"))

        assertEquals(fresh, serverForUseLocal(fetch = { fresh }, checked = snapshot(ignored = setOf("9"))))
    }

    @Test
    fun `Use Local's answer falls back to the conflict's snapshot when the server cannot be read`() = runTest {
        val checked = snapshot(ignored = setOf("9"))
        val conflict = SettingsViewModel.ReenableConflict(listOf("assignments"), "", checked = checked)
        val sent = mutableListOf<Pair<Int, String>>()
        var ignoresAgainst: BackendSyncResult? = null

        keepLocalMarks(
            conflict = conflict,
            fetch = { throw java.io.IOException("offline") },
            marksVersion = { 0 },
            localMarks = { emptySet<String>() to emptySet() },
            send = { id, status -> sent += id to status },
            keep = { _, _ -> },
            pushCourseIgnores = { ignoresAgainst = it },
        )

        assertEquals(listOf(9 to STATUS_NONE), sent)
        assertEquals(checked, ignoresAgainst)
    }
}
