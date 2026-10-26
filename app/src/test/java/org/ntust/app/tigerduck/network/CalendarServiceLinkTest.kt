package org.ntust.app.tigerduck.network

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The academic calendar's page is found through TigerDuck's `/links/ntust-calendar`, a 307 to
 * the school's page (android#375). A local server stands in for both: the redirect on one host
 * name and a hand-made calendar page on another, so the page's own links resolve against
 * where the redirect led.
 */
class CalendarServiceLinkTest {

    private data class Seen(val host: String, val path: String, val userAgent: String?)

    private val seen = CopyOnWriteArrayList<Seen>()
    private lateinit var server: HttpServer

    private val port get() = server.address.port
    private val linkUrl get() = "http://127.0.0.1:$port/links/ntust-calendar"
    private val pageBase get() = "http://localhost:$port"

    private val loopback = InetAddress.getByName("127.0.0.1")

    /** Both host names reach the one IPv4 server, whatever the machine prefers for `localhost`. */
    private val client = OkHttpClient.Builder().dns(object : Dns {
        override fun lookup(hostname: String) = listOf(loopback)
    }).build()

    private val page get() = """
        <html><body>
        <a href="/var/file/48/1048/img/115.ics"><span style="color:#3300ff;">115 學年度行事曆(ics檔案)</span></a>
        <a href="/var/file/48/1048/img/114.ics"><span style="color:#ff0000;">114 學年度行事曆(ics檔案)</span></a>
        <a href="113/calendar.ics"><span>113 學年度行事曆(ics檔案)</span></a>
        <a href="https://files.example.test/112.ics"><span>112 學年度行事曆(ics檔案)</span></a>
        <a href="/var/file/48/1048/img/115.pdf"><span>115 學年度行事曆(pdf檔案)</span></a>
        </body></html>
    """.trimIndent()

    private fun HttpExchange.note() {
        seen += Seen(requestHeaders.getFirst("Host").substringBefore(':'), requestURI.path, requestHeaders.getFirst("User-Agent"))
    }

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress(loopback, 0), 0)
        server.createContext("/links/ntust-calendar") { exchange ->
            exchange.note()
            exchange.responseHeaders.add("Location", "$pageBase/p/404-1048-78935.php?Lang=zh-tw")
            exchange.sendResponseHeaders(307, -1)
            exchange.close()
        }
        server.createContext("/p/") { exchange ->
            exchange.note()
            val body = page.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    @Test
    fun `the calendar page is looked up through TigerDuck's links route`() {
        assertEquals("https://api.tigerduck.app/links/ntust-calendar", CalendarService.CALENDAR_PAGE_URL)
    }

    @Test
    fun `the links route's 307 is followed and the page's links resolve against the school's page`() = runBlocking {
        val urls = CalendarService(client, linkUrl).fetchCalendarUrls()

        assertEquals(
            listOf("127.0.0.1" to "/links/ntust-calendar", "localhost" to "/p/404-1048-78935.php"),
            seen.map { it.host to it.path },
        )
        assertEquals(
            mapOf(
                115 to "$pageBase/var/file/48/1048/img/115.ics",
                114 to "$pageBase/var/file/48/1048/img/114.ics",
                113 to "$pageBase/p/113/calendar.ics",
                112 to "https://files.example.test/112.ics",
            ),
            urls,
        )
        // The page is asked for as a browser asks for it, after the redirect too.
        assertTrue(seen.toString(), seen.all { it.userAgent?.startsWith("Mozilla/5.0") == true })
    }
}
