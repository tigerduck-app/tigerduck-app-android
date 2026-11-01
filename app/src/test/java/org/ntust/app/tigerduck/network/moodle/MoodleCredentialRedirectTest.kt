package org.ntust.app.tigerduck.network.moodle

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Where a POST that carries a Moodle token may go. Two local servers stand in for the Moodle site
 * and for some other host: the site answers every request with a redirect, and the other host
 * records what reaches it. A 307 or a 308 would make OkHttp send the same body again to the new
 * address, so each Moodle POST must stop at the redirect instead.
 */
class MoodleCredentialRedirectTest {

    private lateinit var site: HttpServer
    private lateinit var elsewhere: HttpServer
    private val reached = CopyOnWriteArrayList<String>()
    private var redirectCode = 307

    private val tokens = object : MoodleWebservice.TokenSource {
        override fun stored() = "t1"
        override suspend fun renew(rejected: String?) = "t1"
    }

    /** Sends every request to the stand-in site, as if it were the Moodle host. */
    private val client: OkHttpClient get() = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val local = request.url.newBuilder()
            .scheme("http").host("127.0.0.1").port(site.address.port).build()
        chain.proceed(request.newBuilder().url(local).build())
    }.build()

    private val service get() = MoodleWebservice({ client }, tokens)

    @Before
    fun startServers() {
        elsewhere = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                reached += exchange.requestBody.readBytes().decodeToString()
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            start()
        }
        site = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                exchange.requestBody.readBytes()
                exchange.responseHeaders.add("Location", "http://127.0.0.1:${elsewhere.address.port}/landing")
                exchange.sendResponseHeaders(redirectCode, -1)
                exchange.close()
            }
            start()
        }
    }

    @After
    fun stopServers() {
        site.stop(0)
        elsewhere.stop(0)
    }

    @Test
    fun `a web-service call that is redirected is not sent on with its token`() = runTest {
        for (code in listOf(307, 308)) {
            redirectCode = code
            val failure = runCatching { service.call("core_webservice_get_site_info", "t1") }.exceptionOrNull()

            assertTrue("$code: got $failure", failure != null)
        }
        assertEquals(emptyList<String>(), reached)
    }

    @Test
    fun `a draft upload that is redirected is not sent on with its token`() = runTest {
        for (code in listOf(307, 308)) {
            redirectCode = code
            val failure = runCatching { service.uploadDraft("t1", "a.jpg", "image/jpeg", byteArrayOf(1, 2)) }
                .exceptionOrNull()

            assertTrue("$code: got $failure", failure != null)
        }
        assertEquals(emptyList<String>(), reached)
    }

    @Test
    fun `a file download that is redirected is not sent on with its token`() = runTest {
        val request = MoodleFileUrls.downloadRequest(FILE, accessKey = null, token = "t1")!!
        for (code in listOf(307, 308)) {
            redirectCode = code
            val bytes = runCatching { service.download(request) { true } }.exceptionOrNull()
            val target = File.createTempFile("moodle-file", ".bin")
            val streamed = runCatching {
                service.downloadTo(request, target, expectedMime = null, maxBytes = 1024)
            }.exceptionOrNull()
            target.delete()

            assertTrue("$code: got $bytes", bytes != null)
            assertTrue("$code: got $streamed", streamed != null)
        }
        assertEquals(emptyList<String>(), reached)
    }

    private companion object {
        const val FILE = "https://moodle2.ntust.edu.tw/webservice/pluginfile.php/12/mod_forum/attachment/34/a.pdf"
    }
}
