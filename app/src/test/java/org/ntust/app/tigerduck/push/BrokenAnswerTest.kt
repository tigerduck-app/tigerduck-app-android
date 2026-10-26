package org.ntust.app.tigerduck.push

import android.content.Context
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.shared.Course
import java.io.IOException
import org.ntust.app.tigerduck.util.JvmContext

/**
 * Answers whose body breaks off: the clients read the status first and the
 * body only where they need it, so a status still says what it says, and an
 * upload that landed still counts though its answer can't be read.
 */
class BrokenAnswerTest {

    private var status = 200
    private var sent = 0

    /** Every request answered with [status], and a body that breaks off at its first read. */
    private val backend = OkHttpClient.Builder()
        .addInterceptor { chain ->
            sent++
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("scripted")
                .body(BrokenBody)
                .build()
        }
        .build()

    private object BrokenBody : ResponseBody() {
        override fun contentType() = "application/json".toMediaType()
        override fun contentLength() = 64L
        override fun source(): BufferedSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long = throw IOException("connection cut")
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
        }.buffer()
    }

    private lateinit var prefs: AppPreferences
    private lateinit var tokenManager: AuthTokenManager

    @Before
    fun setUp() {
        val context = JvmContext()
        prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3" }
        // Never the real backend, even past the scripted one.
        assertTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-broken-answer-test", Context.MODE_PRIVATE))
        tokens.save("access", "refresh", Long.MAX_VALUE)
        tokenManager = AuthTokenManager(tokens, backend, prefs, "device-1")
    }

    @Test
    fun `a refused poll says 401 though its body breaks off`() = runTest {
        status = 401
        val client = SyncApiClient(tokenManager, prefs, backend)

        val failure = runCatching { client.fetchRevision() }.exceptionOrNull()

        assertEquals(401, (failure as? PushApiException)?.statusCode)
    }

    @Test
    fun `an upload that landed counts, though its answer can't be read`() = runTest {
        // TigerSync is off for good on F-Droid, where no upload goes out at all.
        assumeTrue(prefs.cloudSyncEnabled)
        val api = PushApiClient(backend, prefs, tokenManager)
        val roster = listOf(Course(courseNo = "CS1", courseName = "CS1"))

        assertEquals(emptyList<String>(), api.uploadCourses(roster, "1151"))
        // Recorded as accepted: the same list doesn't go again.
        api.uploadCourses(roster, "1151")
        assertEquals(1, sent)
    }
}
