package org.ntust.app.tigerduck.ui.screen.home

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.push.BackendSyncResult

/**
 * Home's full sync reads the sign-in its first-sync mark upload is pinned to
 * before it fetches the snapshot ([fetchPinned]). Read after, a sign-out and
 * another sign-in while the fetch was out would pin this account's marks to
 * the next account's session.
 */
class FetchPinnedTest {

    private val snapshot = BackendSyncResult(
        ignoredIds = emptySet(),
        completedIds = emptySet(),
        courseOverrides = emptyList(),
        serverCourseNos = emptySet(),
        currentRevision = 1,
    )

    @Test
    fun `the sign-in is the one the sync began under, not the one after its fetch`() = runTest {
        var signInGeneration = 3

        val (result, signIn) = fetchPinned({ signInGeneration }) {
            // Signed out, and someone else signed in, while the fetch was out.
            signInGeneration++
            snapshot
        }

        assertEquals(snapshot, result)
        assertEquals(3, signIn)
    }
}
