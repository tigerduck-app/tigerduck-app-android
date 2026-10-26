package org.ntust.app.tigerduck.emptyclassroom

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * [OkHttpCour01Transport] against a plain-HTTP server on the loopback address, standing in for
 * cour01 at 127.0.0.1 (the project has no MockWebServer). A redirect to `localhost` stands for the
 * SSO host a lapsed session is sent to.
 */
class OkHttpCour01TransportTest {

    /** Answers each connection with the next reply: the raw response, or a hook that writes it. */
    private class Server(private val replies: List<(Socket) -> Unit>) : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int = server.localPort
        private var next = 0

        init {
            thread(isDaemon = true) {
                while (true) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    val reply = synchronized(this) { replies.getOrNull(next++) } ?: break
                    thread(isDaemon = true) {
                        runCatching {
                            socket.use {
                                val input = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                                var line = input.readLine()
                                var length = 0
                                while (!line.isNullOrEmpty()) {
                                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                        length = line.substringAfter(':').trim().toInt()
                                    }
                                    line = input.readLine()
                                }
                                repeat(length) { input.read() }
                                reply(it)
                            }
                        }
                    }
                }
            }
        }

        override fun close() = server.close()
    }

    private val logged = CopyOnWriteArrayList<String>()
    private val client = OkHttpClient.Builder().followRedirects(true).build()
    private var server: Server? = null

    @After
    fun tearDown() {
        server?.close()
    }

    private fun transport() = OkHttpCour01Transport({ client }, host = "127.0.0.1", log = { logged += it })

    private fun url(server: Server) = "http://127.0.0.1:${server.port}/classroom_user/classroom_usecondition.aspx"

    private fun respond(status: String, body: String, headers: String = ""): (Socket) -> Unit = { socket ->
        val bytes = body.toByteArray()
        socket.getOutputStream().apply {
            write("HTTP/1.1 $status\r\n${headers}Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray() + bytes)
            flush()
        }
    }

    @Test
    fun `a page the site answers is returned`() = runBlocking {
        val server = Server(listOf(respond("200 OK", "<html>DropdownCampusListID</html>"))).also { server = it }

        assertEquals("<html>DropdownCampusListID</html>", transport().get(url(server)))
    }

    @Test
    fun `a request that ends on another host, as a lapsed session's does at the SSO, is null`() = runBlocking {
        val sso = Server(listOf(respond("200 OK", "<form id=\"loginForm\"></form>")))
        val server = Server(
            listOf(respond("302 Found", "", "Location: http://localhost:${sso.port}/account/login\r\n")),
        ).also { server = it }

        try {
            assertNull(transport().post(url(server), listOf("__EVENTTARGET" to "date_cal")))
        } finally {
            sso.close()
        }
    }

    @Test
    fun `a server error is a server error, not a page`() = runBlocking {
        val server = Server(listOf(respond("500 Internal Server Error", "<h1>Server Error</h1>"))).also { server = it }

        try {
            transport().get(url(server))
            fail("expected a server error")
        } catch (e: ClassroomUsageError.ServerError) {
            assertEquals(500, e.code)
        }
        assertTrue(logged.isEmpty())
    }

    @Test
    fun `a call cancelled while its page arrives is not logged as a failure`() = runBlocking {
        val headersSent = CountDownLatch(1)
        val release = CountDownLatch(1)
        // The headers and part of the body, then nothing until the test lets go.
        val stalled: (Socket) -> Unit = { socket ->
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n<html>".toByteArray())
                flush()
            }
            headersSent.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        val server = Server(listOf(stalled)).also { server = it }

        val read = async(Dispatchers.IO) { runCatching { transport().get(url(server)) } }
        assertTrue(headersSent.await(10, TimeUnit.SECONDS))
        // Leave the body reader time to block on the stalled body.
        Thread.sleep(200)
        read.cancel()
        withTimeout(10_000) { read.join() }
        release.countDown()
        Thread.sleep(200)

        assertTrue("logged: $logged", logged.isEmpty())
    }

    @Test
    fun `a page cut off without a cancel is logged`() = runBlocking {
        val cut: (Socket) -> Unit = { socket ->
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n<html>".toByteArray())
                flush()
            }
        }
        val server = Server(listOf(cut)).also { server = it }

        try {
            transport().get(url(server))
            fail("expected the cut-off body to fail")
        } catch (_: IOException) {
        }
        assertEquals(1, logged.size)
    }
}
