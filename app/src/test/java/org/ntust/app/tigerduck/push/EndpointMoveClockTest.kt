package org.ntust.app.tigerduck.push

import android.content.Context
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.SyncClockInterceptor
import org.ntust.app.tigerduck.sync.SyncClock
import org.ntust.app.tigerduck.sync.SyncClockStore
import org.ntust.app.tigerduck.util.JvmContext
import org.ntust.app.tigerduck.util.testSignIn

/**
 * Codex on PR P (P2 2): a request to one backend answered after the endpoint moved to another
 * carries the first backend's clock, and its answer that backend's stamps. Heard as the next
 * backend's, a first backend whose clock runs an hour ahead holds every later stamp past the next
 * one's time, each refused until a restart or its clock catches up; and a refusal's resend goes
 * stamped from it. The clock takes a value only from the backend the endpoint names as it hears it
 * (`SyncClock`), so none of these is heard, and a refused write goes no further.
 */
class EndpointMoveClockTest {

    private val context = JvmContext()
    private val prefs = AppPreferences(context)
    private val clock = SyncClock(SyncClockStore(context.getSharedPreferences("clock", Context.MODE_PRIVATE)), prefs)
    private val anHourAhead = System.currentTimeMillis() + 3_600_000
    private val signIn = testSignIn()

    @Before
    fun syncs() {
        // TigerSync is off for good on F-Droid, where no write goes out at all.
        assumeTrue(prefs.cloudSyncEnabled)
    }

    /** A backend that moves the endpoint from [FIRST] to [NEXT] as a request to [path] goes out. */
    private fun movingAt(path: String) = RecordingBackend(
        ahead = listOf(
            Interceptor { chain ->
                if (chain.request().url.encodedPath == path) prefs.announcementApiBaseUrlOverride = NEXT
                chain.proceed(chain.request())
            },
        ),
    )

    /** The next stamp, which follows the wall clock while nothing of the next backend is heard. */
    private fun assertTheNextBackendsClockIsClean() {
        val stamp = clock.stampMs()
        assertTrue("stamped by the wall clock, not the first backend's: $stamp", stamp < System.currentTimeMillis() + 60_000)
    }

    @Test
    fun `a write refused as ahead by the backend the endpoint moved off goes no further, and its clock is not heard`() = runTest {
        val path = "/v4/sync/assignments/7/override"
        val backend = movingAt(path)
        backend.answer(path, 409, syncFixture("v4/error-clock-ahead.json"), mapOf("X-TigerSync-Clock" to "${anHourAhead * 1_000}"))
        val client = pushApiClientAt(FIRST, backend.client, prefs, clock)

        val answer = client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAtMs = null)

        assertEquals("nothing goes again, stamped from the first backend's clock", 1, backend.sent.size)
        assertEquals(false, answer.sent)
        assertTheNextBackendsClockIsClean()
    }

    @Test
    fun `a course upload refused as ahead by the backend the endpoint moved off goes no further`() = runTest {
        val path = "/v4/sync/courses/upload"
        val backend = movingAt(path)
        backend.answer(path, 409, syncFixture("v4/error-clock-ahead.json"), mapOf("X-TigerSync-Clock" to "${anHourAhead * 1_000}"))
        val client = pushApiClientAt(FIRST, backend.client, prefs, clock)
        val added = org.ntust.app.tigerduck.shared.Course(courseNo = "XA0000009", courseName = "Mine", isManual = true)

        val answer = client.uploadCoursesAnswered(listOf(added), "1151", signIn, addedAt = mapOf("XA0000009" to System.currentTimeMillis()))

        assertEquals("nothing goes again", 1, backend.sent.size)
        assertEquals(null, answer)
        assertTheNextBackendsClockIsClean()
    }

    @Test
    fun `a write's answer from the backend the endpoint moved off raises nothing the next backend stamps past`() = runTest {
        val path = "/v4/sync/assignments/7/override"
        val backend = movingAt(path)
        backend.answer(path, 200, """{"local_status":"ignored","local_status_hlc":${anHourAhead * 1_000},"applied":false}""")
        val client = pushApiClientAt(FIRST, backend.client, prefs, clock)

        client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAtMs = null)

        assertTheNextBackendsClockIsClean()
    }

    @Test
    fun `a snapshot from the backend the endpoint moved off raises nothing the next backend stamps past`() = runTest {
        val path = "/v4/sync/full"
        val backend = movingAt(path)
        val snapshot = org.json.JSONObject(syncFixture("v4/full.json")).put("server_hlc", anHourAhead * 1_000)
        backend.answer(path, 200, snapshot.toString())
        prefs.announcementApiBaseUrlOverride = FIRST
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens", Context.MODE_PRIVATE)).apply { save("access", "refresh", Long.MAX_VALUE) }
        val client = SyncApiClient(AuthTokenManager(tokens, backend.client, prefs, "device-1"), prefs, backend.client, clock)

        client.fetchFullSync(signIn)

        assertTheNextBackendsClockIsClean()
    }

    /**
     * The clock reader checks that a response is our backend's, then hears it: a move of the
     * endpoint between the two, which [checkedAgainst] stands for, would let the first backend's
     * clock in.
     */
    @Test
    fun `a clock header the reader took for our backend's just before the endpoint moved is not heard`() {
        prefs.announcementApiBaseUrlOverride = NEXT
        val checkedAgainst = FIRST
        val reader = OkHttpClient.Builder()
            .addInterceptor(SyncClockInterceptor({ checkedAgainst }, clock::heard))
            .addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("")
                    .header("X-TigerSync-Clock", "${anHourAhead * 1_000}")
                    .body("{}".toResponseBody("application/json".toMediaType())).build()
            }
            .build()

        reader.newCall(Request.Builder().url("$FIRST/sync/revision").build()).execute().close()

        assertTheNextBackendsClockIsClean()
    }

    private companion object {
        const val FIRST = "http://127.0.0.1:9/v4"
        const val NEXT = "http://127.0.0.2:9/v4"
    }
}
