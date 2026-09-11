// Writes this device's Live Update preferences into the `notification`
// settings-document namespace (`GET/PUT /v3/settings/notification`, design
// spec §4.6). Android owns exactly one key in that document —
// `live_activity`. Everything else belongs to somebody else: `assignments`
// and `courses` to iOS/the backend, plus whatever sections a newer build of
// either platform adds to the same namespace.
//
// Writes therefore **merge at the JSON level** rather than re-encoding a
// typed struct: read whatever the server currently holds as a `JsonObject`,
// splice the one key this app owns over it, and PUT the result. Every other
// key travels back exactly as it arrived — top level *and* nested inside a
// section — including keys this build has never heard of. Mirrors iOS's
// `NotificationSettingsSync.merging(_:into:)`
// (`AppState+NotificationSettings.swift`).
//
// This is not a stylistic preference; a typed re-encode here is a live
// data-loss bug in both directions:
//
//   * iOS added `assignments.reminder_offsets_minutes` (the lossless mirror
//     of `reminder_offsets_hours`, sub-hour offsets included) and
//     [AssignmentsSection] does not model it. Re-encoding the typed document
//     would delete it on every write from this device, so a user's 30-minute
//     assignment reminder would silently stop syncing between their own
//     devices. Android schedules its reminders locally, so nothing surfaces
//     until a reminder one day simply fails to arrive.
//   * `reminder_offsets_hours` is `list[float]` on the backend — `0.5`
//     schedules fine — while [AssignmentsSection] declares it `List<Int>?`.
//     A fractional value there fails the *whole* typed document decode.
//     Merging as JSON never decodes it, so the value round-trips untouched.
//
// Nothing here needs an R8 keep rule of its own: the only reflective Gson
// call is [LiveActivitySyncValues.documentUpdates], which encodes
// [NotificationSettingsDocument] — a class whose every field carries
// `@SerializedName` (pinned by `NotificationSettingsDocumentTest`). The
// merge itself is `JsonObject` key manipulation, which obfuscation cannot
// rename.

package org.ntust.app.tigerduck.push

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.liveactivity.LiveActivityPreferences
import org.ntust.app.tigerduck.liveactivity.LiveActivitySyncValues
import javax.inject.Inject
import javax.inject.Singleton

/** The settings-document namespace this file reads and writes. */
internal const val NOTIFICATION_SETTINGS_NAMESPACE = "notification"

private val syncGson = Gson()

/**
 * The two calls [pushLiveActivitySettings] makes, as an interface rather
 * than a [SettingsDocumentApiClient] reference, so the push logic is
 * testable on the plain JVM these unit tests run on.
 *
 * [SettingsDocumentApiClient] can't be constructed in such a test — it needs
 * `AppPreferences` (a real `Context.getSharedPreferences`) and
 * `AuthTokenManager` (Android Keystore via `EncryptedSharedPreferences`) —
 * and neither it nor those are `open`, with no mocking library or
 * Robolectric in this module. Same reasoning, and the same shape, as
 * `parseEnvelope` taking its deserialize step as a parameter, and as
 * `applyOptOutIfAccepted` being lifted out of `PushRegistrationService`.
 */
internal interface SettingsDocumentTransport {
    /** `GET /v3/settings/notification`; null when the user has no document yet (404). */
    suspend fun read(): SettingsDocumentEnvelope<JsonObject>?

    /** `PUT /v3/settings/notification` with [document], compare-and-swapping on [baseRevision]. */
    suspend fun write(document: JsonObject, baseRevision: Long?): SettingsWriteResult<JsonObject>
}

/**
 * Splices [updates] over [into], key by key, and returns the result. Any key
 * [updates] does not mention survives untouched — at the top level (a whole
 * section iOS or a newer build added) and, because this recurses into nested
 * objects, *inside* the section this app does own (a field added to
 * `live_activity` by a build that knows about it).
 *
 * Recursion is the point. Preserving only top-level keys is not enough:
 * unknown keys turn up inside sections, not just beside them — iOS tried the
 * top-level-only version and rejected it.
 *
 * Arrays are replaced wholesale, never unioned: every array in this document
 * is a complete set of user choices, and merging them would make removing an
 * entry impossible to express.
 */
internal fun merging(updates: JsonObject, into: JsonObject): JsonObject {
    val merged = JsonObject()
    // Copy first, so keys the server already had keep their original
    // position and a key only [updates] carries is appended at the end.
    for ((key, value) in into.entrySet()) merged.add(key, value)
    for ((key, value) in updates.entrySet()) {
        val current = merged.get(key)
        val next = if (value.isJsonObject && current != null && current.isJsonObject) {
            merging(value.asJsonObject, current.asJsonObject)
        } else {
            value
        }
        merged.add(key, next)
    }
    return merged
}

/**
 * The keys this client owns, as a JSON object ready to splice over whatever
 * the server currently holds — i.e. `{"live_activity": {...}}` and nothing
 * else.
 *
 * Encoded from [NotificationSettingsDocument] rather than assembled from
 * string literals so the wire keys come from one place, the model's
 * `@SerializedName`s. `assignments` and `courses` are left null and Gson
 * omits nulls, which is what keeps this object to the single key Android
 * owns — the sections it doesn't own never appear in `updates`, so the merge
 * cannot touch them.
 *
 * Field-by-field mapping, fixed by the task-5 brief's table (do not add or
 * infer fields beyond these five):
 *
 * | local ([LiveActivitySyncValues]) | document field                         |
 * |----------------------------------|----------------------------------------|
 * | `showInClass`                    | `live_activity.show_in_class`            |
 * | `showClassPreparing`             | `live_activity.show_class_preparing`     |
 * | `showAssignment`                 | `live_activity.show_assignment`          |
 * | `classPreparingLeadSeconds`      | `live_activity.class_preparing_lead_seconds` |
 * | `assignmentLeadSeconds`          | `live_activity.assignment_lead_seconds`  |
 */
internal fun LiveActivitySyncValues.documentUpdates(): JsonObject =
    syncGson.toJsonTree(
        NotificationSettingsDocument(
            liveActivity = LiveActivitySection(
                showClassPreparing = showClassPreparing,
                showInClass = showInClass,
                showAssignment = showAssignment,
                classPreparingLeadSeconds = classPreparingLeadSeconds,
                assignmentLeadSeconds = assignmentLeadSeconds,
            )
        )
    ).asJsonObject

/**
 * Read-modify-write cycle for the document's `live_activity` section.
 * Everything else in the document travels back exactly as read (see
 * [merging]). On a 409 conflict, adopts the server's document and its
 * revision and retries **exactly once** — never loops.
 *
 * Returns true only when a write actually landed; false when the gates below
 * are closed and nothing was attempted, so a caller can never read "didn't
 * run" as "succeeded".
 *
 * Both gates come from spec §6: cloud sync is the master switch, and
 * "同步內容 → 即時更新" (`AppPreferences.syncLiveActivity`) decides whether
 * *this* device's Live Update settings are among the things it syncs. iOS
 * gates its own write on the same pair. Closed means no request at all, not
 * a request whose result is discarded.
 *
 * A second conflict throws rather than returning false: it is a genuine
 * failure to write (two other devices wrote in the time this one took to
 * rebase once), not a "sync is off" no-op, and the two must not look alike
 * to the caller.
 */
internal suspend fun pushLiveActivitySettings(
    local: LiveActivitySyncValues,
    transport: SettingsDocumentTransport,
    cloudSyncEnabled: Boolean,
    syncLiveActivity: Boolean,
): Boolean {
    if (!cloudSyncEnabled || !syncLiveActivity) return false

    val current = transport.read()
    // No document yet (404) is the normal state for every user who has never
    // written this namespace: merge over an empty object and PUT with a null
    // base revision, which is the API's "create" case.
    var existing = current?.document ?: JsonObject()
    var baseRevision = current?.revision

    val updates = local.documentUpdates()
    var conflicts = 0
    while (true) {
        val result = transport.write(merging(updates, into = existing), baseRevision)
        when (result) {
            is SettingsWriteResult.Written -> return true
            is SettingsWriteResult.Conflict -> {
                conflicts++
                if (conflicts > 1) {
                    throw SettingsDocumentApiException(
                        "write $NOTIFICATION_SETTINGS_NAMESPACE: still conflicting after one rebase"
                    )
                }
                // Rebase onto the winning state the 409 body carries and try
                // again against its revision, so the losing edit is merged in
                // rather than dropped.
                existing = result.server.document
                baseRevision = result.server.revision
            }
        }
    }
}

/**
 * Application-scoped owner of the `live_activity` push: turns "a Live Update
 * preference changed" into at most one coalesced
 * [pushLiveActivitySettings] call.
 *
 * Runs on the application scope, not the caller's `viewModelScope`, on
 * purpose. A `viewModelScope.launch` here would be cancelled by navigating
 * back off the settings screen — which is exactly what a user does right
 * after flipping a toggle — and the write would be silently lost. (That
 * hazard is live in `SettingsViewModel.setServerPushOn` today; it is tracked
 * separately and deliberately not copied here.)
 *
 * Nothing is persisted locally by this class, so there is no fail-closed
 * question to answer: [LiveActivityPreferences] is the source of truth and
 * has already committed the user's choice by the time a push is enqueued.
 * The document is a copy of it, not the record of it — unlike
 * `applyOptOutIfAccepted`, where the local pref *is* the thing the server
 * has to accept first.
 */
@Singleton
class NotificationSettingsSync @Inject constructor(
    private val client: SettingsDocumentApiClient,
    private val liveActivityPreferences: LiveActivityPreferences,
    private val appPreferences: AppPreferences,
    private val authTokenManager: AuthTokenManager,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    // CONFLATED: a slider's onValueChange fires on every pointer move, so
    // dozens of enqueue calls collapse into one pending push, and the one
    // that runs always reads the newest preference values.
    private val pending = Channel<Unit>(Channel.CONFLATED)

    private val transport = object : SettingsDocumentTransport {
        override suspend fun read(): SettingsDocumentEnvelope<JsonObject>? =
            client.read(NOTIFICATION_SETTINGS_NAMESPACE, JsonObject::class.java)

        override suspend fun write(
            document: JsonObject,
            baseRevision: Long?,
        ): SettingsWriteResult<JsonObject> =
            client.write(NOTIFICATION_SETTINGS_NAMESPACE, document, baseRevision, JsonObject::class.java)
    }

    init {
        scope.launch {
            for (unused in pending) {
                // Settle before reading the preferences: a drag that is still
                // in progress should produce one PUT carrying its final value,
                // not one per step. Same 250 ms coalescing window
                // PushRegistrationService.scheduleRegister uses.
                delay(DEBOUNCE_MS)
                runCatching { pushNow() }.onFailure { e ->
                    if (e is CancellationException) throw e
                    // Best-effort: the local preference is already saved, and
                    // the next preference change re-runs this.
                    Log.w(TAG, "live_activity settings push failed", e)
                }
            }
        }
    }

    /**
     * Ask for a push of the current Live Update preferences. Returns
     * immediately; the write happens on the application scope.
     */
    fun enqueueLiveActivityPush() {
        pending.trySend(Unit)
    }

    private suspend fun pushNow() {
        // A settings-document PUT needs a session. Firing one without a token
        // and reading the 401 as the answer is what PushApiClient.hasAuthSession
        // documents you must not do: a 401 is also what a revoked or expired
        // session looks like, and those deserve different handling.
        if (!authTokenManager.isLoggedIn) return
        pushLiveActivitySettings(
            local = liveActivityPreferences.syncSnapshot(),
            transport = transport,
            cloudSyncEnabled = appPreferences.cloudSyncEnabled,
            syncLiveActivity = appPreferences.syncLiveActivity,
        )
    }

    private companion object {
        const val TAG = "Push.NotifSettings"
        const val DEBOUNCE_MS = 250L
    }
}
