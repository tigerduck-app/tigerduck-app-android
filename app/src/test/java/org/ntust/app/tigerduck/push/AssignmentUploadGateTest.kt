package org.ntust.app.tigerduck.push

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.auth.ScriptedBackend
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.shared.account.SignInSeries
import java.util.Date
import org.ntust.app.tigerduck.util.JvmContext
import org.ntust.app.tigerduck.util.testSignIn

/**
 * The assignment upload ledger ([UploadLedger]) through [PushApiClient]: what it remembers is
 * what one backend accepted, and a list it holds goes again once the app is pointed at another.
 */
class AssignmentUploadGateTest {

    private val backend = ScriptedBackend()
    private lateinit var prefs: AppPreferences
    private lateinit var api: PushApiClient

    @Before
    fun setUp() {
        val context = JvmContext()
        prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3" }
        // Never the real backend, even past the scripted one.
        assertTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        // TigerSync is off for good on F-Droid, where no upload goes out at all.
        assumeTrue(prefs.cloudSyncEnabled)
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-assignment-upload-gate-test", Context.MODE_PRIVATE))
        tokens.save("access", "refresh", Long.MAX_VALUE)
        api = PushApiClient(backend.client, prefs, AuthTokenManager(tokens, backend.client, prefs, "device-1"))
    }

    private val homework = listOf(Assignment("7", "CS1", "CS1", "HW1", Date(0)))

    private fun uploads() = backend.received(UPLOAD).size

    @Test
    fun `an unchanged list is not sent again`() = runTest {
        api.uploadAssignments(homework, testSignIn())
        api.uploadAssignments(homework, testSignIn())

        assertEquals(1, uploads())
    }

    @Test
    fun `a list one backend accepted goes to the backend the app is pointed at next`() = runTest {
        api.uploadAssignments(homework, testSignIn())

        prefs.announcementApiBaseUrlOverride = "http://127.0.0.1:19/v3"
        assertTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        api.uploadAssignments(homework, testSignIn())

        assertEquals(2, uploads())
    }

    @Test
    fun `a list accepted as its sign-in ended leaves the ledger for the next account's upload`() = runBlocking {
        val series = SignInSeries()
        val held = backend.hold(UPLOAD)
        val upload = async(Dispatchers.Default) { api.uploadAssignments(homework, series.current) }
        withTimeout(10_000) { held.arrived.await() }

        series.end()
        held.release(200)
        withTimeout(10_000) { upload.await() }

        assertNull(prefs.school.assignmentsUploadFingerprint.get())
        assertEquals(0L, prefs.school.assignmentsUploadedAtMs.get())
    }

    @Test
    fun `a list of a sign-in that has ended goes unsent`() = runTest {
        val series = SignInSeries()
        val signIn = series.current
        series.end()

        assertTrue(runCatching { api.uploadAssignments(homework, signIn) }.isFailure)

        assertEquals(0, uploads())
    }

    private companion object {
        const val UPLOAD = "POST /sync/assignments/upload"
    }
}
