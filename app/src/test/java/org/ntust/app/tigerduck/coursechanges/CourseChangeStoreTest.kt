package org.ntust.app.tigerduck.coursechanges

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.util.JvmContext

/** Ported from iOS `CourseChangesTests`' store cases. */
class CourseChangeStoreTest {

    private val prefs = JvmContext().getSharedPreferences("course_changes_test", 0).also { it.edit().clear().commit() }
    private var base = "https://api.example.test/v3"
    private var onboarded = true
    private val requests = mutableListOf<Request>()

    /** What the server answers next: a status, a body and an ETag. */
    private var answer: Triple<Int, String, String?> = Triple(200, FEED, "W/\"1\"")

    /** Run as the server answers, as an endpoint change that comes while a request is out. */
    private var whileAnswering: () -> Unit = {}

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            requests += chain.request()
            whileAnswering()
            val (code, body, etag) = answer
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("answer")
                .apply { if (etag != null) header("ETag", etag) }
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    private fun store() = CourseChangeStore(prefs, client, { base }, { onboarded })

    @Test
    fun `the store sends its ETag, keeps its copy on a 304 or a 404 and replaces it on a 200`() = runTest {
        val store = store()
        assertTrue(store.refresh())
        assertNull("nothing stored, nothing to match", requests.last().header("If-None-Match"))
        assertEquals("https://api.example.test/v3/calendar/course-changes", requests.last().url.toString())
        val first = store.current()
        assertEquals(CourseChanges.decode(FEED), first)

        answer = Triple(304, "", null)
        assertTrue(store.refresh())
        assertEquals("W/\"1\"", requests.last().header("If-None-Match"))
        assertEquals(first, store.current())

        answer = Triple(404, """{"detail":"no current term"}""", null)
        assertFalse(store.refresh())
        assertEquals(first, store.current())

        answer = Triple(200, OTHER_FEED, "W/\"2\"")
        assertTrue(store.refresh())
        assertEquals(CourseChanges.decode(OTHER_FEED), store.current())
        assertEquals("a new store reads the copy kept", CourseChanges.decode(OTHER_FEED), store().current())
        store().refresh()
        assertEquals("W/\"2\"", requests.last().header("If-None-Match"))
    }

    @Test
    fun `a feed that cannot be read keeps the copy before it`() = runTest {
        val store = store()
        store.refresh()
        answer = Triple(200, "[]", "W/\"3\"")
        assertFalse(store.refresh())
        assertEquals(CourseChanges.decode(FEED), store.current())
        store.refresh()
        assertEquals("the copy's ETag stays", "W/\"1\"", requests.last().header("If-None-Match"))
    }

    @Test
    fun `a stored feed this build cannot read is fetched again`() = runTest {
        store().refresh()
        prefs.edit().putString("payload", "{not json").commit()
        val store = store()
        assertEquals(CourseChanges.EMPTY, store.current())
        store.refresh()
        assertNull(requests.last().header("If-None-Match"))
        assertEquals(CourseChanges.decode(FEED), store.current())
    }

    @Test
    fun `another endpoint's feed is not shown, and is fetched again whole`() = runTest {
        store().refresh()
        base = "https://other.example.test/v3"
        val store = store()
        assertEquals(CourseChanges.EMPTY, store.current())
        answer = Triple(200, OTHER_FEED, "W/\"9\"")
        store.refresh()
        assertNull(requests.last().header("If-None-Match"))
        assertEquals("https://other.example.test/v3/calendar/course-changes", requests.last().url.toString())
        assertEquals(CourseChanges.decode(OTHER_FEED), store.current())
    }

    @Test
    fun `a running store drops its feed when the endpoint moves, before the new one answers`() = runTest {
        val store = store()
        store.refresh()
        base = "https://other.example.test/v3"
        answer = Triple(500, "", null)
        store.refresh()
        assertEquals(CourseChanges.EMPTY, store.current())
    }

    @Test
    fun `an answer from an endpoint that moved while it was out is dropped`() = runTest {
        val store = store()
        whileAnswering = { base = "https://other.example.test/v3" }
        assertFalse(store.refresh())
        assertEquals(CourseChanges.EMPTY, store.current())
        whileAnswering = {}
        store.refresh()
        assertNull("nothing was kept from the old endpoint", requests.last().header("If-None-Match"))
    }

    @Test
    fun `the store asks nothing before onboarding ends`() = runTest {
        onboarded = false
        assertFalse(store().refresh())
        assertTrue(requests.isEmpty())
    }

    private companion object {
        val FEED = """
            {"term":"1151","changes":[
              {"id":1,"course_no":"CC900","kind":"cancelled","periods":null,"class_days":["2026-11-05"],
               "updated_at":"2026-10-30T00:00:00Z"}]}
        """.trimIndent()
        val OTHER_FEED = """
            {"term":"1151","changes":[
              {"id":2,"course_no":"CC900","kind":"room","room":"T4-502","periods":["3"],"class_days":["2026-11-12"],
               "updated_at":"2026-10-31T00:00:00Z"}]}
        """.trimIndent()
    }
}
