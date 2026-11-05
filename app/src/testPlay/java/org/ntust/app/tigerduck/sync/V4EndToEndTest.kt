package org.ntust.app.tigerduck.sync

import android.app.Application
import android.content.Context
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.AccountWorld
import org.ntust.app.tigerduck.auth.DepartingSession
import org.ntust.app.tigerduck.auth.ScriptedBackend
import org.ntust.app.tigerduck.auth.accountWorld
import org.ntust.app.tigerduck.network.ApiVersionGate
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.push.DepartingDeviceUnregister
import org.ntust.app.tigerduck.push.OwedUnregister
import org.ntust.app.tigerduck.push.courseUploadFingerprint
import org.ntust.app.tigerduck.push.syncFixture
import org.ntust.app.tigerduck.push.uploadFingerprint
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.ui.screen.home.HomeSyncState
import org.ntust.app.tigerduck.ui.screen.home.RevisionPollOutcome
import org.ntust.app.tigerduck.ui.screen.home.pollRevisionOnce
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The move to `/v4` (T3.31), end to end over the app's own clients, queues and synchronizer, the
 * backend answering in its place under `/v4`: a phone updated with the endpoint setting, its
 * session and its debts as the released build left them, all naming `/v3`. Its session holds
 * (Review Focus 2), and a sign-out's unregister goes to its backend; a pruned feed runs a full sync
 * and latches nothing; the one re-upload the update makes goes once (S7); and a fetch's upload
 * waits for the process's first pull, the gate `/v4` alone has (PR R). The queues the update finds
 * are [V4QueueFixturesTest]'s. Made-up accounts and course numbers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class V4EndToEndTest {

    private val context = RuntimeEnvironment.getApplication()
    private val backend = ScriptedBackend()
    private val rules = V4WriteRules()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val idleScope = CoroutineScope(Job().apply { cancel() })

    private lateinit var w: AccountWorld
    private lateinit var rig: AppSyncRig
    private lateinit var signIn: SignIn

    private val state = HomeSyncState(
        ignoredAssignmentIds = MutableStateFlow(emptySet()),
        markedCompletedIds = MutableStateFlow(emptySet()),
        ignoredCourseKeys = MutableStateFlow(emptySet()),
        allAssignments = MutableStateFlow(emptyList()),
        allCourses = MutableStateFlow(emptyList()),
        conflicts = MutableStateFlow(emptyList()),
        pendingOverrides = mutableSetOf(),
    )

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        ApiVersionGate.resetForTesting()
        val prefs = org.ntust.app.tigerduck.data.preferences.AppPreferences(context)
        // The setting as the released build left it.
        prefs.announcementApiBaseUrlOverride = STORED
        // Never the real backend, even past the scripted one.
        assumeTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        val clock = SyncClock(SyncClockStore(context), prefs)
        val client = appBackendClient(prefs, clock, backend.client, rules)
        val credentialPrefs = context.getSharedPreferences("tigerduck_credentials", Context.MODE_PRIVATE)
        w = accountWorld(context, credentialPrefs, appScope, idleScope, backend = client, backendLogin = client)
        rig = appSyncRig(context, w, client, clock, appScope, idleScope, oldEntryTimeAt(context, FIRST_RUN_MS))
        runBlocking { assertTrue(w.auth.login(STUDENT, "made-up-password")) }
        signIn = w.sessions.school.signIns.current
        // Every category synced on /v3 before the update, so no first sync of one joins the requests.
        w.appPrefs.school.syncedCategories.set(SyncCategory.entries.map { it.stored }.toSet(), signIn)
    }

    @After
    fun tearDown() {
        appScope.cancel()
        ApiVersionGate.resetForTesting()
    }

    /** A pair the released build stored, issued by the `/v3` the setting named then. */
    private fun storedPairFromV3(expiresAt: Long = Long.MAX_VALUE) {
        w.credentials.v3Tokens.save("access-1", "refresh-1", expiresAt, issuedBy = STORED, studentId = STUDENT)
    }

    /** Each request after the first [from]: method and path, then its token. */
    private fun sentSince(from: Int): List<String> = backend.endpoints().indices.drop(from).map { i ->
        val endpoint = backend.endpoints()[i]
        val request = backend.received(endpoint)[backend.endpoints().subList(0, i).count { it == endpoint }]
        "$endpoint | ${request.authorization}"
    }

    @Test
    fun `a pair issued by v3 stays signed in and its requests go to v4`() = runBlocking {
        storedPairFromV3()
        backend.answer("GET /v4/sync/full", 200, snapshot())
        val from = backend.endpoints().size

        rig.home.sync.pull(state, signIn)

        // Let out by the session's guard to /v4, with the pair /v3 issued: no sign-in, no refresh.
        assertEquals(listOf("GET /v4/sync/full | Bearer access-1"), sentSince(from))
        assertTrue(w.tokens.isLoggedIn)
        assertTrue(w.tokens.hasSessionAt(V4))
        assertEquals(emptyList<String>(), rules.refused)
    }

    @Test
    fun `a pair issued by v3 whose access token ran out is refreshed at v4 and stays signed in`() = runBlocking {
        storedPairFromV3(expiresAt = 0)
        backend.answer("POST /v4/auth/refresh", 200, """{"access_token":"access-2","refresh_token":"refresh-2","expires_in":900}""")
        backend.answer("GET /v4/sync/full", 200, snapshot())
        val from = backend.endpoints().size

        rig.home.sync.pull(state, signIn)

        assertEquals(listOf("POST /v4/auth/refresh | null", "GET /v4/sync/full | Bearer access-2"), sentSince(from))
        assertEquals("""{"refresh_token":"refresh-1"}""", backend.received("POST /v4/auth/refresh").single().body)
        assertTrue(w.tokens.hasSessionAt(V4))
    }

    @Test
    fun `a sign-out of a pair issued by v3 owes its unregister to its backend, and it goes to v4`() = runBlocking {
        storedPairFromV3()
        val unregister = DepartingDeviceUnregister(OwedUnregister(w.credentials), rig.pushApi, w.tokens, w.appPrefs)
        // As the school's sign-out takes the session and owes its unregister (PushRegistrationService.unregister).
        val departing = checkNotNull(w.tokens.logout())
        unregister.owe("device-1", departing)
        val from = backend.endpoints().size

        assertEquals(DepartingDeviceUnregister.Outcome.DONE, unregister.send())

        assertEquals(V4, departing.baseUrl)
        assertEquals(listOf("DELETE /v4/devices/device-1 | Bearer access-1"), sentSince(from))
        assertEquals(null, OwedUnregister(w.credentials).store.load())
        w.tokens.session.open()
    }

    @Test
    fun `an unregister the released build owed to v3 goes to v4, and ends unsent once a session of that backend stands`() = runBlocking {
        val unregister = DepartingDeviceUnregister(OwedUnregister(w.credentials), rig.pushApi, w.tokens, w.appPrefs)
        val owedToV3 = DepartingSession("access-0", "refresh-0", Long.MAX_VALUE, STORED, STUDENT)
        unregister.owe("device-1", owedToV3)
        val from = backend.endpoints().size

        // Signed out: it goes as the departing session, to its backend at /v4.
        assertEquals(DepartingDeviceUnregister.Outcome.DONE, unregister.send())
        assertEquals(listOf("DELETE /v4/devices/device-1 | Bearer access-0"), sentSince(from))

        // Signed in again with a pair /v3 issued: the same backend, so a debt to /v3 ends unsent.
        unregister.owe("device-1", owedToV3)
        storedPairFromV3()
        assertEquals(DepartingDeviceUnregister.Outcome.DONE, unregister.send())
        assertEquals(1, backend.received("DELETE /v4/devices/device-1").size)
    }

    @Test
    fun `a sync_revision_expired 409 runs a full sync and latches nothing`() = runBlocking {
        storedPairFromV3()
        // A full sync before set the watermark at 40; the feed has since been pruned past it.
        state.lastKnownRevision.set(40, signIn)
        backend.answer("GET /v4/sync/revision", 200, """{"revision":52}""")
        backend.answer("GET /v4/sync", 409, syncFixture("v4/feed-expired.json"))
        backend.answer("GET /v4/sync/full", 200, snapshot(revision = 52))
        val from = backend.endpoints().size

        val outcome = poll()

        assertEquals(RevisionPollOutcome.CHANGED, outcome)
        assertEquals(
            listOf("GET /v4/sync/revision | Bearer access-1", "GET /v4/sync | Bearer access-1", "GET /v4/sync/full | Bearer access-1"),
            sentSince(from),
        )
        assertEquals("since_revision=40", backend.received("GET /v4/sync").single().query.substringBefore('&'))
        assertEquals(52L, state.lastKnownRevision.value)
        assertFalse("latched by the pruned feed", ApiVersionGate.isRetired.value)

        // The latch is on this client: a retired API does latch it.
        backend.answer("GET /v4/sync/revision", 410, syncFixture("v4/error-api-retired.json"))
        poll()
        assertTrue(ApiVersionGate.isRetired.value)
    }

    private suspend fun poll() = pollRevisionOnce(
        syncEnabled = { w.appPrefs.cloudSyncEnabled },
        online = { true },
        signIn = { true },
        fetchRevision = rig.syncApi::fetchRevision,
        fetchChanges = rig.syncApi::fetchChanges,
        lastKnownRevision = { state.lastKnownRevision.value },
        setLastKnownRevision = state.lastKnownRevision::set,
        currentSignIn = { w.sessions.school.signIns.current },
        fullSyncsInFlight = { 0 },
        retryUnsent = rig.home.sync::sendWhatWaits,
        fullSync = { rig.home.sync.pull(state, signIn) },
    )

    @Test
    fun `the one re-upload of each term and of the assignments after the update goes once, not at every upload`() = runBlocking {
        storedPairFromV3()
        val terms = mapOf(
            "1141" to listOf(Course(courseNo = "XA0000001", courseName = "One")),
            "1142" to listOf(Course(courseNo = "XA0000002", courseName = "Two")),
        )
        // What the released build's ledger holds: each list as /v3 accepted it a minute ago.
        val aMinuteAgo = System.currentTimeMillis() - 60_000
        terms.forEach { (term, courses) -> w.appPrefs.recordCoursesUpload(term, courseUploadFingerprint(STORED, courses, term), aMinuteAgo) }
        w.appPrefs.school.assignmentsUploadFingerprint.set(uploadFingerprint(STORED, """{"assignments":[]}"""), signIn)
        w.appPrefs.school.assignmentsUploadedAtMs.set(aMinuteAgo, signIn)
        terms.keys.forEach { backend.answer("POST /v4/sync/courses/upload", 200, """{"upserted":1,"accepted_keys":[]}""") }
        val from = backend.endpoints().size

        repeat(3) {
            terms.forEach { (term, courses) -> rig.pushApi.uploadCourses(courses, term, signIn) }
            rig.pushApi.uploadAssignments(emptyList(), signIn)
        }

        assertEquals(
            listOf(
                "POST /v4/sync/courses/upload | Bearer access-1",
                "POST /v4/sync/courses/upload | Bearer access-1",
                "POST /v4/sync/assignments/upload | Bearer access-1",
            ),
            sentSince(from),
        )
        assertEquals(listOf("1141", "1142"), backend.received("POST /v4/sync/courses/upload").map { JSONObject(it.body).getJSONArray("courses").getJSONObject(0).getString("semester") })
    }

    @Test
    fun `the setting the released build left turns the first-pull gate on, so a fetch's assignments go after the pull`() = runBlocking {
        storedPairFromV3()
        // As the app builds it: the API read from the endpoint setting at each upload.
        val uploads = SchoolUploads(rig.home.gate, appScope, w.dataCache, rig.pushApi, w.appPrefs)
        backend.answer("GET /v4/sync/full", 200, snapshot())
        val from = backend.endpoints().size

        // A fresh process's fetch, before any pull in it.
        uploads.assignments(emptyList(), signIn)
        assertEquals("sent before the first pull", emptyList<String>(), sentSince(from))

        rig.home.sync.pull(state, signIn)
        withTimeout(10_000) { while (backend.endpoints().size < from + 2) delay(10) }

        assertEquals(
            listOf("GET /v4/sync/full | Bearer access-1", "POST /v4/sync/assignments/upload | Bearer access-1"),
            sentSince(from),
        )
    }

    private fun snapshot(revision: Long = 42) = JSONObject()
        .put("current_revision", revision)
        .put("server_hlc", System.currentTimeMillis() * 1_000)
        .put("assignments", JSONArray())
        .put("assignment_overrides", JSONArray())
        .put("courses", JSONArray())
        .put("course_overrides", JSONArray())
        .put("course_tombstones", JSONArray())
        .put("holiday_overrides", JSONArray())
        .toString()

    private companion object {
        const val STUDENT = "B00015999"
        const val STORED = "http://127.0.0.1:9/v3"
        const val V4 = "http://127.0.0.1:9/v4"
        const val FIRST_RUN_MS = 1_760_000_000_000L
    }
}
