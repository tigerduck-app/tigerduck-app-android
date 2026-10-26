package org.ntust.app.tigerduck.network

import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.debug.DebugFixtureStore
import org.ntust.app.tigerduck.debug.DemoModeInterceptor
import org.ntust.app.tigerduck.network.tls.PinTestChain
import org.ntust.app.tigerduck.shared.tls.PinMismatchException
import org.ntust.app.tigerduck.shared.tls.PinMismatchResolver
import org.ntust.app.tigerduck.shared.tls.PinnedTls
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import kotlin.concurrent.thread
import org.ntust.app.tigerduck.util.JvmContext

/** A call tried again after a pin mismatch is still the call of the account it started under. */
class NtustSessionPinRetryTest {

    private val context = JvmContext()
    private val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
    private val requests = AtomicInteger()

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    socket.use {
                        it.soTimeout = 5_000
                        it.getInputStream().bufferedReader().readLine() ?: return@use
                        requests.incrementAndGet()
                        it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                    }
                }
            }
        }
    }

    @After
    fun close() = server.close()

    private lateinit var manager: NtustSessionManager

    @Test
    fun `a sign-out while a mismatch is settled keeps the retry out of the next account's session`() {
        // The student signs out while the document is fetched or the prompt is up; the pins then clear.
        val resolver = PinMismatchResolver { _, _ -> manager.signOut(); true }
        val tls = PinnedTls(HandshakeCertificates.Builder().build().trustManager, resolver)
        manager = NtustSessionManager(AppPreferences(context), DemoModeInterceptor(DebugFixtureStore(context)), tls)
        val attempts = AtomicInteger()
        val client = manager.client.newBuilder()
            // Inside every interceptor the manager adds: the first attempt meets a mismatch, as its handshake would.
            .addInterceptor { chain ->
                if (attempts.getAndIncrement() == 0) {
                    throw SSLHandshakeException("pins").apply { initCause(PinMismatchException(PinTestChain().mismatch())) }
                }
                chain.proceed(chain.request())
            }
            .build()

        val failure = runCatching {
            client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/").build()).execute().close()
        }.exceptionOrNull()

        assertEquals(2, attempts.get())
        assertTrue("not refused: $failure", failure is InterruptedIOException)
        assertEquals(0, requests.get())
    }
}
