package org.ntust.app.tigerduck.ui.screen.settings

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import okhttp3.OkHttpClient
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.auth.ScriptedBackend
import org.ntust.app.tigerduck.data.PendingCourseOverrides
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.util.JvmContext

/**
 * Use Local's requests fail, rather than go quietly unsent, once course sync
 * is off ([PushCourseSyncBackend]). [PushApiClient] skips them without a word
 * then, so Use Local went on as if they had landed: a term reset followed by
 * no upload left every other device hiding the whole term.
 */
class PushCourseSyncBackendTest {

    private val backend = ScriptedBackend()
    private lateinit var prefs: AppPreferences
    private lateinit var courses: PushCourseSyncBackend

    @Before
    fun setUp() {
        val context = JvmContext()
        prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3" }
        // Never the real backend, even past the scripted one.
        assertTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-use-local-test", Context.MODE_PRIVATE))
        tokens.save("access", "refresh", Long.MAX_VALUE)
        val tokenManager = AuthTokenManager(tokens, backend.client, prefs, "device-1")
        val api = PushApiClient(backend.client, prefs, tokenManager)
        val cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), prefs))
        courses = PushCourseSyncBackend(api, prefs, PendingCourseOverrides(cache, api, prefs, tokenManager))
    }

    private suspend fun fails(request: suspend () -> Unit): Boolean = runCatching { request() }.isFailure

    @Test
    fun `with course sync off every request fails and none is sent`() = runTest {
        prefs.syncCourses = false

        assertTrue(fails { courses.deleteAllCourses("1151") })
        assertTrue(fails { courses.uploadCourses(listOf(Course(courseNo = "CS1", courseName = "CS1")), "1151") })
        assertTrue(fails { courses.deleteCourse("client:1151:CS1") })
        assertTrue(fails { courses.patchCustomName("1151CS1", "Calc", "en") })
        assertEquals(emptyList<String>(), backend.endpoints())
    }

    @Test
    fun `with cloud sync off every request fails and none is sent`() = runTest {
        prefs.cloudSyncEnabled = false

        assertTrue(fails { courses.deleteAllCourses("1151") })
        assertTrue(fails { courses.uploadCourses(emptyList(), "1151") })
        assertEquals(emptyList<String>(), backend.endpoints())
    }
}
