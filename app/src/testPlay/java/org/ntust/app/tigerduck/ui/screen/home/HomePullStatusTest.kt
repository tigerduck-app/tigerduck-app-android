package org.ntust.app.tigerduck.ui.screen.home

import android.app.Application
import android.content.Context
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.AccountWorld
import org.ntust.app.tigerduck.auth.ScriptedBackend
import org.ntust.app.tigerduck.auth.accountWorld
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.notification.SyncSource
import org.ntust.app.tigerduck.ui.component.ServerKind
import org.ntust.app.tigerduck.ui.component.ServerStatus
import org.ntust.app.tigerduck.ui.component.ServerStatusTracker
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What Home's full sync says of itself: the source the "local only" banner reads, the backend's
 * status, the revision watermark and the first-pull gate, when it lands, when it fails, and when
 * TigerSync is off and nothing is attempted. Made-up accounts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class HomePullStatusTest {

    private val context = RuntimeEnvironment.getApplication()
    private val backend = ScriptedBackend(under = "/v4")
    private val appJob = SupervisorJob()
    private val appScope = CoroutineScope(appJob + Dispatchers.IO)
    private val idleScope = CoroutineScope(Job().apply { cancel() })

    private lateinit var w: AccountWorld
    private lateinit var rig: HomeSyncRig

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
        val credentialPrefs = context.getSharedPreferences("tigerduck_credentials", Context.MODE_PRIVATE)
        w = accountWorld(context, credentialPrefs, appScope, idleScope, backend = backend.client)
        w.appPrefs.announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3"
        // Never the real backend, even past the scripted one.
        assumeTrue(resolveAnnouncementEndpoint(w.appPrefs).overrideApplied)
        rig = homeSyncRig(context, w, backend.client, idleScope)
        runBlocking {
            assertTrue(w.auth.login(STUDENT, "made-up-password"))
            w.credentials.v3Tokens.save("access-1", "refresh-1", Long.MAX_VALUE)
        }
    }

    @After
    fun tearDown() {
        appScope.cancel()
    }

    @Test
    fun `a pull that lands says so and moves the watermark`() = runBlocking {
        val signIn = w.sessions.school.signIns.current
        backend.answer("GET /sync/full", 200, """{"current_revision":42}""")

        rig.sync.pull(state, signIn)

        assertEquals(SyncSource.BACKEND, w.appPrefs.lastSyncSource.value)
        assertEquals(ServerStatus.OK, ServerStatusTracker.statuses.value[ServerKind.BACKEND])
        assertEquals(42L, state.lastKnownRevision.value)
    }

    @Test
    fun `a pull that fails says so and moves nothing`() = runBlocking {
        val signIn = w.sessions.school.signIns.current
        backend.answer("GET /sync/full", 500)

        rig.sync.pull(state, signIn)

        assertEquals(SyncSource.LOCAL, w.appPrefs.lastSyncSource.value)
        assertEquals(ServerStatus.FAILED, ServerStatusTracker.statuses.value[ServerKind.BACKEND])
        assertEquals(0L, state.lastKnownRevision.value)
    }

    @Test
    fun `a pull that lands opens the first-pull gate for its sign-in, and one that fails does not`() = runBlocking {
        val signIn = w.sessions.school.signIns.current
        backend.answer("GET /sync/full", 500)
        rig.sync.pull(state, signIn)
        assertFalse(rig.gate.hasPulled(signIn))

        backend.answer("GET /sync/full", 200, """{"current_revision":42}""")
        rig.sync.pull(state, signIn)
        assertTrue(rig.gate.hasPulled(signIn))
    }

    @Test
    fun `with TigerSync off a pull sends nothing and says none was attempted`() = runBlocking {
        val signIn = w.sessions.school.signIns.current
        w.appPrefs.setLastSyncSource(SyncSource.BACKEND)
        w.appPrefs.cloudSyncEnabled = false
        val from = backend.endpoints().size

        rig.sync.pull(state, signIn)

        assertEquals(emptyList<String>(), backend.endpoints().drop(from))
        assertEquals(SyncSource.NONE, w.appPrefs.lastSyncSource.value)
    }

    private companion object {
        const val STUDENT = "B00015999"
    }
}
