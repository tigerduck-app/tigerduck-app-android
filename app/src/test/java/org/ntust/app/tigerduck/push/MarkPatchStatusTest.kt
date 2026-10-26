package org.ntust.app.tigerduck.push

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.auth.ScriptedBackend
import org.ntust.app.tigerduck.data.CourseIgnoreSync
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.util.JvmContext

/**
 * A refused mark PATCH carries its HTTP status, so `UnsentMarks` parks only
 * a mark the backend refused (a 404 for an assignment it has no row for),
 * never one a busy or broken server could not take.
 */
class MarkPatchStatusTest {

    private val backend = ScriptedBackend()
    private lateinit var prefs: AppPreferences
    private lateinit var api: PushApiClient

    @Before
    fun setUp() {
        val context = JvmContext()
        prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3" }
        // Never the real backend, even past the scripted one.
        assertTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        // The fdroid build never syncs, so it sends no mark to test.
        assumeTrue(prefs.cloudSyncEnabled)
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-mark-test", Context.MODE_PRIVATE))
        tokens.save("access", "refresh", Long.MAX_VALUE)
        api = PushApiClient(backend.client, prefs, AuthTokenManager(tokens, backend.client, prefs, "device-1"))
    }

    private suspend fun failure(): Exception =
        runCatching { api.patchAssignmentOverride(7, "ignored") }.exceptionOrNull() as Exception

    @Test
    fun `a 404 is a refusal`() = runTest {
        backend.answer(PATCH, 404)

        val e = failure()

        assertEquals(404, (e as PushApiException).statusCode)
        assertTrue(CourseIgnoreSync.refuses(e))
    }

    @Test
    fun `a server error is not`() = runTest {
        backend.answer(PATCH, 503)

        val e = failure()

        assertEquals(503, (e as PushApiException).statusCode)
        assertTrue(!CourseIgnoreSync.refuses(e))
    }

    private companion object {
        const val PATCH = "PATCH /sync/assignments/7/override"
    }
}
