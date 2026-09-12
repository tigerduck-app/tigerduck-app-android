package org.ntust.app.tigerduck.push

import android.content.SharedPreferences
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.preferences.effectiveCloudSyncEnabled
import org.ntust.app.tigerduck.liveactivity.LiveActivityPreferences
import org.ntust.app.tigerduck.liveactivity.LiveActivitySyncValues
import org.ntust.app.tigerduck.notification.AssignmentReminderOffset

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
@OptIn(ExperimentalCoroutinesApi::class)
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
            isLoggedIn = true,
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
            isLoggedIn = true,
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
            isLoggedIn = true,
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
            isLoggedIn = true,
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
                    isLoggedIn = true,
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
            isLoggedIn = true,
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
            isLoggedIn = true,
        )

        assertFalse("nothing was written, so this must not report success", written)
        assertEquals("must not even read the document", 0, transport.readCount)
        assertTrue(transport.writtenDocuments.isEmpty())
    }

    @Test
    fun `signed out sends no request at all`() = runBlocking {
        val transport = RecordingTransport(
            existing = SettingsDocumentEnvelope(document = json(serverDocumentJson), revision = 7L)
        )

        val written = pushLiveActivitySettings(
            local = local,
            transport = transport,
            cloudSyncEnabled = true,
            syncLiveActivity = true,
            isLoggedIn = false,
        )

        assertFalse("nothing was written, so this must not report success", written)
        assertEquals("must not even read the document", 0, transport.readCount)
        assertTrue(transport.writtenDocuments.isEmpty())
    }

    /**
     * Ties the fdroid gate to this class's actual push path: even with the
     * *stored* preference on, signed in, and live-activity sync on,
     * [effectiveCloudSyncEnabled] forces the boolean this function gates on
     * to false on fdroid, so [NotificationSettingsSync] (whose `@Inject`
     * constructor wires `cloudSyncEnabled = { appPreferences.cloudSyncEnabled }`)
     * never pushes there.
     */
    @Test
    fun `fdroid sends no push at all even with a stored preference of true`() = runBlocking {
        val transport = RecordingTransport(
            existing = SettingsDocumentEnvelope(document = json(serverDocumentJson), revision = 7L)
        )

        val written = pushLiveActivitySettings(
            local = local,
            transport = transport,
            cloudSyncEnabled = effectiveCloudSyncEnabled(storedValue = true, flavor = "fdroid"),
            syncLiveActivity = true,
            isLoggedIn = true,
        )

        assertFalse("fdroid can never push, whatever is stored", written)
        assertEquals("must not even read the document", 0, transport.readCount)
        assertTrue(transport.writtenDocuments.isEmpty())
    }

    /** The pull-side mirror of the push test above. */
    @Test
    fun `fdroid pulls nothing at all even with a stored preference of true`() = runBlocking {
        val transport = RecordingTransport(
            existing = SettingsDocumentEnvelope(document = json(serverDocumentJson), revision = 7L)
        )

        val result = pullLiveActivitySettings(
            preferences = freshPreferences(),
            transport = transport,
            cloudSyncEnabled = effectiveCloudSyncEnabled(storedValue = true, flavor = "fdroid"),
            syncLiveActivity = true,
            isLoggedIn = true,
        )

        assertFalse("fdroid can never pull, whatever is stored", result)
        assertEquals("must not even read the document", 0, transport.readCount)
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

    // ── 6. Read-and-apply: pullLiveActivitySettings ─────────────────────────
    //
    // [pullLiveActivitySettings] is exercised against a real
    // [LiveActivityPreferences] (backed by the in-memory [FakeLiveActivitySharedPreferences]
    // below), not a mock -- the whole point is proving the *stored* value
    // after applying, including the real clamping [LiveActivityPreferences]'s
    // own setters do. Every "keep local" lead time below starts from a value
    // that is neither the property's default nor what a plausible bug would
    // coerce the bad input to. A boolean has only two values, so no single
    // starting value dodges both; the cases that keep several booleans start
    // them on both sides -- at least one `true`, which the forbidden "missing
    // or null means false" fallback would flip, and at least one `false`,
    // which a reset to the default (`true`) would.

    private fun freshPreferences(): LiveActivityPreferences = LiveActivityPreferences(FakeLiveActivitySharedPreferences())

    @Test
    fun `pull applies all five document fields onto local preferences, field by field`() = runBlocking {
        val prefs = freshPreferences()
        // Opposite of every document value below, so a swapped mapping (e.g.
        // show_in_class and show_class_preparing transposed) fails instead of
        // coincidentally matching.
        prefs.showInClass = true
        prefs.showClassPreparing = false
        prefs.showAssignment = true
        prefs.classPreparingLeadTimeSec = 3_600
        prefs.assignmentLeadTimeSec = 14_400
        val transport = RecordingTransport(
            existing = SettingsDocumentEnvelope(
                document = json(
                    """
                    {
                      "live_activity": {
                        "show_in_class": false,
                        "show_class_preparing": true,
                        "show_assignment": false,
                        "class_preparing_lead_seconds": 1800,
                        "assignment_lead_seconds": 7200
                      }
                    }
                    """.trimIndent()
                ),
                revision = 1L,
            )
        )

        val result = pullLiveActivitySettings(
            preferences = prefs,
            transport = transport,
            cloudSyncEnabled = true,
            syncLiveActivity = true,
            isLoggedIn = true,
        )

        assertTrue("a read was attempted and applied", result)
        assertEquals("show_in_class -> showInClass", false, prefs.showInClass)
        assertEquals("show_class_preparing -> showClassPreparing", true, prefs.showClassPreparing)
        assertEquals("show_assignment -> showAssignment", false, prefs.showAssignment)
        assertEquals(
            "class_preparing_lead_seconds -> classPreparingLeadTimeSec",
            1_800L,
            prefs.classPreparingLeadTimeSec,
        )
        assertEquals(
            "assignment_lead_seconds -> assignmentLeadTimeSec",
            7_200L,
            prefs.assignmentLeadTimeSec,
        )
    }

    @Test
    fun `pull clamps a value outside the local range instead of writing it as-is or discarding it`() = runBlocking {
        val prefs = freshPreferences()
        prefs.classPreparingLeadTimeSec = 1_800
        prefs.assignmentLeadTimeSec = 7_200
        val transport = RecordingTransport(
            existing = SettingsDocumentEnvelope(
                document = json(
                    """
                    {
                      "live_activity": {
                        "class_preparing_lead_seconds": 50000,
                        "assignment_lead_seconds": 100
                      }
                    }
                    """.trimIndent()
                ),
                revision = 1L,
            )
        )

        pullLiveActivitySettings(
            preferences = prefs,
            transport = transport,
            cloudSyncEnabled = true,
            syncLiveActivity = true,
            isLoggedIn = true,
        )

        // 50000s is above MAX_CLASS_LEAD_SEC (14400 = 4h): clamped down, not
        // written as 50000 and not left at the old 1800.
        assertEquals(14_400L, prefs.classPreparingLeadTimeSec)
        // 100s is below MIN_ASSIGNMENT_LEAD_SEC (3600 = 1h): clamped up, not
        // written as 100 and not left at the old 7200.
        assertEquals(3_600L, prefs.assignmentLeadTimeSec)
    }

    @Test
    fun `pull leaves every local value untouched when live_activity is absent`() = runBlocking {
        val prefs = freshPreferences()
        prefs.showInClass = true
        prefs.showClassPreparing = false
        prefs.showAssignment = true
        prefs.classPreparingLeadTimeSec = 1_800
        prefs.assignmentLeadTimeSec = 7_200
        val transport = RecordingTransport(
            existing = SettingsDocumentEnvelope(
                document = json("""{"assignments": {"enabled": true}}"""),
                revision = 1L,
            )
        )

        val result = pullLiveActivitySettings(prefs, transport, cloudSyncEnabled = true, syncLiveActivity = true, isLoggedIn = true)

        assertTrue("an absent section is not a failure", result)
        assertTrue("absent must not read as false", prefs.showInClass)
        assertFalse(prefs.showClassPreparing)
        assertTrue("absent must not read as false", prefs.showAssignment)
        assertEquals(1_800L, prefs.classPreparingLeadTimeSec)
        assertEquals(7_200L, prefs.assignmentLeadTimeSec)
    }

    @Test
    fun `pull leaves every local value untouched when live_activity is JSON null`() = runBlocking {
        val prefs = freshPreferences()
        prefs.showInClass = true
        prefs.showClassPreparing = false
        prefs.showAssignment = true
        prefs.classPreparingLeadTimeSec = 1_800
        prefs.assignmentLeadTimeSec = 7_200
        val transport = RecordingTransport(
            existing = SettingsDocumentEnvelope(document = json("""{"live_activity": null}"""), revision = 1L)
        )

        val result = pullLiveActivitySettings(prefs, transport, cloudSyncEnabled = true, syncLiveActivity = true, isLoggedIn = true)

        assertTrue("a null section is not a failure", result)
        assertTrue("a null section must not read as false", prefs.showInClass)
        assertFalse(prefs.showClassPreparing)
        assertTrue("a null section must not read as false", prefs.showAssignment)
        assertEquals(1_800L, prefs.classPreparingLeadTimeSec)
        assertEquals(7_200L, prefs.assignmentLeadTimeSec)
    }

    @Test
    fun `pull leaves just the missing fields untouched when live_activity has only some of them`() = runBlocking {
        val prefs = freshPreferences()
        prefs.showInClass = true
        prefs.showClassPreparing = true
        prefs.showAssignment = false
        prefs.classPreparingLeadTimeSec = 1_800
        prefs.assignmentLeadTimeSec = 7_200
        val transport = RecordingTransport(
            existing = SettingsDocumentEnvelope(
                document = json("""{"live_activity": {"show_in_class": false}}"""),
                revision = 1L,
            )
        )

        pullLiveActivitySettings(prefs, transport, cloudSyncEnabled = true, syncLiveActivity = true, isLoggedIn = true)

        assertFalse("the one present, valid field is applied", prefs.showInClass)
        assertTrue("a missing field keeps the local value, never false", prefs.showClassPreparing)
        assertFalse("a missing field keeps the local value", prefs.showAssignment)
        assertEquals(1_800L, prefs.classPreparingLeadTimeSec)
        assertEquals(7_200L, prefs.assignmentLeadTimeSec)
    }

    @Test
    fun `pull keeps the local value when a boolean field is the wrong JSON type`() = runBlocking {
        val prefs = freshPreferences()
        // Chosen so a naive `JsonPrimitive.getAsBoolean()` (which falls back to
        // `Boolean.parseBoolean(asString)` for a non-boolean primitive) would
        // read the string "true" as `true` -- the OPPOSITE of this starting
        // value -- so a missing type check flips this assertion instead of
        // passing by coincidence.
        prefs.showInClass = false
        val transport = RecordingTransport(
            existing = SettingsDocumentEnvelope(
                document = json("""{"live_activity": {"show_in_class": "true"}}"""),
                revision = 1L,
            )
        )

        pullLiveActivitySettings(prefs, transport, cloudSyncEnabled = true, syncLiveActivity = true, isLoggedIn = true)

        assertFalse(
            "a JSON string is not a JSON boolean, however it reads -- keep local",
            prefs.showInClass,
        )
    }

    /**
     * The trap this test exists to guard against: [SettingsDocumentTransport.read]
     * hands back an already-parsed [JsonObject], so a fractional value here goes
     * through Gson's *parsed-tree* int path (`JsonPrimitive.getAsInt()` ->
     * `Number.intValue()`), which truncates `0.5` to `0` silently -- no
     * exception the way the raw-text `JsonReader.nextInt()` path would throw.
     * A naive `.asInt` here would turn a half-second document value into `0`,
     * then clamping would turn that `0` into the 1-hour floor: a real,
     * plausible-looking user preference manufactured from garbage.
     */
    @Test
    fun `pull rejects a fractional lead-seconds value instead of silently truncating it`() = runBlocking {
        val prefs = freshPreferences()
        prefs.assignmentLeadTimeSec = 7_200
        val transport = RecordingTransport(
            existing = SettingsDocumentEnvelope(
                document = json("""{"live_activity": {"assignment_lead_seconds": 0.5}}"""),
                revision = 1L,
            )
        )

        pullLiveActivitySettings(prefs, transport, cloudSyncEnabled = true, syncLiveActivity = true, isLoggedIn = true)

        assertEquals(
            "0.5 must be rejected outright, not truncated to 0 and then clamped to the 1h floor",
            7_200L,
            prefs.assignmentLeadTimeSec,
        )
    }

    @Test
    fun `pull keeps the local value when an integer field is the wrong JSON type`() = runBlocking {
        val prefs = freshPreferences()
        // Gson's getAsInt() actually parses a numeric-looking STRING leniently
        // (LazilyParsedNumber), so "3600" would silently succeed as 3600 -- a
        // different value from this 7200 starting point -- unless the pull
        // path checks isNumber before trusting it.
        prefs.assignmentLeadTimeSec = 7_200
        val transport = RecordingTransport(
            existing = SettingsDocumentEnvelope(
                document = json("""{"live_activity": {"assignment_lead_seconds": "3600"}}"""),
                revision = 1L,
            )
        )

        pullLiveActivitySettings(prefs, transport, cloudSyncEnabled = true, syncLiveActivity = true, isLoggedIn = true)

        assertEquals(
            "a JSON string is not a JSON number, however numeric it looks -- keep local",
            7_200L,
            prefs.assignmentLeadTimeSec,
        )
    }

    // ── 7. The push queue: which triggers may mark this device dirty ───────
    //
    // Driven through NotificationSettingsSync's real entry points on the real
    // class, with virtual time standing in for its 250 ms debounce and its
    // retry backoffs. AuthService itself needs a real Context and the Android
    // Keystore to construct, so [Device.signIn] and [Device.logOut] replay
    // what its sign-in paths and logout() do to this class, in the same
    // order: the session appears, then pushIfUnconfirmed(); the session goes,
    // then cancelPendingPushes().

    /**
     * One account's `notification` document on the backend. Every write
     * lands (no conflicts), and [offline] makes every call throw the way
     * `SettingsDocumentApiClient` does on an `IOException`.
     */
    private class FakeDocumentServer : SettingsDocumentTransport {
        var document: JsonObject? = null
        var offline = false
        var beforeRead: () -> Unit = {}
        var duringWrite: () -> Unit = {}

        /**
         * When set, the next read answers with the document as it stood when
         * the request went out, but only once this completes: a response
         * still on the wire while other requests land. One-shot.
         */
        var holdRead: CompletableDeferred<Unit>? = null
        var reads = 0
            private set
        val writes = mutableListOf<JsonObject>()
        private var revision = 0L

        override suspend fun read(): SettingsDocumentEnvelope<JsonObject>? {
            if (offline) throw SettingsDocumentApiException("read notification: offline")
            reads++
            beforeRead()
            val served = document?.let { SettingsDocumentEnvelope(document = it, revision = revision) }
            holdRead?.let { response ->
                holdRead = null
                response.await()
            }
            return served
        }

        override suspend fun write(document: JsonObject, baseRevision: Long?): SettingsWriteResult<JsonObject> {
            if (offline) throw SettingsDocumentApiException("write notification: offline")
            duringWrite()
            writes += document
            this.document = document
            revision++
            return SettingsWriteResult.Written(revision)
        }
    }

    /**
     * In-memory [AssignmentReminderSyncStore]: [Device] plays the role
     * [AppPreferences] does in production, minus the parts that need a real
     * `Context`.
     */
    private class FakeAssignmentReminderSyncStore : AssignmentReminderSyncStore {
        override var enabled: Boolean = true
        override var offsets: Set<AssignmentReminderOffset> = AssignmentReminderOffset.DEFAULTS
        override var hasUnconfirmedEdit: Boolean = false
    }

    /**
     * One phone: its Live Update preferences, its assignment-reminder store,
     * the real [NotificationSettingsSync] running on [scope], and one
     * [FakeDocumentServer] per account, reached through whichever account is
     * signed in at the moment a request goes out.
     */
    private class Device(scope: CoroutineScope) {
        val prefs = LiveActivityPreferences(FakeLiveActivitySharedPreferences())
        val assignments = FakeAssignmentReminderSyncStore()
        var syncLiveActivity = true
        var syncAssignmentReminders = true
        val syncLiveActivityChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val syncAssignmentRemindersChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        private var signedInAs: String? = null
        private val servers = mutableMapOf<String, FakeDocumentServer>()

        fun server(account: String): FakeDocumentServer = servers.getOrPut(account) { FakeDocumentServer() }

        private fun session(): FakeDocumentServer =
            server(checkNotNull(signedInAs) { "a request went out with no session" })

        val sync = NotificationSettingsSync(
            transport = object : SettingsDocumentTransport {
                override suspend fun read(): SettingsDocumentEnvelope<JsonObject>? = session().read()

                override suspend fun write(
                    document: JsonObject,
                    baseRevision: Long?,
                ): SettingsWriteResult<JsonObject> = session().write(document, baseRevision)
            },
            liveActivityPreferences = prefs,
            assignmentStore = assignments,
            cloudSyncEnabled = { true },
            syncLiveActivity = { syncLiveActivity },
            syncAssignmentReminders = { syncAssignmentReminders },
            isLoggedIn = { signedInAs != null },
            syncLiveActivityChanged = syncLiveActivityChanged,
            syncAssignmentRemindersChanged = syncAssignmentRemindersChanged,
            scope = scope,
        )

        /** Each of AuthService's sign-in paths: the v3 session exists, then the catch-up. */
        fun signIn(account: String) {
            signedInAs = account
            sync.pushIfUnconfirmed()
        }

        /** AuthService.logout(): the tokens are wiped, then the queue is cancelled. */
        fun logOut() {
            signedInAs = null
            sync.cancelPendingPushes()
        }
    }

    /**
     * Runs the queue until it has nothing left to do. `advanceUntilIdle()`
     * cannot: it returns as soon as only [TestScope.backgroundScope] work is
     * left, which is where the queue runs, so it would never run the queue at
     * all — and every test below that expects nothing to happen would pass
     * for that reason alone. An hour of virtual time is far past the
     * debounce, both retry backoffs and every attempt between them.
     */
    private fun TestScope.runQueue() {
        advanceTimeBy(60 * 60 * 1_000L)
        runCurrent()
    }

    private fun FakeDocumentServer.lastClassLeadSeconds(): Int =
        writes.last().getAsJsonObject("live_activity").get("class_preparing_lead_seconds").asInt

    @Test
    fun `signing in with nothing pending leaves the account's document alone`() = runTest {
        val device = Device(backgroundScope)
        // What the user's iPhone wrote before this phone ever signed in.
        device.server("A").document = json("""{"live_activity": {"class_preparing_lead_seconds": 3600}}""")

        device.signIn("A")
        runQueue()

        assertEquals("nothing was pending, so signing in must not even read the document", 0, device.server("A").reads)
        assertTrue(device.server("A").writes.isEmpty())
        // ...so the first pull adopts the iPhone's value, instead of reading
        // back this phone's own default after a sign-in had pushed it.
        device.sync.pullNow()
        assertEquals(3_600L, device.prefs.classPreparingLeadTimeSec)
    }

    @Test
    fun `signing in pushes an edit made while signed out`() = runTest {
        val device = Device(backgroundScope)
        device.prefs.classPreparingLeadTimeSec = 1_800
        device.sync.markUnconfirmedAndPush()
        runQueue()
        assertTrue(
            "signed out, the push had nowhere to go, so the edit stays unconfirmed",
            device.prefs.hasUnconfirmedSyncEdit,
        )

        device.signIn("A")
        runQueue()

        assertEquals("the pending edit must be pushed on sign-in", 1, device.server("A").writes.size)
        assertEquals(1_800, device.server("A").lastClassLeadSeconds())
        assertFalse("the push landed, so the edit is now confirmed", device.prefs.hasUnconfirmedSyncEdit)
    }

    @Test
    fun `an edit still unconfirmed at logout is not pushed into the next account's document`() = runTest {
        val device = Device(backgroundScope)
        device.signIn("A")
        device.server("A").offline = true
        device.prefs.classPreparingLeadTimeSec = 1_800
        device.sync.markUnconfirmedAndPush()
        runQueue() // the push fails, both retries fail, and the queue gives up
        assertTrue(device.prefs.hasUnconfirmedSyncEdit)

        device.logOut()
        device.signIn("B")
        runQueue()

        assertTrue("account A's settings must not reach account B's document", device.server("B").writes.isEmpty())
    }

    @Test
    fun `a push still in its debounce at logout is dropped, not sent as the next account`() = runTest {
        val device = Device(backgroundScope)
        device.signIn("A")
        device.prefs.classPreparingLeadTimeSec = 1_800
        device.sync.markUnconfirmedAndPush()
        runCurrent() // the queue has taken the push and is waiting out the debounce

        device.logOut()
        device.signIn("B")
        runQueue()

        assertTrue("a push queued as account A must not run as account B", device.server("B").writes.isEmpty())
    }

    @Test
    fun `a retry still in its backoff at logout is dropped, not sent as the next account`() = runTest {
        val device = Device(backgroundScope)
        device.signIn("A")
        device.server("A").offline = true
        device.prefs.classPreparingLeadTimeSec = 1_800
        device.sync.markUnconfirmedAndPush()
        advanceTimeBy(1_000) // the first attempt has failed; its retry waits 5 s

        device.logOut()
        device.signIn("B")
        runQueue()

        assertTrue("a retry scheduled as account A must not run as account B", device.server("B").writes.isEmpty())
    }

    /**
     * After the loop's own generation check (just above, at the top of this
     * `for` block), a push can still make up to three requests — a read, a
     * write, and a second write after a 409 — and each can trigger a token
     * refresh or a full relogin first. If another account finishes signing
     * in inside that window, this device's values must not land in its
     * document. Simulated here by switching accounts from inside the fake
     * server's [FakeDocumentServer.beforeRead] hook, the same technique
     * `a relogin that fires inside a pull...` below uses for a relogin.
     */
    @Test
    fun `a push mid-flight is abandoned, not written into either account's document, if the account changes before its write`() =
        runTest {
            val device = Device(backgroundScope)
            device.signIn("A")
            device.prefs.classPreparingLeadTimeSec = 1_800
            // The account switch completes while this push's read is on the
            // wire, landing squarely in the window between the read and the
            // write.
            device.server("A").beforeRead = {
                device.logOut()
                device.signIn("B")
            }

            device.sync.markUnconfirmedAndPush()
            runQueue()

            assertTrue(
                "the push must not write account A's values into B's document",
                device.server("B").writes.isEmpty(),
            )
            assertTrue(
                "a push whose account changed mid-flight must not write at all",
                device.server("A").writes.isEmpty(),
            )
        }

    /**
     * The counterpart to the account-switch tests above: nothing about
     * closing that window may stop an ordinary retry that stays within the
     * same signed-in session. Guards `NotificationSettingsSync`'s retry
     * re-queue (`if (queuedGeneration == generation.get()) ...`): inverting
     * that comparison, or deleting the `trySend`, silently disables every
     * retry while every other test in this file stays green, because they
     * only ever exercise the *stale*-generation half of that line.
     */
    @Test
    fun `a retry within the same generation still fires and delivers the edit`() = runTest {
        val device = Device(backgroundScope)
        device.signIn("A")
        device.server("A").offline = true
        device.prefs.classPreparingLeadTimeSec = 1_800
        device.sync.markUnconfirmedAndPush()
        advanceTimeBy(1_000) // debounce elapses and the first attempt fails while offline; its retry waits 5 s
        device.server("A").offline = false // the network recovers before the retry fires; no account change at all
        runQueue()

        assertEquals("the same-generation retry must actually reach the server", 1, device.server("A").writes.size)
        assertEquals(1_800, device.server("A").lastClassLeadSeconds())
        assertFalse("the retry's push landed, so the edit is now confirmed", device.prefs.hasUnconfirmedSyncEdit)
    }

    @Test
    fun `a relogin that fires inside a pull does not stop that pull from applying`() = runTest {
        val device = Device(backgroundScope)
        device.signIn("A")
        device.server("A").document = json("""{"live_activity": {"class_preparing_lead_seconds": 3600}}""")
        // A lapsed refresh token sends AuthTokenManager.authHeader() into
        // AuthService.attemptRelogin while the pull's own request is being
        // built, and the relogin ends with the catch-up every sign-in makes.
        device.server("A").beforeRead = { device.sync.pushIfUnconfirmed() }

        val pulled = device.sync.pullNow()
        runQueue()

        assertTrue(pulled)
        assertEquals(
            "the pull must apply what it read, not mistake its own relogin for a local edit",
            3_600L,
            device.prefs.classPreparingLeadTimeSec,
        )
        assertTrue("nothing was pending, so nothing is pushed", device.server("A").writes.isEmpty())
    }

    @Test
    fun `turning live-activity sync on pushes this device's values even with nothing pending`() = runTest {
        val device = Device(backgroundScope)
        device.syncLiveActivity = false
        device.signIn("A")
        runQueue()

        device.syncLiveActivity = true
        device.syncLiveActivityChanged.emit(Unit)
        runQueue()

        assertEquals("the switch turning on republishes this device's values", 1, device.server("A").writes.size)
    }

    @Test
    fun `an edit that lands while its push is in flight stays unconfirmed until its own push lands`() = runTest {
        val device = Device(backgroundScope)
        device.signIn("A")
        device.prefs.classPreparingLeadTimeSec = 1_800
        // A second edit lands while the first push's PUT is on the wire. Its
        // own push is queued behind this one, and if the process dies before
        // that runs, the flag is all that remembers the edit.
        device.server("A").duringWrite = {
            device.server("A").duringWrite = {}
            device.prefs.classPreparingLeadTimeSec = 2_700
        }
        device.sync.markUnconfirmedAndPush()
        runQueue()

        assertEquals(1_800, device.server("A").lastClassLeadSeconds())
        assertTrue(
            "the server holds 1800 while this phone holds 2700, so the edit must stay unconfirmed",
            device.prefs.hasUnconfirmedSyncEdit,
        )

        // The next catch-up delivers it, and only then is it confirmed.
        device.sync.pushIfUnconfirmed()
        runQueue()

        assertEquals(2_700, device.server("A").lastClassLeadSeconds())
        assertFalse(device.prefs.hasUnconfirmedSyncEdit)
    }

    // ── 7b. The push queue: assignment-reminder settings go through it too ──
    //
    // Not a re-proof of the generation/backoff/offline machinery above —
    // that machinery is shared code, already exercised there — just the
    // branch-specific behavior this unit adds: an assignment edit actually
    // triggers a push through the very same queue, one queued trigger can
    // carry both sections, the assignments gate is independent of
    // live_activity's, and both unconfirmed-edit flags are tracked and
    // cleared independently.

    @Test
    fun `an assignment edit pushes through the same queue and confirms`() = runTest {
        val device = Device(backgroundScope)
        device.signIn("A")
        device.assignments.enabled = false
        device.assignments.offsets = setOf(AssignmentReminderOffset.HR2)

        device.sync.markAssignmentUnconfirmedAndPush()
        runQueue()

        assertEquals("only the dirty section pushed", 1, device.server("A").writes.size)
        val section = device.server("A").writes.single().getAsJsonObject("assignments")
        assertEquals(false, section.get("enabled").asBoolean)
        assertEquals(listOf(120), section.getAsJsonArray("reminder_offsets_minutes").map { it.asInt })
        assertFalse("the push landed, so the edit is now confirmed", device.assignments.hasUnconfirmedEdit)
    }

    @Test
    fun `one queued trigger carries both sections when both are dirty`() = runTest {
        val device = Device(backgroundScope)
        device.signIn("A")
        device.prefs.classPreparingLeadTimeSec = 1_800
        device.assignments.enabled = false

        // CONFLATED: both marks collapse into the one generation the queue
        // is holding, so this is one queued trigger, not two.
        device.sync.markUnconfirmedAndPush()
        device.sync.markAssignmentUnconfirmedAndPush()
        runQueue()

        val writes = device.server("A").writes
        assertEquals("one queued run makes two requests, one per dirty section", 2, writes.size)
        assertTrue(writes.first().has("live_activity"))
        assertTrue(writes.last().has("assignments"))
        assertFalse(device.prefs.hasUnconfirmedSyncEdit)
        assertFalse(device.assignments.hasUnconfirmedEdit)
    }

    @Test
    fun `the assignments switch off leaves that edit unconfirmed without blocking the live_activity push`() = runTest {
        val device = Device(backgroundScope)
        device.signIn("A")
        device.syncAssignmentReminders = false
        device.prefs.classPreparingLeadTimeSec = 1_800
        device.assignments.enabled = false

        device.sync.markUnconfirmedAndPush()
        device.sync.markAssignmentUnconfirmedAndPush()
        runQueue()

        assertEquals("only live_activity's gate was open", 1, device.server("A").writes.size)
        assertEquals(1_800, device.server("A").lastClassLeadSeconds())
        assertFalse(device.prefs.hasUnconfirmedSyncEdit)
        assertTrue(
            "the switch is off, so this edit was never attempted and must stay unconfirmed for the next catch-up",
            device.assignments.hasUnconfirmedEdit,
        )
    }

    @Test
    fun `turning assignment-reminder sync on pushes this device's values even with nothing pending`() = runTest {
        val device = Device(backgroundScope)
        device.syncAssignmentReminders = false
        device.signIn("A")
        runQueue()

        device.syncAssignmentReminders = true
        device.syncAssignmentRemindersChanged.emit(Unit)
        runQueue()

        assertEquals("the switch turning on republishes this device's values", 1, device.server("A").writes.size)
        assertTrue(device.server("A").writes.single().has("assignments"))
    }

    @Test
    fun `an unconfirmed assignment edit at logout is not pushed into the next account's document`() = runTest {
        val device = Device(backgroundScope)
        device.signIn("A")
        device.server("A").offline = true
        device.assignments.enabled = false
        device.sync.markAssignmentUnconfirmedAndPush()
        runQueue() // the push fails, both retries fail, and the queue gives up
        assertTrue(device.assignments.hasUnconfirmedEdit)

        device.logOut()
        device.signIn("B")
        runQueue()

        assertTrue("account A's settings must not reach account B's document", device.server("B").writes.isEmpty())
    }

    @Test
    fun `pushIfUnconfirmed delivers a pending assignment edit with no live_activity edit involved`() = runTest {
        val device = Device(backgroundScope)
        device.assignments.enabled = false
        device.sync.markAssignmentUnconfirmedAndPush()
        runQueue()
        assertTrue("signed out, the push had nowhere to go", device.assignments.hasUnconfirmedEdit)

        device.signIn("A")
        runQueue()

        assertEquals(1, device.server("A").writes.size)
        assertFalse(device.assignments.hasUnconfirmedEdit)
    }

    // ── 7c. A pull never reverts an edit, or lands between an edit's push and its apply ──
    //
    // Driven through the real pullNow(), not the pure pull function: the
    // edit has to land while the pull's GET is on the wire, and what goes
    // wrong is pullNow() writing back values it read before that. Live-
    // activity sync is off in each, so the one GET a pull makes is the
    // assignments one.

    private val defaultsDocument = """
        {"assignments": {
          "enabled": true,
          "reminder_offsets_hours": [48, 24, 8, 2, 1],
          "reminder_offsets_minutes": [2880, 1440, 480, 120, 60, 30]
        }}
    """.trimIndent()

    private fun FakeDocumentServer.lastAssignments(): JsonObject = writes.last().getAsJsonObject("assignments")

    private fun FakeDocumentServer.lastAssignmentMinutes(): List<Int> =
        lastAssignments().getAsJsonArray("reminder_offsets_minutes").map { it.asInt }

    @Test
    fun `pull does not overwrite assignment values while a push has not yet been confirmed`() = runTest {
        val device = Device(backgroundScope)
        device.syncLiveActivity = false
        device.signIn("A")
        device.server("A").document = json(defaultsDocument)
        // The user turns 10 分鐘 on while the screen's pull is reading.
        device.server("A").beforeRead = {
            device.server("A").beforeRead = {}
            device.assignments.offsets = AssignmentReminderOffset.DEFAULTS + AssignmentReminderOffset.MIN10
            device.sync.markAssignmentUnconfirmedAndPush()
        }

        device.sync.pullNow()

        assertEquals(
            "the tap must survive the pull that was reading when it landed",
            AssignmentReminderOffset.DEFAULTS + AssignmentReminderOffset.MIN10,
            device.assignments.offsets,
        )
        runQueue()
        assertEquals(
            "the edit, not the values from before it, must reach the user's other devices",
            listOf(2_880, 1_440, 480, 120, 60, 30, 10),
            device.server("A").lastAssignmentMinutes(),
        )
        assertFalse(device.assignments.hasUnconfirmedEdit)
    }

    @Test
    fun `turning assignment reminders off while a pull is reading is not reverted`() = runTest {
        val device = Device(backgroundScope)
        device.syncLiveActivity = false
        device.signIn("A")
        device.server("A").document = json(defaultsDocument)
        device.server("A").beforeRead = {
            device.server("A").beforeRead = {}
            device.assignments.enabled = false
            device.sync.markAssignmentUnconfirmedAndPush()
        }

        device.sync.pullNow()

        assertFalse("the switch must stay off, not flip back on", device.assignments.enabled)
        runQueue()
        assertEquals(false, device.server("A").lastAssignments().get("enabled").asBoolean)
        assertFalse(device.assignments.hasUnconfirmedEdit)
    }

    /**
     * The pull's GET goes out before an edit, and its response is still on
     * the wire when the edit's push has landed and cleared the flag. That
     * response is older than the edit and must not be applied over it.
     */
    @Test
    fun `a pull response still on the wire when an edit's push lands is not applied over that edit`() = runTest {
        val device = Device(backgroundScope)
        device.syncLiveActivity = false
        device.signIn("A")
        // What the iPhone wrote before this pull started: 24 小時 only.
        device.server("A").document = json("""{"assignments": {"enabled": true, "reminder_offsets_minutes": [1440]}}""")
        val response = CompletableDeferred<Unit>()
        device.server("A").holdRead = response
        val pull = launch { device.sync.pullNow() }
        runCurrent() // the GET is out; its response is on the wire

        device.assignments.offsets = AssignmentReminderOffset.DEFAULTS + AssignmentReminderOffset.MIN10
        device.sync.markAssignmentUnconfirmedAndPush()
        runQueue()
        response.complete(Unit)
        pull.join()
        runQueue()

        assertEquals(
            "a response read before the edit must not be applied over it",
            AssignmentReminderOffset.DEFAULTS + AssignmentReminderOffset.MIN10,
            device.assignments.offsets,
        )
        assertEquals(listOf(2_880, 1_440, 480, 120, 60, 30, 10), device.server("A").lastAssignmentMinutes())
    }

    /**
     * Same window, but the edit is undone before its push runs, so the
     * values end where the pull's snapshot had them and comparing against
     * that snapshot cannot notice anything happened. Only running the pull
     * on the same queue as the push keeps the edit's push from landing
     * between the pull's read and its apply.
     */
    @Test
    fun `a push cannot land between a pull's read and its apply`() = runTest {
        val device = Device(backgroundScope)
        device.syncLiveActivity = false
        device.signIn("A")
        device.server("A").document = json("""{"assignments": {"enabled": true, "reminder_offsets_minutes": [1440]}}""")
        val response = CompletableDeferred<Unit>()
        device.server("A").holdRead = response
        val pull = launch { device.sync.pullNow() }
        runCurrent()

        // 10 分鐘 on and straight back off: an edit all the same, whose push
        // carries this phone's values over the iPhone's.
        device.assignments.offsets = AssignmentReminderOffset.DEFAULTS + AssignmentReminderOffset.MIN10
        device.sync.markAssignmentUnconfirmedAndPush()
        device.assignments.offsets = AssignmentReminderOffset.DEFAULTS
        device.sync.markAssignmentUnconfirmedAndPush()
        runQueue()
        response.complete(Unit)
        pull.join()
        runQueue()

        assertEquals(listOf(2_880, 1_440, 480, 120, 60, 30), device.server("A").lastAssignmentMinutes())
        assertEquals(
            "the phone must hold what its own push left on the server, not the response it read before",
            AssignmentReminderOffset.DEFAULTS,
            device.assignments.offsets,
        )
    }

    // ── 8. Bounded retry after a push failure ───────────────────────────────

    @Test
    fun `a failed push is retried a bounded number of times, then gives up`() {
        assertTrue("first consecutive failure schedules a retry", shouldRetryAfterPushFailure(1, maxRetries = 2))
        assertTrue("second consecutive failure schedules a retry", shouldRetryAfterPushFailure(2, maxRetries = 2))
        assertFalse(
            "a third consecutive failure gives up instead of retrying forever",
            shouldRetryAfterPushFailure(3, maxRetries = 2),
        )
        assertFalse(
            "an arbitrarily large failure count must never resume retrying",
            shouldRetryAfterPushFailure(1_000, maxRetries = 2),
        )
        // Production wiring relies on the default bound.
        assertTrue(shouldRetryAfterPushFailure(MAX_PUSH_RETRIES))
        assertFalse(shouldRetryAfterPushFailure(MAX_PUSH_RETRIES + 1))
    }

    @Test
    fun `push retry backoff increases and is never immediate`() {
        assertTrue(
            "the first retry must actually wait, not fire inside the same debounce window",
            pushRetryBackoffMillis(1) >= 1_000L,
        )
        assertTrue(
            "backoff must increase, not repeat the same short wait forever",
            pushRetryBackoffMillis(2) > pushRetryBackoffMillis(1),
        )
    }

    // ── 9. A pull must never crash on a transport failure ──────────────────

    private fun throwingTransport(failure: Throwable): SettingsDocumentTransport =
        object : SettingsDocumentTransport {
            override suspend fun read(): SettingsDocumentEnvelope<JsonObject> = throw failure
            override suspend fun write(
                document: JsonObject,
                baseRevision: Long?,
            ): SettingsWriteResult<JsonObject> = error("not used by this test")
        }

    /**
     * Mirrors the hazard fixed at `LiveActivitySettingsViewModel`'s `init` block:
     * opening the Live Activity settings screen with no connectivity used to
     * crash the app outright, because `SettingsDocumentApiClient.read()` throws
     * on a transport failure and nothing caught it before it reached
     * `viewModelScope`, which has no `CoroutineExceptionHandler`.
     * `LiveActivitySettingsViewModel` itself needs a real Context/Keystore-backed
     * Hilt graph and cannot be constructed in this module's plain-JVM tests, so
     * this pins the extracted catching wrapper it now calls instead.
     */
    @Test
    fun `pullLiveActivitySettingsCatching survives a transport failure and leaves local values untouched`() = runBlocking {
        val prefs = freshPreferences()
        // Non-default, so "kept local" cannot be confused with "reset".
        prefs.showInClass = false
        prefs.assignmentLeadTimeSec = 7_200
        var reportedFailure: Throwable? = null

        val result = pullLiveActivitySettingsCatching(
            preferences = prefs,
            transport = throwingTransport(SettingsDocumentApiException("read notification failed: HTTP 000 offline")),
            cloudSyncEnabled = true,
            syncLiveActivity = true,
            isLoggedIn = true,
            onFailure = { e -> reportedFailure = e },
        )

        assertFalse("a caught transport failure must not report as a successful pull", result)
        assertTrue(
            "the caller must be told what failed, not have it silently swallowed",
            reportedFailure is SettingsDocumentApiException,
        )
        assertFalse("local values must survive a failed pull untouched", prefs.showInClass)
        assertEquals(7_200L, prefs.assignmentLeadTimeSec)
    }

    @Test
    fun `cancellation during pull is rethrown, not caught and reported as an ordinary failure`() {
        var reportedFailure: Throwable? = null

        assertThrows(CancellationException::class.java) {
            runBlocking {
                pullLiveActivitySettingsCatching(
                    preferences = freshPreferences(),
                    transport = throwingTransport(CancellationException("scope cancelled")),
                    cloudSyncEnabled = true,
                    syncLiveActivity = true,
                    isLoggedIn = true,
                    onFailure = { e -> reportedFailure = e },
                )
            }
        }

        assertNull("cancellation must propagate, not be reported through onFailure", reportedFailure)
    }

    // ── 10. A pull must not silently discard an unconfirmed local edit ─────

    /**
     * With no protection, "edit a value, the push fails, reopen the screen"
     * ends with the pull's stale server value silently overwriting the user's
     * own edit — no error, no trace. That is data loss, not merely a delayed
     * propagation, which is why the guard has to persist rather than live in
     * memory. `isLocalDirty` is [LiveActivityPreferences.hasUnconfirmedSyncEdit]
     * at the production call site; this drives it directly since the Hilt class
     * cannot be constructed here.
     */
    @Test
    fun `pull does not overwrite local values while a push has not yet been confirmed`() = runBlocking {
        val prefs = freshPreferences()
        // Non-default, and the opposite of what "the server" holds below, so
        // "overwritten by the document" and "kept local" are distinguishable.
        prefs.showInClass = false
        prefs.assignmentLeadTimeSec = 7_200
        val transport = RecordingTransport(
            existing = SettingsDocumentEnvelope(
                document = json(
                    """{"live_activity": {"show_in_class": true, "assignment_lead_seconds": 3600}}"""
                ),
                revision = 1L,
            )
        )
        var onLocalDirtyCalled = false

        val result = pullLiveActivitySettings(
            preferences = prefs,
            transport = transport,
            cloudSyncEnabled = true,
            syncLiveActivity = true,
            isLoggedIn = true,
            isLocalDirty = { true },
            onLocalDirty = { onLocalDirtyCalled = true },
        )

        assertTrue("a read was still attempted", result)
        assertTrue(
            "the caller must be told to re-push rather than silently losing the edit",
            onLocalDirtyCalled,
        )
        assertFalse(
            "an unconfirmed local edit must survive a pull, not be overwritten by the server's older value",
            prefs.showInClass,
        )
        assertEquals(
            "an unconfirmed local edit must survive a pull, not be overwritten by the server's older value",
            7_200L,
            prefs.assignmentLeadTimeSec,
        )
    }

    /**
     * The signal `pullNow()` reads via `isLocalDirty` above
     * (`LiveActivityPreferences.hasUnconfirmedSyncEdit`) has to survive the
     * process being killed between an edit and its confirmation — an
     * in-memory-only flag or counter cannot, and silently reopens the exact
     * defect the previous test pins. A JVM test cannot literally restart a
     * process, but constructing a *second*, independent
     * [LiveActivityPreferences] instance over the *same* backing
     * [SharedPreferences] is exactly what that looks like against real
     * Android storage: a fresh object, the same on-disk key-value store.
     */
    @Test
    fun `hasUnconfirmedSyncEdit survives a new LiveActivityPreferences instance over the same storage`() {
        val storage = FakeLiveActivitySharedPreferences()
        val beforeRestart = LiveActivityPreferences(storage)
        assertFalse(
            "a fresh install (or a build that never wrote this key) has nothing to protect",
            beforeRestart.hasUnconfirmedSyncEdit,
        )

        beforeRestart.hasUnconfirmedSyncEdit = true

        val afterRestart = LiveActivityPreferences(storage)
        assertTrue(
            "an edit must still be flagged unconfirmed after the process that made it is gone",
            afterRestart.hasUnconfirmedSyncEdit,
        )

        afterRestart.hasUnconfirmedSyncEdit = false
        val afterConfirmedPush = LiveActivityPreferences(storage)
        assertFalse(
            "clearing the flag must also survive a restart, or every device would look permanently dirty",
            afterConfirmedPush.hasUnconfirmedSyncEdit,
        )
    }

    // ── 11. The wire keys must not be able to silently drift from the DTO ──

    /**
     * The highest-value test the prior round was missing: push known values
     * through the real write path, feed the exact document it produced
     * straight into the read path, and assert the five values survive. This is
     * what would actually catch a divergence between the two sides' wire keys
     * — both the key strings and the section name — rather than relying on
     * two independent string literals happening to agree.
     */
    @Test
    fun `a document written by the push path round-trips through the pull path unchanged`() = runBlocking {
        val pushTransport = RecordingTransport(existing = null)
        pushLiveActivitySettings(
            local = local,
            transport = pushTransport,
            cloudSyncEnabled = true,
            syncLiveActivity = true,
            isLoggedIn = true,
        )
        val writtenDocument = pushTransport.writtenDocuments.single()

        val prefs = freshPreferences()
        // Deliberately opposite of `local`'s five values, so the assertions
        // below prove the round trip actually moved data rather than
        // coincidentally matching a default or an unrelated starting value.
        prefs.showInClass = !local.showInClass
        prefs.showClassPreparing = !local.showClassPreparing
        prefs.showAssignment = !local.showAssignment
        prefs.classPreparingLeadTimeSec = 3_600
        prefs.assignmentLeadTimeSec = 14_400
        val pullTransport = RecordingTransport(
            existing = SettingsDocumentEnvelope(document = writtenDocument, revision = 1L)
        )

        pullLiveActivitySettings(
            preferences = prefs,
            transport = pullTransport,
            cloudSyncEnabled = true,
            syncLiveActivity = true,
            isLoggedIn = true,
        )

        assertEquals(local.showInClass, prefs.showInClass)
        assertEquals(local.showClassPreparing, prefs.showClassPreparing)
        assertEquals(local.showAssignment, prefs.showAssignment)
        assertEquals(local.classPreparingLeadSeconds.toLong(), prefs.classPreparingLeadTimeSec)
        assertEquals(local.assignmentLeadSeconds.toLong(), prefs.assignmentLeadTimeSec)
    }

    // ── 12. The other half of the hand-rolled integer guard ────────────────

    @Test
    fun `pull keeps the local value when an integer field is out of Int range`() = runBlocking {
        val prefs = freshPreferences()
        prefs.assignmentLeadTimeSec = 7_200
        val transport = RecordingTransport(
            existing = SettingsDocumentEnvelope(
                document = json("""{"live_activity": {"assignment_lead_seconds": 1e19}}"""),
                revision = 1L,
            )
        )

        pullLiveActivitySettings(prefs, transport, cloudSyncEnabled = true, syncLiveActivity = true, isLoggedIn = true)

        assertEquals(
            "a value far outside Int range must be rejected, not accepted via an overflowed conversion",
            7_200L,
            prefs.assignmentLeadTimeSec,
        )
    }

    // ── 13. resolveOffsets: document offsets -> the local enum set ─────────

    @Test
    fun `resolveOffsets keeps the local set when neither document field is present`() {
        val local = setOf(AssignmentReminderOffset.HR16, AssignmentReminderOffset.MIN10)
        assertEquals(local, resolveOffsets(documentMinutes = null, documentHours = null, currentLocal = local))
    }

    @Test
    fun `resolveOffsets prefers minutes over hours whenever minutes is a list`() {
        val resolved = resolveOffsets(
            documentMinutes = listOf(30),
            documentHours = listOf(24, 2),
            currentLocal = setOf(AssignmentReminderOffset.HR16),
        )
        assertEquals(setOf(AssignmentReminderOffset.MIN30), resolved)
    }

    @Test
    fun `resolveOffsets treats an empty minutes list as the user turning everything off`() {
        val local = setOf(AssignmentReminderOffset.HR48, AssignmentReminderOffset.MIN30)
        val resolved = resolveOffsets(documentMinutes = emptyList(), documentHours = listOf(24), currentLocal = local)
        assertEquals(emptySet<AssignmentReminderOffset>(), resolved)
    }

    @Test
    fun `resolveOffsets falls back to hours and keeps the local sub-hour offsets when minutes is absent`() {
        // HR16 is not among the document's hours, so it must be dropped;
        // MIN10 (sub-hour) must survive, because the hours field cannot
        // express it either way -- its absence is not evidence it was
        // turned off.
        val local = setOf(AssignmentReminderOffset.HR16, AssignmentReminderOffset.MIN10)
        val resolved = resolveOffsets(documentMinutes = null, documentHours = listOf(24, 2), currentLocal = local)
        assertEquals(
            setOf(AssignmentReminderOffset.HR24, AssignmentReminderOffset.HR2, AssignmentReminderOffset.MIN10),
            resolved,
        )
    }

    @Test
    fun `resolveOffsets drops a minute value no local case represents`() {
        val resolved = resolveOffsets(documentMinutes = listOf(2_880, 999), documentHours = null, currentLocal = emptySet())
        assertEquals(setOf(AssignmentReminderOffset.HR48), resolved)
    }

    // ── 14. pushAssignmentSettings: local -> document mapping ──────────────

    /** Deliberately non-default: enabled=false (default true), and a mixed hour/sub-hour selection. */
    private val localAssignments = AssignmentSyncValues(
        enabled = false,
        offsets = setOf(AssignmentReminderOffset.HR48, AssignmentReminderOffset.HR1, AssignmentReminderOffset.MIN30),
    )

    @Test
    fun `assignment local values map onto the assignments keys, both lists populated`() = runBlocking {
        val transport = RecordingTransport(existing = null)

        val written = pushAssignmentSettings(
            local = localAssignments,
            transport = transport,
            cloudSyncEnabled = true,
            syncAssignmentReminders = true,
            isLoggedIn = true,
        )

        assertTrue("the write landed", written)
        val document = transport.writtenDocuments.single()
        assertEquals(setOf("assignments"), document.keySet())
        val section = document.getAsJsonObject("assignments")
        assertEquals(setOf("enabled", "reminder_offsets_hours", "reminder_offsets_minutes"), section.keySet())
        assertEquals(false, section.get("enabled").asBoolean)
        assertEquals(
            "the complete set, sub-hour included, descending",
            listOf(2_880, 60, 30),
            section.getAsJsonArray("reminder_offsets_minutes").map { it.asInt },
        )
        assertEquals(
            "MIN30 has no whole-hour representation, so it must be excluded from hours",
            listOf(48, 1),
            section.getAsJsonArray("reminder_offsets_hours").map { it.asInt },
        )
    }

    @Test
    fun `an assignments push leaves every section it does not own untouched`() = runBlocking {
        val server = json(serverDocumentJson)
        val transport = RecordingTransport(existing = SettingsDocumentEnvelope(document = server, revision = 7L))

        pushAssignmentSettings(
            local = localAssignments,
            transport = transport,
            cloudSyncEnabled = true,
            syncAssignmentReminders = true,
            isLoggedIn = true,
        )

        val document = transport.writtenDocuments.single()
        assertEquals(7L, transport.baseRevisions.single())
        assertEquals("courses must come back exactly as it went in", server.getAsJsonObject("courses"), document.getAsJsonObject("courses"))
        assertEquals(
            "live_activity must come back exactly as it went in",
            server.getAsJsonObject("live_activity"),
            document.getAsJsonObject("live_activity"),
        )
        assertEquals(
            "an unknown top-level section must come back exactly as it went in",
            server.getAsJsonObject("some_future_section"),
            document.getAsJsonObject("some_future_section"),
        )
        assertEquals("nothing invented or dropped at the top level", server.keySet(), document.keySet())
    }

    @Test
    fun `an unknown key nested inside assignments survives a push`() = runBlocking {
        val server = json(
            """
            {
              "assignments": {
                "enabled": true,
                "reminder_offsets_hours": [24],
                "reminder_offsets_minutes": [1440],
                "snooze_minutes": 5
              }
            }
            """.trimIndent()
        )
        val transport = RecordingTransport(existing = SettingsDocumentEnvelope(document = server, revision = 3L))

        pushAssignmentSettings(
            local = localAssignments,
            transport = transport,
            cloudSyncEnabled = true,
            syncAssignmentReminders = true,
            isLoggedIn = true,
        )

        val section = transport.writtenDocuments.single().getAsJsonObject("assignments")
        assertTrue("an unknown key nested inside assignments must survive", section.has("snooze_minutes"))
        assertEquals(5, section.get("snooze_minutes").asInt)
    }

    @Test
    fun `a minute value no local case represents survives an assignments push, kept in step with hours when whole-hour`() = runBlocking {
        val server = json(
            """{"assignments": {"enabled": true, "reminder_offsets_minutes": [2880, 180, 47]}}"""
        )
        // Local selection is just HR48 (2880), already in the document -- this
        // push's only "intent" is to confirm enabled/HR48, not to touch 180
        // (3h, a value no fixed case represents) or 47 (not even a whole hour).
        val local = AssignmentSyncValues(enabled = true, offsets = setOf(AssignmentReminderOffset.HR48))
        val transport = RecordingTransport(existing = SettingsDocumentEnvelope(document = server, revision = 5L))

        pushAssignmentSettings(
            local = local,
            transport = transport,
            cloudSyncEnabled = true,
            syncAssignmentReminders = true,
            isLoggedIn = true,
        )

        val section = transport.writtenDocuments.single().getAsJsonObject("assignments")
        assertEquals(
            "both foreign values must survive in minutes, not just the one this build's enum knows",
            listOf(2_880, 180, 47),
            section.getAsJsonArray("reminder_offsets_minutes").map { it.asInt },
        )
        assertEquals(
            "180 (3h, a whole hour) must be kept in step in hours too; 47 must not, since it isn't one",
            listOf(48, 3),
            section.getAsJsonArray("reminder_offsets_hours").map { it.asInt },
        )
    }

    @Test
    fun `an assignments push conflict adopts the server document and retries exactly once`() = runBlocking {
        val local = AssignmentSyncValues(enabled = true, offsets = setOf(AssignmentReminderOffset.HR24))
        val winner = json("""{"assignments": {"enabled": false}, "written_by_another_device": true}""")
        val transport = RecordingTransport(
            existing = SettingsDocumentEnvelope(document = json(serverDocumentJson), revision = 7L),
            outcomes = listOf(
                SettingsWriteResult.Conflict(SettingsDocumentEnvelope(document = winner, revision = 9L)),
                SettingsWriteResult.Written(10L),
            ),
        )

        val written = pushAssignmentSettings(
            local = local,
            transport = transport,
            cloudSyncEnabled = true,
            syncAssignmentReminders = true,
            isLoggedIn = true,
        )

        assertTrue("the retry landed", written)
        assertEquals(2, transport.writtenDocuments.size)
        assertEquals(listOf(7L, 9L), transport.baseRevisions)
        val retried = transport.writtenDocuments[1]
        assertEquals(
            "this device's own edit must overwrite the winner's stale enabled, not the other way round",
            true,
            retried.getAsJsonObject("assignments").get("enabled").asBoolean,
        )
        assertTrue(
            "a key only the winning document had must survive the rebase",
            retried.has("written_by_another_device"),
        )
    }

    @Test
    fun `a second assignments push conflict gives up instead of retrying forever`() {
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
                pushAssignmentSettings(
                    local = localAssignments,
                    transport = transport,
                    cloudSyncEnabled = true,
                    syncAssignmentReminders = true,
                    isLoggedIn = true,
                )
            }
        }

        assertEquals(2, transport.writtenDocuments.size)
        assertTrue(thrown.message.orEmpty().contains(NOTIFICATION_SETTINGS_NAMESPACE))
    }

    @Test
    fun `assignment reminders switch off sends no push request at all`() = runBlocking {
        val transport = RecordingTransport(existing = SettingsDocumentEnvelope(document = json(serverDocumentJson), revision = 7L))

        val written = pushAssignmentSettings(
            local = localAssignments, transport = transport,
            cloudSyncEnabled = true, syncAssignmentReminders = false, isLoggedIn = true,
        )

        assertFalse(written)
        assertEquals("must not even read the document", 0, transport.readCount)
        assertTrue(transport.writtenDocuments.isEmpty())
    }

    @Test
    fun `cloud sync off sends no assignments push request at all`() = runBlocking {
        val transport = RecordingTransport(existing = SettingsDocumentEnvelope(document = json(serverDocumentJson), revision = 7L))

        val written = pushAssignmentSettings(
            local = localAssignments, transport = transport,
            cloudSyncEnabled = false, syncAssignmentReminders = true, isLoggedIn = true,
        )

        assertFalse(written)
        assertEquals(0, transport.readCount)
    }

    @Test
    fun `signed out sends no assignments push request at all`() = runBlocking {
        val transport = RecordingTransport(existing = SettingsDocumentEnvelope(document = json(serverDocumentJson), revision = 7L))

        val written = pushAssignmentSettings(
            local = localAssignments, transport = transport,
            cloudSyncEnabled = true, syncAssignmentReminders = true, isLoggedIn = false,
        )

        assertFalse(written)
        assertEquals(0, transport.readCount)
    }

    // ── 15. pullAssignmentSettings: the four degrade-to-local cases ────────
    //
    // Each starts from a deliberately non-default local value -- enabled is
    // false (default true) and the offsets are neither DEFAULTS nor empty --
    // so "kept local" and "reset to default" cannot look alike.

    private fun nonDefaultAssignments() = AssignmentSyncValues(
        enabled = false,
        offsets = setOf(AssignmentReminderOffset.HR16, AssignmentReminderOffset.MIN10),
    )

    @Test
    fun `pull keeps local assignment values when the section is absent`() = runBlocking {
        val current = nonDefaultAssignments()
        val transport = RecordingTransport(
            existing = SettingsDocumentEnvelope(document = json("""{"live_activity": {"show_in_class": true}}"""), revision = 1L)
        )

        val resolved = pullAssignmentSettings(current, transport, cloudSyncEnabled = true, syncAssignmentReminders = true, isLoggedIn = true)

        assertNull("an absent section has nothing to apply; the local values stand as they are", resolved)
    }

    @Test
    fun `pull keeps local assignment values when the section is JSON null`() = runBlocking {
        val current = nonDefaultAssignments()
        val transport = RecordingTransport(
            existing = SettingsDocumentEnvelope(document = json("""{"assignments": null}"""), revision = 1L)
        )

        val resolved = pullAssignmentSettings(current, transport, cloudSyncEnabled = true, syncAssignmentReminders = true, isLoggedIn = true)

        assertNull("a null section has nothing to apply; the local values stand as they are", resolved)
    }

    @Test
    fun `pull keeps local values for fields the section omits`() = runBlocking {
        val current = nonDefaultAssignments()
        val transport = RecordingTransport(
            existing = SettingsDocumentEnvelope(document = json("""{"assignments": {}}"""), revision = 1L)
        )

        val resolved = pullAssignmentSettings(current, transport, cloudSyncEnabled = true, syncAssignmentReminders = true, isLoggedIn = true)

        assertEquals("a field missing from an otherwise-present section must not reset to defaults", current, resolved)
    }

    @Test
    fun `pull keeps local values when fields are the wrong JSON type`() = runBlocking {
        val current = nonDefaultAssignments()
        val transport = RecordingTransport(
            existing = SettingsDocumentEnvelope(
                document = json(
                    """
                    {"assignments": {
                        "enabled": "true",
                        "reminder_offsets_minutes": {"not": "a list"},
                        "reminder_offsets_hours": "24"
                    }}
                    """.trimIndent()
                ),
                revision = 1L,
            )
        )

        val resolved = pullAssignmentSettings(current, transport, cloudSyncEnabled = true, syncAssignmentReminders = true, isLoggedIn = true)

        assertEquals(
            "a JSON string is not a JSON boolean or a JSON array, however each reads -- keep local",
            current,
            resolved,
        )
    }

    // ── 16. pullAssignmentSettings: precedence and gates ────────────────────

    @Test
    fun `pull applies enabled and prefers minutes over hours`() = runBlocking {
        val current = AssignmentSyncValues(enabled = false, offsets = setOf(AssignmentReminderOffset.HR16))
        val transport = RecordingTransport(
            existing = SettingsDocumentEnvelope(
                document = json(
                    """{"assignments": {"enabled": true, "reminder_offsets_hours": [24, 2], "reminder_offsets_minutes": [30]}}"""
                ),
                revision = 1L,
            )
        )

        val resolved = pullAssignmentSettings(current, transport, cloudSyncEnabled = true, syncAssignmentReminders = true, isLoggedIn = true)

        assertEquals(AssignmentSyncValues(enabled = true, offsets = setOf(AssignmentReminderOffset.MIN30)), resolved)
    }

    @Test
    fun `pull falls back to hours when minutes is absent, keeping local sub-hour offsets`() = runBlocking {
        val current = AssignmentSyncValues(
            enabled = true,
            offsets = setOf(AssignmentReminderOffset.HR16, AssignmentReminderOffset.MIN10),
        )
        val transport = RecordingTransport(
            existing = SettingsDocumentEnvelope(document = json("""{"assignments": {"reminder_offsets_hours": [24, 2]}}"""), revision = 1L)
        )

        val resolved = pullAssignmentSettings(current, transport, cloudSyncEnabled = true, syncAssignmentReminders = true, isLoggedIn = true)

        assertEquals(
            setOf(AssignmentReminderOffset.HR24, AssignmentReminderOffset.HR2, AssignmentReminderOffset.MIN10),
            resolved?.offsets,
        )
    }

    @Test
    fun `pull treats an empty minutes list as every offset turned off`() = runBlocking {
        val current = AssignmentSyncValues(
            enabled = true,
            offsets = setOf(AssignmentReminderOffset.HR48, AssignmentReminderOffset.MIN30),
        )
        val transport = RecordingTransport(
            existing = SettingsDocumentEnvelope(document = json("""{"assignments": {"reminder_offsets_minutes": []}}"""), revision = 1L)
        )

        val resolved = pullAssignmentSettings(current, transport, cloudSyncEnabled = true, syncAssignmentReminders = true, isLoggedIn = true)

        assertEquals(emptySet<AssignmentReminderOffset>(), resolved?.offsets)
    }

    @Test
    fun `pull resolves nothing, and asks for the pending push, while a local edit must win`() = runBlocking {
        val current = nonDefaultAssignments()
        val transport = RecordingTransport(
            existing = SettingsDocumentEnvelope(document = json("""{"assignments": {"enabled": true}}"""), revision = 1L)
        )
        var onLocalDirtyCalled = false

        val resolved = pullAssignmentSettings(
            current, transport, cloudSyncEnabled = true, syncAssignmentReminders = true, isLoggedIn = true,
            isLocalDirty = { true }, onLocalDirty = { onLocalDirtyCalled = true },
        )

        assertTrue("the caller must be told to re-push rather than silently losing the edit", onLocalDirtyCalled)
        assertNull(
            "nothing may be applied over an edit that must win, not even the values from before it",
            resolved,
        )
    }

    @Test
    fun `pull attempts nothing when the assignment reminders switch is off`() = runBlocking {
        val transport = RecordingTransport(existing = SettingsDocumentEnvelope(document = json(serverDocumentJson), revision = 7L))

        val resolved = pullAssignmentSettings(
            current = AssignmentSyncValues(enabled = true, offsets = AssignmentReminderOffset.DEFAULTS),
            transport = transport, cloudSyncEnabled = true, syncAssignmentReminders = false, isLoggedIn = true,
        )

        assertNull("a closed gate must not report as an attempted pull", resolved)
        assertEquals("must not even read the document", 0, transport.readCount)
    }

    @Test
    fun `pullAssignmentSettingsCatching survives a transport failure and leaves local values untouched`() = runBlocking {
        val current = nonDefaultAssignments()
        var reportedFailure: Throwable? = null

        val resolved = pullAssignmentSettingsCatching(
            current = current,
            transport = throwingTransport(SettingsDocumentApiException("read notification failed: HTTP 000 offline")),
            cloudSyncEnabled = true,
            syncAssignmentReminders = true,
            isLoggedIn = true,
            onFailure = { e -> reportedFailure = e },
        )

        assertNull("a caught transport failure must not report a resolved value", resolved)
        assertTrue(
            "the caller must be told what failed, not have it silently swallowed",
            reportedFailure is SettingsDocumentApiException,
        )
    }

    @Test
    fun `a document written by the assignments push path round-trips through the pull path unchanged`() = runBlocking {
        val pushTransport = RecordingTransport(existing = null)
        pushAssignmentSettings(
            local = localAssignments,
            transport = pushTransport,
            cloudSyncEnabled = true,
            syncAssignmentReminders = true,
            isLoggedIn = true,
        )
        val written = pushTransport.writtenDocuments.single()

        val pullTransport = RecordingTransport(existing = SettingsDocumentEnvelope(document = written, revision = 1L))
        val resolved = pullAssignmentSettings(
            // Deliberately opposite of localAssignments, so this proves the
            // round trip actually moved data.
            current = AssignmentSyncValues(enabled = !localAssignments.enabled, offsets = setOf(AssignmentReminderOffset.HR4)),
            transport = pullTransport,
            cloudSyncEnabled = true,
            syncAssignmentReminders = true,
            isLoggedIn = true,
        )

        assertEquals(localAssignments, resolved)
    }
}

/**
 * Minimal in-memory [SharedPreferences] double, trimmed to exactly what
 * [LiveActivityPreferences] uses (getLong/getBoolean, edit().putLong/
 * putBoolean().apply()). File-private and independent from the identically
 * shaped fake in `LiveActivityPreferencesTest` on purpose -- that one is
 * `private` to its own file (Kotlin top-level `private` is file-scoped), and
 * this suite constructs its starting state through [LiveActivityPreferences]'s
 * own setters rather than seeding raw keys, so it never needs that fake's
 * `initialLongs`/`initialBooleans`/`editCallCount` surface.
 */
private class FakeLiveActivitySharedPreferences : SharedPreferences {
    private val longs = mutableMapOf<String, Long>()
    private val booleans = mutableMapOf<String, Boolean>()

    override fun getAll(): MutableMap<String, *> = (longs + booleans).toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = defValue
    override fun getLong(key: String?, defValue: Long): Long = longs[key] ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = booleans[key] ?: defValue
    override fun contains(key: String?): Boolean = longs.containsKey(key) || booleans.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    private inner class FakeEditor : SharedPreferences.Editor {
        private val putLongs = mutableMapOf<String, Long>()
        private val putBooleans = mutableMapOf<String, Boolean>()
        private val removedKeys = mutableSetOf<String>()
        private var doClear = false

        override fun putString(key: String?, value: String?) = this
        override fun putStringSet(key: String?, values: MutableSet<String>?) = this
        override fun putInt(key: String?, value: Int) = this

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
            if (key != null) {
                putLongs[key] = value
                removedKeys.remove(key)
            }
        }

        override fun putFloat(key: String?, value: Float) = this

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
            if (key != null) {
                putBooleans[key] = value
                removedKeys.remove(key)
            }
        }

        override fun remove(key: String?): SharedPreferences.Editor = apply {
            if (key != null) {
                removedKeys.add(key)
                putLongs.remove(key)
                putBooleans.remove(key)
            }
        }

        override fun clear(): SharedPreferences.Editor = apply { doClear = true }

        override fun commit(): Boolean {
            applyChanges()
            return true
        }

        override fun apply() = applyChanges()

        private fun applyChanges() {
            if (doClear) {
                longs.clear()
                booleans.clear()
            }
            removedKeys.forEach {
                longs.remove(it)
                booleans.remove(it)
            }
            longs.putAll(putLongs)
            booleans.putAll(putBooleans)
        }
    }
}
