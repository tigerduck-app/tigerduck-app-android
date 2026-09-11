package org.ntust.app.tigerduck.push

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.liveactivity.LiveActivitySyncValues

/**
 * Covers [pushLiveActivitySettings] — the write half of the `notification`
 * settings document (design spec §4.6), which Android shares with iOS and
 * with future versions of both.
 *
 * The test that matters most here is
 * `every key this client does not own survives the write...`: the whole
 * reason this code merges at the JSON level instead of re-encoding
 * [NotificationSettingsDocument] is that a typed re-encode silently deletes
 * every key the typed model doesn't declare. Its input therefore carries
 * keys these data classes have never heard of, **including ones nested
 * inside sections** — `assignments.reminder_offsets_minutes` (real: iOS
 * writes it and [AssignmentsSection] doesn't model it) and an unknown field
 * inside `live_activity`, the one section this client does own. Preserving
 * only top-level keys is not enough, and that test is what proves it.
 *
 * [pushLiveActivitySettings] is a top-level function taking a
 * [SettingsDocumentTransport] so it can be driven here at all: the real
 * [SettingsDocumentApiClient] needs `AppPreferences` (a real `Context`) and
 * `AuthTokenManager` (Android Keystore), none of the four are `open`, and
 * this module has neither Robolectric nor a mocking library — the same
 * constraint that shaped `applyOptOutIfAccepted` and `parseEnvelope`.
 */
class NotificationSettingsSyncTest {

    /**
     * Distinct, deliberately asymmetric local values: the two booleans that
     * are easiest to transpose (`show_in_class` / `show_class_preparing`)
     * differ, so a swapped mapping fails instead of passing by luck, and so
     * do the two lead times.
     */
    private val local = LiveActivitySyncValues(
        showInClass = true,
        showClassPreparing = false,
        showAssignment = true,
        classPreparingLeadSeconds = 900,
        assignmentLeadSeconds = 28_800,
    )

    /**
     * What the server is holding: the two sections Android does not own, one
     * whole section it has never heard of, and — the point — unknown keys
     * *inside* sections. `assignments.reminder_offsets_minutes` is iOS's
     * lossless sub-hour mirror, and `show_on_lock_screen` sits inside
     * `live_activity`, the section this client rewrites.
     */
    private val serverDocumentJson = """
        {
          "assignments": {
            "enabled": true,
            "reminder_offsets_hours": [24, 2],
            "reminder_offsets_minutes": [1440, 120, 30]
          },
          "courses": { "enabled": true, "reminder_offsets_minutes": [10] },
          "live_activity": {
            "show_in_class": false,
            "show_class_preparing": true,
            "show_assignment": false,
            "class_preparing_lead_seconds": 60,
            "assignment_lead_seconds": 3600,
            "show_on_lock_screen": true
          },
          "some_future_section": { "whatever": true, "nested": { "deep": [1, 2] } }
        }
    """.trimIndent()

    private fun json(text: String): JsonObject = JsonParser.parseString(text).asJsonObject

    /**
     * Records what [pushLiveActivitySettings] asks of the network and replays
     * a scripted sequence of write outcomes (the last one repeats, so a test
     * that must not loop forever still terminates).
     */
    private class RecordingTransport(
        private val existing: SettingsDocumentEnvelope<JsonObject>?,
        private val outcomes: List<SettingsWriteResult<JsonObject>> =
            listOf(SettingsWriteResult.Written(2L)),
    ) : SettingsDocumentTransport {
        var readCount = 0
            private set
        val writtenDocuments = mutableListOf<JsonObject>()
        val baseRevisions = mutableListOf<Long?>()

        override suspend fun read(): SettingsDocumentEnvelope<JsonObject>? {
            readCount++
            return existing
        }

        override suspend fun write(
            document: JsonObject,
            baseRevision: Long?,
        ): SettingsWriteResult<JsonObject> {
            writtenDocuments += document
            baseRevisions += baseRevision
            return outcomes[minOf(writtenDocuments.size - 1, outcomes.size - 1)]
        }
    }

    // ── 1. Local → document mapping, field by field ────────────────────────

    @Test
    fun `local preferences map onto the live_activity keys field by field`() = runBlocking {
        val transport = RecordingTransport(existing = null)

        val written = pushLiveActivitySettings(
            local = local,
            transport = transport,
            cloudSyncEnabled = true,
            syncLiveActivity = true,
        )

        assertTrue("the write landed", written)
        val document = transport.writtenDocuments.single()
        // A user with no document yet gets one containing exactly the section
        // this client owns — no invented `assignments` / `courses`.
        assertEquals(setOf("live_activity"), document.keySet())
        assertNull("no document yet means create, i.e. a null base revision", transport.baseRevisions.single())

        val section = document.getAsJsonObject("live_activity")
        assertEquals(
            setOf(
                "show_class_preparing",
                "show_in_class",
                "show_assignment",
                "class_preparing_lead_seconds",
                "assignment_lead_seconds",
            ),
            section.keySet(),
        )
        assertEquals("showInClass -> show_in_class", true, section.get("show_in_class").asBoolean)
        assertEquals(
            "showClassPreparing -> show_class_preparing",
            false,
            section.get("show_class_preparing").asBoolean,
        )
        assertEquals("showAssignment -> show_assignment", true, section.get("show_assignment").asBoolean)
        assertEquals(
            "class lead seconds -> class_preparing_lead_seconds",
            900,
            section.get("class_preparing_lead_seconds").asInt,
        )
        assertEquals(
            "assignment lead seconds -> assignment_lead_seconds",
            28_800,
            section.get("assignment_lead_seconds").asInt,
        )
    }

    // ── 2. Everything this client does not own comes back untouched ───────

    @Test
    fun `every key this client does not own survives the write, nested ones included`() = runBlocking {
        val server = json(serverDocumentJson)
        val transport = RecordingTransport(
            existing = SettingsDocumentEnvelope(document = server, revision = 7L)
        )

        pushLiveActivitySettings(
            local = local,
            transport = transport,
            cloudSyncEnabled = true,
            syncLiveActivity = true,
        )

        val document = transport.writtenDocuments.single()
        assertEquals(7L, transport.baseRevisions.single())

        // Whole sections owned by somebody else: byte-for-byte, including the
        // key the typed AssignmentsSection does not declare. Deleting
        // `reminder_offsets_minutes` would silently stop a user's 30-minute
        // assignment reminder from syncing across their devices, and because
        // Android schedules reminders locally nothing would surface until one
        // day a reminder simply failed to arrive.
        assertEquals(
            "assignments must come back exactly as it went in",
            server.getAsJsonObject("assignments"),
            document.getAsJsonObject("assignments"),
        )
        assertEquals(
            "courses must come back exactly as it went in",
            server.getAsJsonObject("courses"),
            document.getAsJsonObject("courses"),
        )
        // A section no build here has ever heard of, nested objects and all.
        assertEquals(
            "an unknown top-level section must come back exactly as it went in",
            server.getAsJsonObject("some_future_section"),
            document.getAsJsonObject("some_future_section"),
        )

        // ...and the case top-level-only preservation gets wrong: an unknown
        // key INSIDE the section this client does rewrite.
        val section = document.getAsJsonObject("live_activity")
        assertTrue(
            "an unknown key nested inside live_activity must survive",
            section.has("show_on_lock_screen"),
        )
        assertEquals(true, section.get("show_on_lock_screen").asBoolean)

        // The five fields this client owns are the only ones it changed.
        assertEquals(true, section.get("show_in_class").asBoolean)
        assertEquals(false, section.get("show_class_preparing").asBoolean)
        assertEquals(true, section.get("show_assignment").asBoolean)
        assertEquals(900, section.get("class_preparing_lead_seconds").asInt)
        assertEquals(28_800, section.get("assignment_lead_seconds").asInt)

        // Nothing was dropped and nothing was invented at the top level either.
        assertEquals(server.keySet(), document.keySet())
    }

    /**
     * The backend reads `reminder_offsets_hours` as `list[float]` and `0.5`
     * schedules fine, but [AssignmentsSection] declares it `List<Int>?` —
     * decoding that document into the typed model fails outright. Merging as
     * JSON never decodes it, so the value survives a write from this device
     * untouched. (This is why `reminder_offsets_minutes` exists; Android does
     * not read either field yet, but it must not destroy them.)
     */
    @Test
    fun `a fractional reminder offset the typed model cannot hold survives the write`() = runBlocking {
        val server = json(
            """
            {
              "assignments": { "enabled": true, "reminder_offsets_hours": [24, 0.5] }
            }
            """.trimIndent()
        )
        val transport = RecordingTransport(
            existing = SettingsDocumentEnvelope(document = server, revision = 3L)
        )

        pushLiveActivitySettings(
            local = local,
            transport = transport,
            cloudSyncEnabled = true,
            syncLiveActivity = true,
        )

        val offsets = transport.writtenDocuments.single()
            .getAsJsonObject("assignments")
            .getAsJsonArray("reminder_offsets_hours")
        assertEquals(2, offsets.size())
        assertEquals(24.0, offsets[0].asDouble, 0.0)
        assertEquals("the half-hour offset must not be rounded or dropped", 0.5, offsets[1].asDouble, 0.0)
    }

    // ── 3. Conflict handling ──────────────────────────────────────────────

    @Test
    fun `a 409 adopts the server document and retries exactly once`() = runBlocking {
        // The winning state another device wrote while this push was in
        // flight: a different `assignments`, plus a key this build has never
        // seen. Both have to end up in the retried write.
        val winner = json(
            """
            {
              "assignments": { "enabled": false },
              "written_by_another_device": true
            }
            """.trimIndent()
        )
        val transport = RecordingTransport(
            existing = SettingsDocumentEnvelope(document = json(serverDocumentJson), revision = 7L),
            outcomes = listOf(
                SettingsWriteResult.Conflict(SettingsDocumentEnvelope(document = winner, revision = 9L)),
                SettingsWriteResult.Written(10L),
            ),
        )

        val written = pushLiveActivitySettings(
            local = local,
            transport = transport,
            cloudSyncEnabled = true,
            syncLiveActivity = true,
        )

        assertTrue("the retry landed", written)
        assertEquals("exactly one rebase and retry", 2, transport.writtenDocuments.size)
        assertEquals("the retry compare-and-swaps on the server's revision", listOf(7L, 9L), transport.baseRevisions)
        assertEquals(1, transport.readCount)

        val retried = transport.writtenDocuments[1]
        // Rebased onto the winner, not onto the stale read: the loser's own
        // section is re-applied, and the winner's keys are kept.
        assertEquals(false, retried.getAsJsonObject("assignments").get("enabled").asBoolean)
        assertTrue(
            "a key only the winning document had must survive the rebase",
            retried.has("written_by_another_device"),
        )
        assertEquals(true, retried.get("written_by_another_device").asBoolean)
        assertEquals(true, retried.getAsJsonObject("live_activity").get("show_in_class").asBoolean)
        // The stale read's sections are gone, because the server says they are
        // gone — adopting the winner means adopting all of it.
        assertFalse(retried.has("some_future_section"))
    }

    @Test
    fun `a second 409 gives up instead of retrying forever`() {
        val transport = RecordingTransport(
            existing = SettingsDocumentEnvelope(document = json(serverDocumentJson), revision = 7L),
            outcomes = listOf(
                SettingsWriteResult.Conflict(
                    SettingsDocumentEnvelope(document = json("""{"assignments": {"enabled": false}}"""), revision = 9L)
                )
            ),
        )

        val thrown = assertThrows(SettingsDocumentApiException::class.java) {
            runBlocking {
                pushLiveActivitySettings(
                    local = local,
                    transport = transport,
                    cloudSyncEnabled = true,
                    syncLiveActivity = true,
                )
            }
        }

        assertEquals("stops after the single rebase", 2, transport.writtenDocuments.size)
        assertTrue(
            "expected the message to name the namespace, was: ${thrown.message}",
            thrown.message.orEmpty().contains(NOTIFICATION_SETTINGS_NAMESPACE),
        )
    }

    // ── 4. Gates: closed means no request at all ──────────────────────────

    @Test
    fun `cloud sync off sends no request at all`() = runBlocking {
        val transport = RecordingTransport(
            existing = SettingsDocumentEnvelope(document = json(serverDocumentJson), revision = 7L)
        )

        val written = pushLiveActivitySettings(
            local = local,
            transport = transport,
            cloudSyncEnabled = false,
            syncLiveActivity = true,
        )

        assertFalse("nothing was written, so this must not report success", written)
        assertEquals("must not even read the document", 0, transport.readCount)
        assertTrue(transport.writtenDocuments.isEmpty())
    }

    @Test
    fun `live activity sync content switch off sends no request at all`() = runBlocking {
        val transport = RecordingTransport(
            existing = SettingsDocumentEnvelope(document = json(serverDocumentJson), revision = 7L)
        )

        val written = pushLiveActivitySettings(
            local = local,
            transport = transport,
            cloudSyncEnabled = true,
            syncLiveActivity = false,
        )

        assertFalse("nothing was written, so this must not report success", written)
        assertEquals("must not even read the document", 0, transport.readCount)
        assertTrue(transport.writtenDocuments.isEmpty())
    }

    // ── 5. The merge itself ───────────────────────────────────────────────

    /**
     * Direct cover for [merging]'s two rules, separately from the push: a
     * nested object is merged key-wise (not replaced wholesale), and an array
     * IS replaced wholesale — a user removing one reminder offset has to be
     * expressible, which unioning arrays would make impossible.
     */
    @Test
    fun `merging recurses into objects but replaces arrays wholesale`() {
        val existing = json("""{"a": {"keep": 1, "change": 2, "deep": {"kept": true}}, "list": [1, 2, 3]}""")
        val updates = json("""{"a": {"change": 20, "added": 3}, "list": [9]}""")

        val merged = merging(updates, into = existing)

        assertEquals(json("""{"a": {"keep": 1, "change": 20, "deep": {"kept": true}, "added": 3}, "list": [9]}"""), merged)
        // ...and the inputs are left alone, so a caller can retry with the
        // same `updates` against a different `existing` after a conflict.
        assertEquals(json("""{"a": {"keep": 1, "change": 2, "deep": {"kept": true}}, "list": [1, 2, 3]}"""), existing)
        assertEquals(json("""{"a": {"change": 20, "added": 3}, "list": [9]}"""), updates)
    }
}
