package org.ntust.app.tigerduck.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.tls.HandshakeCertificates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.debug.DebugFixtureStore
import org.ntust.app.tigerduck.debug.DemoModeInterceptor
import org.ntust.app.tigerduck.di.NetworkModule
import org.ntust.app.tigerduck.shared.tls.PinnedTls
import org.ntust.app.tigerduck.sync.SyncClock
import org.ntust.app.tigerduck.sync.SyncClockStore
import org.ntust.app.tigerduck.util.JvmContext

/**
 * The clock is heard on every response from our backend, errors too, and on nothing else; hearing
 * changes no request and no response (`tigersync-clock`, "The backend reports its clock on every
 * response").
 */
class SyncClockInterceptorTest {

    private val heard = mutableListOf<Long>()
    private var backend: String? = "https://api.example.test/v3"

    /** What each request is answered with: its status and its clock header, none when null. */
    private var answer: (Request) -> Response = { reply(it) }

    private fun reply(request: Request, code: Int = 200, clock: String? = CLOCK.toString()): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .apply { if (clock != null) header("X-TigerSync-Clock", clock) }
            .body("{}".toResponseBody("application/json".toMediaType()))
            .build()

    private val client = OkHttpClient.Builder()
        .addInterceptor(SyncClockInterceptor({ backend }, { clock, _ -> heard += clock }))
        .addInterceptor { chain -> answer(chain.request()) }
        .build()

    private fun get(url: String): Response = client.newCall(Request.Builder().url(url).build()).execute().also { it.close() }

    @Test
    fun `our backend's clock is heard on a success and on an error`() {
        get("https://api.example.test/v3/sync/full")
        answer = { reply(it, code = 404, clock = (CLOCK + 1).toString()) }
        get("https://api.example.test/v3/sync/courses/x/override")
        answer = { reply(it, code = 500, clock = (CLOCK + 2).toString()) }
        get("https://api.example.test/v3/sync/full")

        assertEquals(listOf(CLOCK, CLOCK + 1, CLOCK + 2), heard)
    }

    @Test
    fun `our backend is heard at either API version`() {
        get("https://api.example.test/v4/sync/full")
        backend = "https://api.example.test/v4"
        get("https://api.example.test/v3/sync/full")

        assertEquals(listOf(CLOCK, CLOCK), heard)
    }

    @Test
    fun `another host's clock, or a path beside the API, is not heard`() {
        listOf(
            "https://other.example.test/v3/sync/full",
            "https://moodle.example.test/webservice/rest/server.php",
            "https://api.example.test/links/name",
            "https://api.example.test/health",
            "https://api.example.test/v3x/sync/full",
            "http://api.example.test/v3/sync/full",
        ).forEach(::get)

        assertEquals(emptyList<Long>(), heard)
    }

    @Test
    fun `the backend is the one the setting names when the answer comes`() {
        get("https://api.example.test/v3/sync/full")
        backend = "https://other.example.test/v3"
        get("https://api.example.test/v3/sync/full")
        get("https://other.example.test/v3/sync/full")
        backend = null
        get("https://other.example.test/v3/sync/full")

        assertEquals(listOf(CLOCK, CLOCK), heard)
    }

    @Test
    fun `a header that is no clock is not heard`() {
        // Not a number, below 2020 or past 2100 in microseconds, or one whose stamps would overflow.
        val notClocks = listOf(null, "", "abc", "12.5", "0", "-5", "99999999999999999999", "1000", "4200000000000000", "${Long.MAX_VALUE}")
        notClocks.forEach { value ->
            answer = { reply(it, clock = value) }
            get("https://api.example.test/v3/sync/full")
        }
        answer = { reply(it, clock = " $CLOCK ") }
        get("https://api.example.test/v3/sync/full")

        assertEquals(listOf(CLOCK), heard)
    }

    @Test
    fun `the clock is the last hop's`() {
        // Our backend's request, answered from elsewhere, as after a redirect.
        answer = { reply(it.newBuilder().url("https://other.example.test/v3/sync/full").build()) }
        get("https://api.example.test/v3/sync/full")
        assertEquals("answered elsewhere", emptyList<Long>(), heard)

        // Another host's request, answered by ours.
        answer = { reply(it.newBuilder().url("https://api.example.test/v3/sync/full").build()) }
        get("https://other.example.test/v3/sync/full")
        assertEquals("answered by our backend", listOf(CLOCK), heard)
    }

    @Test
    fun `hearing changes no request and no response`() {
        val built = Request.Builder().url("https://api.example.test/v3/sync/full").header("Authorization", "Bearer a").build()
        var arrived: Request? = null
        var answered: Response? = null
        var handedBack: Response? = null
        answer = { request -> reply(request).also { arrived = request; answered = it } }
        val outer = OkHttpClient.Builder()
            .addInterceptor { chain -> chain.proceed(chain.request()).also { handedBack = it } }
            .addInterceptor(SyncClockInterceptor({ backend }, { clock, _ -> heard += clock }))
            .addInterceptor { chain -> answer(chain.request()) }
            .build()

        outer.newCall(built).execute().close()

        assertSame(built, arrived)
        assertSame(answered, handedBack)
        assertEquals(listOf(CLOCK), heard)
    }

    @Test
    fun `a clock that cannot record what it heard fails nothing`() {
        val failing = OkHttpClient.Builder()
            .addInterceptor(SyncClockInterceptor({ backend }, { _, _ -> throw IllegalStateException("disk") }))
            .addInterceptor { chain -> reply(chain.request(), code = 201) }
            .build()

        val response = failing.newCall(Request.Builder().url("https://api.example.test/v3/sync/full").build()).execute()

        assertEquals(201, response.use { it.code })
    }

    @Test
    fun `the shared client hears the backend, just inside the version gate`() {
        val context = JvmContext()
        val prefs = AppPreferences(context)
        val shared = NetworkModule.provideOkHttpClient(
            prefs,
            DemoModeInterceptor(DebugFixtureStore(context)),
            PinnedTls(HandshakeCertificates.Builder().build().trustManager),
            SyncClock(SyncClockStore(context), prefs),
        )
        val interceptors = shared.interceptors

        assertEquals(1, interceptors.count { it is SyncClockInterceptor })
        assertEquals(interceptors.indexOfFirst { it is ApiVersionInterceptor } + 1, interceptors.indexOfFirst { it is SyncClockInterceptor })
    }

    private companion object {
        const val CLOCK = 1_800_000_000_000_123L
    }
}
