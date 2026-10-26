package org.ntust.app.tigerduck.push

import android.content.Context
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
import java.util.Date
import org.ntust.app.tigerduck.util.JvmContext

/**
 * The `due_at` TigerSync is told: the date the student's work is due, a teacher's extension
 * included, as iOS uploads it and the backend's own Moodle fetch writes it (tigerduck-backend#82).
 */
class AssignmentUploadDueDateTest {

    private val backend = ScriptedBackend()
    private lateinit var api: PushApiClient

    @Before
    fun setUp() {
        val context = JvmContext()
        val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3" }
        // Never the real backend, even past the scripted one.
        assertTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        // TigerSync is off for good on F-Droid, where no upload goes out at all.
        assumeTrue(prefs.cloudSyncEnabled)
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-assignment-due-date-test", Context.MODE_PRIVATE))
        tokens.save("access", "refresh", Long.MAX_VALUE)
        api = PushApiClient(backend.client, prefs, AuthTokenManager(tokens, backend.client, prefs, "device-1"))
    }

    private fun uploadedDueAts(): Map<String, String> =
        JsonParser.parseString(backend.received("POST /sync/assignments/upload").single().body)
            .asJsonObject.getAsJsonArray("assignments")
            .associate { it.asJsonObject["moodle_assignment_id"].asInt.toString() to it.asJsonObject["due_at"].asString }

    @Test
    fun `an extended assignment goes up due at the extension, one without at its own date`() = runTest {
        val due = Date(1_800_000_000_000L)
        api.uploadAssignments(
            listOf(
                Assignment("7", "CS1", "CS1", "HW1", due, extensionDueDate = Date(due.time + 3 * DAY_MS)),
                Assignment("8", "CS1", "CS1", "HW2", due),
                // One taken back to before the due date leaves the due date.
                Assignment("9", "CS1", "CS1", "HW3", due, extensionDueDate = Date(due.time - DAY_MS)),
            ),
        )

        assertEquals(
            mapOf("7" to "2027-01-18T08:00:00Z", "8" to "2027-01-15T08:00:00Z", "9" to "2027-01-15T08:00:00Z"),
            uploadedDueAts(),
        )
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
