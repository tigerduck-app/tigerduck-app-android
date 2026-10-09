package org.ntust.app.tigerduck.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedFetchTest {

    private var clockMs = 0L

    // A supervisor scope, like the application scope the services hand it:
    // there a failed request is delivered to the callers awaiting it, while
    // runTest would report the same failure in backgroundScope as the test's.
    private fun TestScope.shared(windowMs: Long = 60_000) = SharedFetch<String, Int>(
        CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job])),
        windowMs,
        now = { clockMs },
    )

    /** A server that counts its requests and answers each when told to. */
    private class Server {
        var requests = 0
        private var answer = CompletableDeferred<Int>()

        suspend fun fetch(): Int {
            requests++
            return answer.await()
        }

        fun reply(value: Int) {
            answer.complete(value)
            answer = CompletableDeferred()
        }

        fun fail() {
            answer.completeExceptionally(IllegalStateException("down"))
            answer = CompletableDeferred()
        }
    }

    @Test
    fun `callers that ask while a request is running share it`() = runTest {
        val shared = shared()
        val server = Server()
        val answers = List(3) { async { shared.get("k", server::fetch) } }
        runCurrent()

        server.reply(7)

        assertEquals(listOf(7, 7, 7), answers.map { it.await() })
        assertEquals(1, server.requests)
    }

    @Test
    fun `an answer is reused inside the window and fetched again after it`() = runTest {
        val shared = shared(windowMs = 60_000)
        val server = Server()
        val first = async { shared.get("k", server::fetch) }
        runCurrent()
        server.reply(1)
        assertEquals(1, first.await())

        clockMs += 59_999
        assertEquals(1, shared.get("k", server::fetch))
        assertEquals(1, server.requests)

        clockMs += 1
        val late = async { shared.get("k", server::fetch) }
        runCurrent()
        server.reply(2)
        assertEquals(2, late.await())
        assertEquals(2, server.requests)
    }

    @Test
    fun `different keys do not share`() = runTest {
        val shared = shared()
        val server = Server()
        launch { shared.get("a", server::fetch) }
        launch { shared.get("b", server::fetch) }
        runCurrent()

        assertEquals(2, server.requests)
        server.reply(1)
        server.reply(2)
    }

    @Test
    fun `a failure reaches every waiting caller and is not kept`() = runTest {
        val shared = shared()
        val server = Server()
        val waiting = List(2) {
            async { runCatching { shared.get("k", server::fetch) } }
        }
        runCurrent()

        server.fail()

        assertTrue(waiting.all { it.await().isFailure })
        assertEquals(1, server.requests)

        val retry = async { shared.get("k", server::fetch) }
        runCurrent()
        server.reply(3)
        assertEquals(3, retry.await())
        assertEquals(2, server.requests)
    }

    @Test
    fun `expire makes the next caller ask again`() = runTest {
        val shared = shared()
        val server = Server()
        val first = async { shared.get("k", server::fetch) }
        runCurrent()
        server.reply(1)
        first.await()

        shared.expire()
        val fresh = async { shared.get("k", server::fetch) }
        runCurrent()
        server.reply(2)

        assertEquals(2, fresh.await())
        assertEquals(2, server.requests)
    }

    @Test
    fun `a caller cancelled while waiting leaves the request going`() = runTest {
        val shared = shared()
        val server = Server()
        val leaving = launch { shared.get("k", server::fetch) }
        val staying = async { shared.get("k", server::fetch) }
        runCurrent()

        leaving.cancel()
        server.reply(5)

        assertEquals(5, staying.await())
        assertEquals(1, server.requests)
    }

    @Test
    fun `with no window only requests in flight are shared`() = runTest {
        val shared = shared(windowMs = 0)
        val server = Server()
        val first = async { shared.get("k", server::fetch) }
        runCurrent()
        server.reply(1)
        first.await()

        val second = async { shared.get("k", server::fetch) }
        runCurrent()
        server.reply(2)

        assertEquals(2, second.await())
        assertEquals(2, server.requests)
    }

    @Test
    fun `an answer past its window is dropped when another lands`() = runTest {
        val shared = shared(windowMs = 60_000)
        val server = Server()
        val old = async { shared.get("old", server::fetch) }
        runCurrent()
        server.reply(1)
        old.await()

        clockMs += 60_000
        val fresh = async { shared.get("new", server::fetch) }
        runCurrent()
        server.reply(2)
        fresh.await()

        assertEquals(setOf("new"), shared.keptKeys())
    }

    @Test
    fun `with no window nothing is kept`() = runTest {
        val shared = shared(windowMs = 0)
        val server = Server()
        val answer = async { shared.get("k", server::fetch) }
        runCurrent()
        server.reply(1)
        answer.await()

        assertEquals(emptySet<String>(), shared.keptKeys())
    }
}
