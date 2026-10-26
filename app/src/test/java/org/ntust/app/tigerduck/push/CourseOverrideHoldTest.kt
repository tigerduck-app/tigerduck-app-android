package org.ntust.app.tigerduck.push

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.auth.ScriptedBackend
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.util.JvmContext

/**
 * A course override PATCH reads [PushApiClient.patchCourseOverride]'s `allowed` once more right
 * before the request goes, after the header is made, which can renew the session: a hold the
 * course outbox put on the kind meanwhile stops it (tigerduck-app-android#380).
 */
class CourseOverrideHoldTest {

    private val backend = ScriptedBackend()
    private lateinit var api: PushApiClient
    private lateinit var prefs: AppPreferences

    @Before
    fun setUp() {
        val context = JvmContext()
        prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3" }
        // Never the real backend, even past the scripted one.
        assertTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-override-hold-test", Context.MODE_PRIVATE))
        tokens.save("access", "refresh", Long.MAX_VALUE)
        api = PushApiClient(backend.client, prefs, AuthTokenManager(tokens, backend.client, prefs, "device-1"))
    }

    @Test
    fun `a PATCH no longer allowed once its header is made does not go, and says so`() = runTest {
        // TigerSync is off for good on F-Droid, where no override PATCH goes out at all.
        assumeTrue(prefs.cloudSyncEnabled)
        var allowed = true
        assertTrue(api.patchCourseOverride("1151CS1", colorHex = "#FF0000", allowed = { allowed }))
        assertEquals(listOf("PATCH /sync/courses/1151CS1/override"), backend.endpoints())

        allowed = false
        assertFalse(api.patchCourseOverride("1151CS1", colorHex = "#00FF00", allowed = { allowed }))
        assertEquals("nothing more went out", 1, backend.endpoints().size)
    }
}
