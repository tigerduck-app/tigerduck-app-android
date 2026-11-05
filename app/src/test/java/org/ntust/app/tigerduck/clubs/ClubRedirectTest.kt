package org.ntust.app.tigerduck.clubs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.clubs.FakeClubServer.Companion.activity
import org.ntust.app.tigerduck.clubs.FakeClubServer.Companion.club
import org.ntust.app.tigerduck.clubs.FakeClubServer.Companion.envelope
import org.ntust.app.tigerduck.util.JvmContext
import org.ntust.app.tigerduck.util.testSignIn
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The club client never follows a redirect: one to another host would send a request under that
 * host's trust before the answer's host could be refused. Two servers on 127.0.0.1 stand for the
 * club system and the host a redirect names (`localhost`), as the project has no MockWebServer.
 */
class ClubRedirectTest {

    /** Answers the [n]th request, from 0, with the raw response [reply] gives, and counts them. */
    private class Server(private val reply: (n: Int) -> String) : AutoCloseable {
        private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int = socket.localPort
        val requests = AtomicInteger()

        init {
            thread(isDaemon = true) {
                while (true) {
                    val connection = runCatching { socket.accept() }.getOrNull() ?: break
                    runCatching {
                        connection.use {
                            val input = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                            while (!input.readLine().isNullOrEmpty()) Unit
                            it.getOutputStream().write(reply(requests.getAndIncrement()).toByteArray())
                            it.getOutputStream().flush()
                        }
                    }
                }
            }
        }

        override fun close() = socket.close()
    }

    private fun ok(body: String, headers: String = "") =
        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n$headers" +
            "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"

    private fun redirect(to: String) = "HTTP/1.1 302 Found\r\nLocation: $to\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"

    private val elsewhere = Server { ok(envelope(activity(9, 9009, "2026-11-02", "19:00", "21:00"))) }
    private var clubs: Server? = null

    @After
    fun tearDown() {
        elsewhere.close()
        clubs?.close()
    }

    private fun api(server: Server) = ClubApiClient(OkHttpClient(), "http://127.0.0.1:${server.port}/api/v1/public".toHttpUrl())

    private fun away(path: String) = "http://localhost:${elsewhere.port}/api/v1/public/$path"

    @Test
    fun `a redirect of the feed to another host sends nothing there, and the cached feed stays`() = runBlocking {
        val server = Server { n ->
            if (n == 0) ok(envelope(activity(1, 9001, "2026-11-02", "19:00", "21:00")), "ETag: \"v1\"\r\n") else redirect(away("activities"))
        }.also { clubs = it }
        val feed = ClubActivities(JvmContext().getSharedPreferences("club_activities", 0), api(server))
        val signIn = testSignIn()

        feed.refresh(signIn)
        feed.refresh(signIn)

        assertEquals(0, elsewhere.requests.get())
        assertEquals(2, server.requests.get())
        assertEquals(listOf(1), feed.activities.value.map { it.id })
    }

    @Test
    fun `a redirect of the directory to another host sends nothing there, and the cached clubs stay`() = runBlocking {
        val server = Server { n ->
            if (n == 0) ok(envelope(club(9001, "測試棋藝社")), "Cache-Control: max-age=0\r\n") else redirect(away("clubs"))
        }.also { clubs = it }
        val directory = ClubDirectory(JvmContext().getSharedPreferences("club_directory", 0), api(server), { true }, emptyFlow(), CoroutineScope(Dispatchers.Unconfined))

        directory.refresh()
        directory.refresh()

        assertEquals(0, elsewhere.requests.get())
        assertEquals(2, server.requests.get())
        assertEquals(listOf(9001), directory.state.value.clubs.map { it.id })
    }
}
