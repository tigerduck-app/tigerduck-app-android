package org.ntust.app.tigerduck.push

import android.app.Application
import android.content.Context
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.auth.ScriptedBackend
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.shared.Course
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Every course fetch, class table edit and sync posted the term's whole list, unchanged or not
 * (tigerduck-app-android#120). A list the server accepted goes again only once it changes, or six
 * hours on, as assignments do ([UploadLedger]) and as the iPhone app's
 * `PushCoordinator.uploadCoursesIfChanged`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CourseUploadGateTest {

    private val backend = ScriptedBackend()
    private lateinit var prefs: AppPreferences
    private lateinit var tokens: V3TokenStore
    private lateinit var tokenManager: AuthTokenManager
    private lateinit var api: PushApiClient

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3" }
        // Never the real backend, even past the scripted one.
        assertTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        // TigerSync is off for good on F-Droid, where no upload goes out at all.
        assumeTrue(prefs.cloudSyncEnabled)
        tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-course-upload-gate-test", Context.MODE_PRIVATE))
        tokens.save("access", "refresh", Long.MAX_VALUE)
        tokenManager = AuthTokenManager(tokens, backend.client, prefs, "device-1")
        api = PushApiClient(backend.client, prefs, tokenManager)
    }

    private fun roster(vararg courseNos: String) = courseNos.map { Course(courseNo = it, courseName = it) }

    private fun uploads() = backend.received(UPLOAD).size

    @Test
    fun `an unchanged term list is not sent again`() = runTest {
        api.uploadCourses(roster("CS1", "CS2"), "1151")
        api.uploadCourses(roster("CS1", "CS2"), "1151")

        assertEquals(1, uploads())
    }

    @Test
    fun `the same courses in another order are one list`() = runTest {
        api.uploadCourses(roster("CS1", "CS2"), "1151")
        api.uploadCourses(roster("CS2", "CS1"), "1151")

        assertEquals(1, uploads())
    }

    @Test
    fun `a changed list goes at once, and so does the same list in another term`() = runTest {
        api.uploadCourses(roster("CS1", "CS2"), "1151")
        api.uploadCourses(roster("CS1", "CS2", "CS3"), "1151")
        assertEquals(2, uploads())

        api.uploadCourses(roster("CS1", "CS2"), "1142")
        assertEquals(3, uploads())
    }

    @Test
    fun `a refused upload is not recorded, so the next one goes`() = runTest {
        backend.answer(UPLOAD, 500)
        assertTrue(runCatching { api.uploadCourses(roster("CS1"), "1151") }.isFailure)

        api.uploadCourses(roster("CS1"), "1151")

        assertEquals(2, uploads())
    }

    @Test
    fun `an add forced past a tombstone goes whatever the ledger holds`() = runTest {
        api.uploadCourses(roster("CS1", "CS2"), "1151")
        api.uploadCourses(roster("CS1", "CS2"), "1151", forceKeys = listOf("client:1151:CS2"))

        assertEquals(2, uploads())
    }

    @Test
    fun `a term the server has emptied gets its list again on resend`() = runTest {
        api.uploadCourses(roster("CS1"), "1151")
        api.uploadCourses(roster("CS1"), "1151", resend = true)

        assertEquals(2, uploads())
    }

    @Test
    fun `a term reset forgets the term's list, so the upload after it goes`() = runTest {
        api.uploadCourses(roster("CS1"), "1151")
        api.uploadCourses(roster("CS1"), "1142")

        api.deleteAllCourses("1151")
        api.uploadCourses(roster("CS1"), "1151")
        api.uploadCourses(roster("CS1"), "1142")

        assertEquals(3, uploads())
    }

    @Test
    fun `sign-out forgets every list, so the next account's upload goes`() = runTest {
        api.uploadCourses(roster("CS1"), "1151")

        prefs.clearSyncStamps()
        api.uploadCourses(roster("CS1"), "1151")

        assertEquals(2, uploads())
    }

    @Test
    fun `a list one backend accepted goes to the backend the app is pointed at next`() = runTest {
        api.uploadCourses(roster("CS1"), "1151")

        prefs.announcementApiBaseUrlOverride = "http://127.0.0.1:19/v3"
        assertTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        api.uploadCourses(roster("CS1"), "1151")

        assertEquals(2, uploads())
    }

    /**
     * The sign-out here comes before the upload's answer, so the sign-in check alone turns the
     * record away; the sign-out that comes between the check and the record is
     * [UploadRecordLockTest]'s.
     */
    @Test
    fun `a sign-out before the upload's answer leaves no record, so the next account's same list goes`() = runTest {
        val held = backend.hold(UPLOAD)
        val departing = launch { runCatching { api.uploadCourses(roster("CS1"), "1151") } }
        held.arrived.await()

        // AuthService.logout: the generation moves under the sign-in lock, then the stamps go.
        tokenManager.logout()
        prefs.clearSyncStamps()
        held.release(200)
        departing.join()

        tokens.save("access-2", "refresh-2", Long.MAX_VALUE)
        api.uploadCourses(roster("CS1"), "1151")
        assertEquals(2, uploads())
    }

    private companion object {
        const val UPLOAD = "POST /sync/courses/upload"
    }
}
