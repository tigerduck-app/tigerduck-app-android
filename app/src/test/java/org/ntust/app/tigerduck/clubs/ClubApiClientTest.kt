package org.ntust.app.tigerduck.clubs

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.ntust.app.tigerduck.clubs.FakeClubServer.Answer
import java.io.IOException
import java.time.Instant

/** The club API's requests and answers (ClubAPIModelsTests, ClubCacheTests on iOS), on a fake server. */
class ClubApiClientTest {

    private val server = FakeClubServer()

    @Test
    fun `the directory lives for its max-age, held to a day, and 300 seconds without one`() {
        assertEquals(300L, ClubApiClient.maxAge(null))
        assertEquals(300L, ClubApiClient.maxAge("public"))
        assertEquals(600L, ClubApiClient.maxAge("public, MAX-AGE=600"))
        assertEquals(86_400L, ClubApiClient.maxAge("max-age=99999999"))
        assertEquals(300L, ClubApiClient.maxAge("max-age=-5"))
        assertEquals(300L, ClubApiClient.maxAge("max-age=soon"))
    }

    @Test
    fun `the directory's answer carries its max-age`() = runBlocking {
        server.enqueue("clubs", Answer(body = "[]", headers = mapOf("Cache-Control" to "public, max-age=120")))

        val answer = server.api.clubs()

        assertEquals(ClubDirectoryAnswer("[]", 120L), answer)
        val request = server.requests.single()
        assertEquals("https://clubs.ntust.edu.tw/api/v1/public/clubs", request.url.toString())
        assertEquals("application/json", request.header("Accept"))
    }

    @Test
    fun `the feed is a conditional GET, the stored ETag goes out, and a 304 answers nothing new`() = runBlocking {
        server.enqueue("activities", Answer(body = "feed", headers = mapOf("ETag" to "\"v1\"")))
        server.enqueue("activities", Answer(code = 304))

        assertEquals(ETaggedBody("feed", "\"v1\""), server.api.activities(etag = null))
        assertNull(server.api.activities(etag = "\"v1\""))

        val (first, second) = server.requests("activities")
        assertNull(first.header("If-None-Match"))
        assertEquals("\"v1\"", second.header("If-None-Match"))
    }

    @Test
    fun `a 503 is the club system being down, and an answer from another host or a 500 is refused`() = runBlocking {
        server.enqueue("clubs", Answer(code = 503, body = "<html>maintenance</html>"))
        server.enqueue("clubs", Answer(body = "[]", landedAt = "https://evil.example/api/v1/public/clubs"))
        server.enqueue("activities", Answer(code = 500))

        assertFails(ClubsUnavailableException::class.java) { server.api.clubs() }
        assertFails(IOException::class.java) { server.api.clubs() }
        assertFails(IOException::class.java) { server.api.activities(null) }
    }

    @Test
    fun `a 503 between 00 00 and 07 00 Taipei time is the nightly maintenance`() {
        val down = ClubsUnavailableException()
        assertTrue(ClubApiClient.isMaintenance(down, Instant.parse("2026-11-04T18:30:00Z")))
        assertTrue(ClubApiClient.isMaintenance(down, Instant.parse("2026-11-04T22:59:00Z")))
        assertFalse(ClubApiClient.isMaintenance(down, Instant.parse("2026-11-04T23:00:00Z")))
        assertFalse(ClubApiClient.isMaintenance(IOException("HTTP 500"), Instant.parse("2026-11-04T18:30:00Z")))
    }

    private suspend fun <T : Throwable> assertFails(type: Class<T>, block: suspend () -> Unit) {
        try {
            block()
            fail("expected ${type.simpleName}")
        } catch (e: Throwable) {
            assertTrue("${e::class.java.simpleName} is not ${type.simpleName}", type.isInstance(e))
        }
    }
}
