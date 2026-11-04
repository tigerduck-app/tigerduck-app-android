package org.ntust.app.tigerduck.push

import com.google.gson.JsonObject
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.auth.AuthTokenManager.SignInAttempt
import org.ntust.app.tigerduck.auth.NotSignedInException
import org.ntust.app.tigerduck.auth.ScriptedBackend
import org.ntust.app.tigerduck.auth.retryingAfterRelogin
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.BulletinApiClient
import org.ntust.app.tigerduck.network.model.SubscriptionRule
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.util.JvmContext

/**
 * Every request of account data carries the sign-in it was built under, the backend's four clients
 * alike: past a sign-out it goes unsent rather than with the next account's token, and a replay
 * after a 401 is the same request under the same sign-in.
 */
class RequestsCarryTheirSignInTest {

    private val backend = ScriptedBackend(under = "/v4")
    private lateinit var tokens: V3TokenStore
    private lateinit var manager: AuthTokenManager
    private lateinit var push: PushApiClient
    private lateinit var sync: SyncApiClient
    private lateinit var documents: SettingsDocumentApiClient
    private lateinit var bulletins: BulletinApiClient
    private lateinit var first: SignIn

    @Before
    fun setUp() {
        val context = JvmContext()
        val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3" }
        // Never the real backend, even past the scripted one.
        assertTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        // TigerSync is off for good on F-Droid, where most of these send nothing at all.
        assumeTrue(prefs.cloudSyncEnabled)
        tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-requests-test", android.content.Context.MODE_PRIVATE))
        tokens.save("access-a", "refresh-a", Long.MAX_VALUE)
        manager = AuthTokenManager(tokens, backend.client, prefs, "device-1")
        push = PushApiClient(backend.client, prefs, manager)
        sync = SyncApiClient(manager, prefs, backend.client)
        documents = SettingsDocumentApiClient(backend.client, prefs, manager)
        bulletins = BulletinApiClient(backend.client, prefs, manager)
        first = manager.session.signIns.current
    }

    /** The sign-out and its wipe, and the next student with a session of their own. */
    private fun nextStudentSignsIn() {
        manager.logout()
        manager.session.open()
        tokens.save("access-b", "refresh-b", Long.MAX_VALUE)
    }

    private fun rule() = SubscriptionRule(id = 1, name = "rule", clientId = "00000000-0000-4000-8000-000000000001")

    /** Every request that takes a session, each built under the sign-in it is given. */
    private fun requests(): Map<String, suspend (SignIn) -> Unit> = mapOf(
        "register" to { signIn ->
            push.register(DeviceRegisterRequest(clientDeviceId = "device-1", pushToken = PushTokenIn(tokenValue = "fcm-1")), signIn)
        },
        "device preferences" to { signIn -> push.updateDevicePreferences("device-1", signIn, locale = "en") },
        "credentials" to { signIn -> push.updateCredentials("moodle", signIn) },
        "holiday" to { signIn -> push.putHolidayOverrideAnswered(3, notify = true, signIn, madeAtMs = null) },
        "mark" to { signIn -> push.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAtMs = null) },
        "course override" to { signIn -> push.patchCourseOverrideAnswered(COURSE, signIn, colorHex = "#FF0000") },
        "course ignore" to { signIn -> push.patchCourseIgnoreAnswered(COURSE, ignore = true, moodleCourseId = null, signIn, madeAtMs = null) },
        "assignments" to { signIn -> push.uploadAssignments(emptyList(), signIn) },
        "courses" to { signIn -> push.uploadCourses(listOf(Course(courseNo = "CS1", courseName = "CS1")), "1151", signIn) },
        "course delete" to { signIn -> push.deleteCourseAnswered("client:1151:CS1", signIn, hiddenAt = null) },
        "term reset" to { signIn -> push.deleteAllCoursesAnswered("1151", signIn, resetAtMs = null) },
        "revision" to { signIn -> sync.fetchRevision(signIn) },
        "changes" to { signIn -> sync.fetchChanges(1, signIn) },
        "full sync" to { signIn -> sync.fetchFullSync(signIn) },
        "settings read" to { signIn -> documents.read<JsonObject>("notification", signIn) },
        "settings write" to { signIn -> documents.write("notification", JsonObject(), null, signIn) },
        "subscriptions" to { signIn -> bulletins.fetchSubscriptions(signIn) },
        "subscriptions save" to { signIn -> bulletins.saveSubscriptions(listOf(rule()), signIn) },
    )

    @Test
    fun `a request built for an ended sign-in goes unsent`() = runTest {
        nextStudentSignsIn()

        for ((name, request) in requests()) {
            val failure = runCatching { request(first) }.exceptionOrNull()

            assertTrue("$name got $failure", failure is NotSignedInException)
        }
        assertEquals("the next student's session stands, and carries none of them", emptyList<String>(), backend.endpoints())
    }

    @Test
    fun `the same requests go under the sign-in that stands, with its token`() = runTest {
        nextStudentSignsIn()
        val requests = requests()

        // A reply a request cannot read is not what this asks: each is on the wire by then.
        for (request in requests.values) runCatching { request(manager.session.signIns.current) }

        assertEquals(requests.keys.toString(), requests.size, backend.endpoints().size)
        val sentWith = backend.endpoints().distinct().flatMap(backend::received).map { it.authorization }.toSet()
        assertEquals(setOf("Bearer access-b"), sentWith)
    }

    /** The sign-out lands with the refused request on its way back, before the relogin asks. */
    @Test
    fun `a 401 replay after a sign-out and a sign-in as another student goes unsent`() = runTest {
        backend.answer(FULL_SYNC, 401)

        val failure = runCatching {
            retryingAfterRelogin(first, { signIn ->
                nextStudentSignsIn()
                manager.signInAgain(signIn)
            }) { signIn -> sync.fetchFullSync(signIn) }
        }.exceptionOrNull()

        assertTrue("the refusal stands, got $failure", (failure as? PushApiException)?.message?.contains("401") == true)
        assertEquals(listOf("Bearer access-a"), backend.received(FULL_SYNC).map { it.authorization })
    }

    /** The relogin said yes, and the sign-out lands after: the replay itself refuses. */
    @Test
    fun `a replay a sign-out overtook after its relogin goes unsent too`() = runTest {
        backend.answer(FULL_SYNC, 401)

        val failure = runCatching {
            retryingAfterRelogin(first, {
                nextStudentSignsIn()
                true
            }) { signIn -> sync.fetchFullSync(signIn) }
        }.exceptionOrNull()

        assertTrue("got $failure", failure is NotSignedInException)
        assertEquals(listOf("Bearer access-a"), backend.received(FULL_SYNC).map { it.authorization })
    }

    /** The relogin itself says no: its reply came too late for the sign-in that asked. */
    @Test
    fun `a sign-out during the relogin replays nothing`() = runTest {
        backend.answer(FULL_SYNC, 401)
        manager.signInWithStoredAccount = {
            nextStudentSignsIn()
            SignInAttempt.SIGNED_IN
        }

        val failure = runCatching {
            retryingAfterRelogin(first, manager::signInAgain) { signIn -> sync.fetchFullSync(signIn) }
        }.exceptionOrNull()

        assertTrue("the refusal stands, got $failure", failure is PushApiException)
        assertEquals(listOf("Bearer access-a"), backend.received(FULL_SYNC).map { it.authorization })
    }

    @Test
    fun `a replay under the sign-in that stands goes with the pair its relogin got`() = runTest {
        backend.answer(FULL_SYNC, 401)
        manager.signInWithStoredAccount = {
            tokens.save("access-a2", "refresh-a2", Long.MAX_VALUE)
            SignInAttempt.SIGNED_IN
        }

        retryingAfterRelogin(first, manager::signInAgain) { signIn -> sync.fetchFullSync(signIn) }

        assertEquals(listOf("Bearer access-a", "Bearer access-a2"), backend.received(FULL_SYNC).map { it.authorization })
    }

    @Test
    fun `the departing unregister still goes with its session`() = runTest {
        val departing = manager.departingSession()!!
        nextStudentSignsIn()

        val status = push.deleteDevice(departing.baseUrl, "device-1", departing.accessToken)

        assertEquals(200, status)
        assertEquals("Bearer access-a", backend.received("DELETE /devices/device-1").single().authorization)
    }

    private companion object {
        const val FULL_SYNC = "GET /sync/full"
        val COURSE = CourseRef(courseKey = "client:1151:CS1")
    }
}
