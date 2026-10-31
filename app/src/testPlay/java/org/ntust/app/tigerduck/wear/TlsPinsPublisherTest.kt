package org.ntust.app.tigerduck.wear

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The TLS pins item is put one publish at a time, so the item last put is the newest (A8). */
@OptIn(ExperimentalCoroutinesApi::class)
class TlsPinsPublisherTest {

    @Test
    fun `a publish started during another takes its snapshot after that one is put`() = runTest {
        var acceptances = "old"
        val snapshots = mutableListOf<String>()
        val puts = mutableListOf<String>()
        val firstPut = CompletableDeferred<Unit>()
        val publisher = WatchItemPublisher(snapshot = { acceptances.also { snapshots += it } }) { item ->
            if (puts.isEmpty()) firstPut.await()
            puts += item
        }

        launch { publisher.publish() }
        runCurrent()
        // A Continue meanwhile, published at once.
        acceptances = "new"
        launch { publisher.publish() }
        runCurrent()
        assertEquals(listOf("old"), snapshots)

        firstPut.complete(Unit)
        runCurrent()
        assertEquals(listOf("old", "new"), puts)
    }
}
