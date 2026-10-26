package org.ntust.app.tigerduck.ui.screen.home

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.ui.screen.home.AssignmentOverrideReconciler.STATUS_COMPLETED
import org.ntust.app.tigerduck.ui.screen.home.AssignmentOverrideReconciler.STATUS_IGNORED
import java.io.IOException

/**
 * [sendFirstSyncMarks]: the first-sync upload counts only when every mark
 * landed (tigerduck-app-android#155). Recorded after a failed PATCH, the next
 * sync would take the server — still without that mark — as the truth and
 * wipe it here.
 */
class FirstSyncMarksTest {

    private val sent = mutableListOf<Pair<Int, String>>()
    private val kept = mutableListOf<Pair<String, String>>()

    @Test
    fun `every mark goes up with its status, and the upload counts`() = runTest {
        val landed = sendFirstSyncMarks(setOf("1"), setOf("2"), keep = { _, _ -> }) { id, status -> sent += id to status }

        assertTrue(landed)
        assertEquals(setOf(1 to STATUS_IGNORED, 2 to STATUS_COMPLETED), sent.toSet())
    }

    @Test
    fun `a failed PATCH leaves the upload uncounted, and the other marks still go up`() = runTest {
        val landed = sendFirstSyncMarks(setOf("1", "3"), setOf("2"), keep = { id, status -> kept += id to status }) { id, status ->
            if (id == 1) throw IOException("offline")
            sent += id to status
        }

        assertFalse(landed)
        assertEquals(setOf(3 to STATUS_IGNORED, 2 to STATUS_COMPLETED), sent.toSet())
        // Kept to go up later: once the others reach the server it is no
        // longer empty, so the next sync is no first sync and would take the
        // server's set, without this mark, over this phone's.
        assertEquals(listOf("1" to STATUS_IGNORED), kept)
    }

    @Test
    fun `an id that is not a Moodle assignment id is skipped, not failed`() = runTest {
        val landed = sendFirstSyncMarks(setOf("x"), emptySet(), keep = { _, _ -> }) { id, status -> sent += id to status }

        assertTrue(landed)
        assertEquals(emptyList<Pair<Int, String>>(), sent)
    }

    @Test
    fun `a cancellation is not swallowed as a failed mark`() = runTest {
        val thrown = runCatching {
            sendFirstSyncMarks(setOf("1"), emptySet(), keep = { _, _ -> }) { _, _ -> throw CancellationException("left Home") }
        }.exceptionOrNull()

        assertTrue("got $thrown", thrown is CancellationException)
    }
}
