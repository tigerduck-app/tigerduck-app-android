package org.ntust.app.tigerduck.push

import android.content.SharedPreferences
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.liveactivity.LiveActivityPreferences
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
    // own setters do. Every "keep local" case below starts from a value that
    // is deliberately neither the property's default nor whatever a plausible
    // bug would coerce the bad input to, so a reset-to-default or a silent
    // coercion would each be caught rather than passing by coincidence.

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
        prefs.showInClass = false
        prefs.showClassPreparing = false
        prefs.showAssignment = false
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
        assertFalse(prefs.showInClass)
        assertFalse(prefs.showClassPreparing)
        assertFalse(prefs.showAssignment)
        assertEquals(1_800L, prefs.classPreparingLeadTimeSec)
        assertEquals(7_200L, prefs.assignmentLeadTimeSec)
    }

    @Test
    fun `pull leaves every local value untouched when live_activity is JSON null`() = runBlocking {
        val prefs = freshPreferences()
        prefs.showInClass = false
        prefs.showClassPreparing = false
        prefs.showAssignment = false
        prefs.classPreparingLeadTimeSec = 1_800
        prefs.assignmentLeadTimeSec = 7_200
        val transport = RecordingTransport(
            existing = SettingsDocumentEnvelope(document = json("""{"live_activity": null}"""), revision = 1L)
        )

        val result = pullLiveActivitySettings(prefs, transport, cloudSyncEnabled = true, syncLiveActivity = true, isLoggedIn = true)

        assertTrue("a null section is not a failure", result)
        assertFalse(prefs.showInClass)
        assertFalse(prefs.showClassPreparing)
        assertFalse(prefs.showAssignment)
        assertEquals(1_800L, prefs.classPreparingLeadTimeSec)
        assertEquals(7_200L, prefs.assignmentLeadTimeSec)
    }

    @Test
    fun `pull leaves just the missing fields untouched when live_activity has only some of them`() = runBlocking {
        val prefs = freshPreferences()
        prefs.showInClass = true
        prefs.showClassPreparing = false
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
        assertFalse("a missing field keeps the local value", prefs.showClassPreparing)
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
     * The trap this task exists to guard against: [SettingsDocumentTransport.read]
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

    // ── 7. Reconcile gates: signing in / the sync switch turning on ────────
    //
    // These pin the gate behavior at the level this module can unit test --
    // see pushLiveActivitySettings's KDoc. The production trigger itself
    // (AuthService calling NotificationSettingsSync.enqueueLiveActivityPush()
    // on sign-in; AppPreferences.syncLiveActivityChanged doing the same when
    // the switch turns on) is glue that needs a real Context/Keystore to
    // construct and is verified by reading the diff, the same way
    // PushRegistrationService.onSignedIn()'s call sites are today.

    @Test
    fun `signing in lets a previously blocked push through`() = runBlocking {
        val transport = RecordingTransport(existing = null)

        val blockedWhileLoggedOut = pushLiveActivitySettings(
            local = local,
            transport = transport,
            cloudSyncEnabled = true,
            syncLiveActivity = true,
            isLoggedIn = false,
        )
        assertFalse("not signed in yet, so this must not report success", blockedWhileLoggedOut)
        assertEquals("must not even read the document while signed out", 0, transport.readCount)

        val pushedAfterSignIn = pushLiveActivitySettings(
            local = local,
            transport = transport,
            cloudSyncEnabled = true,
            syncLiveActivity = true,
            isLoggedIn = true,
        )

        assertTrue("the exact same call now succeeds once signed in", pushedAfterSignIn)
        assertEquals(1, transport.writtenDocuments.size)
    }

    @Test
    fun `turning the live-activity sync switch on lets a previously blocked push through`() = runBlocking {
        val transport = RecordingTransport(existing = null)

        val blockedWhileOff = pushLiveActivitySettings(
            local = local,
            transport = transport,
            cloudSyncEnabled = true,
            syncLiveActivity = false,
            isLoggedIn = true,
        )
        assertFalse("the switch is off, so this must not report success", blockedWhileOff)
        assertEquals("must not even read the document while the switch is off", 0, transport.readCount)

        val pushedAfterSwitchOn = pushLiveActivitySettings(
            local = local,
            transport = transport,
            cloudSyncEnabled = true,
            syncLiveActivity = true,
            isLoggedIn = true,
        )

        assertTrue("the exact same call now succeeds once the switch is on", pushedAfterSwitchOn)
        assertEquals(1, transport.writtenDocuments.size)
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
