// Writes this device's Live Update preferences AND its assignment-reminder
// settings into the `notification` settings-document namespace (`GET/PUT
// /v3/settings/notification`, design spec §4.6), and reads them back.
// Android owns two keys in that document — `live_activity` and
// `assignments` — each gated on its own "同步內容" switch
// (`syncLiveActivity` / `syncAssignmentReminders`) plus `cloudSyncEnabled`.
// `courses` belongs to somebody else — the course-reminder feature — and so
// does whatever section a newer build of either platform adds to the same
// namespace; this file only ever round-trips those unchanged.
//
// The read half ([pullLiveActivitySettings], [pullAssignmentSettings]) is
// what makes this sync rather than one-way replication: without it, an
// iOS user's edits are invisible on Android. Every field degrades to "keep
// the local value" when the document can't express it — missing, null, or
// the wrong JSON type — never to `false`/`0`/an empty set. The live-activity
// lead times are additionally clamped into this build's local range. That
// range equals iOS's slider ranges (1 h–8 h, 5 min–4 h), but nothing holds
// the document to it: the backend does not validate these fields, and iOS
// applies no floor to its assignment lead time when it loads or pulls one.
// So the document can hold a value this build's own slider could never
// produce.
//
// Writes therefore **merge at the JSON level** rather than re-encoding a
// typed struct: read whatever the server currently holds as a `JsonObject`,
// splice the keys this app owns over it, and PUT the result. Every other
// key travels back exactly as it arrived — top level *and* nested inside a
// section — including keys this build has never heard of. Mirrors iOS's
// `NotificationSettingsSync.merging(_:into:)`
// (`AppState+NotificationSettings.swift`).
//
// This is not a stylistic preference; a typed re-encode here is a live
// data-loss bug:
//
//   * `reminder_offsets_hours` is `list[float]` on the backend — `0.5`
//     schedules fine — while [AssignmentsSection] declares it `List<Int>?`.
//     A fractional value there fails the *whole* typed document decode.
//     Merging as JSON never decodes it, so the value round-trips untouched.
//   * [AssignmentsSection] models a fixed `List<Int>?` for
//     `reminder_offsets_minutes`, but the *values* inside it are arbitrary
//     integers, not just the ones [org.ntust.app.tigerduck.notification.AssignmentReminderOffset]
//     has cases for (a future build's extra offset, or malformed data).
//     [AssignmentSyncValues.documentUpdates] folds any such value already in
//     the document back into what it writes, rather than silently deleting
//     it because this build's enum cannot represent it — see that function's
//     KDoc.
//
// Nothing here needs an R8 keep rule of its own: the only reflective Gson
// calls are [LiveActivitySyncValues.documentUpdates] and
// [AssignmentSyncValues.documentUpdates], which encode
// [NotificationSettingsDocument] — a class whose every field carries
// `@SerializedName` (pinned by `NotificationSettingsDocumentTest`). The
// merge itself is `JsonObject` key manipulation, which obfuscation cannot
// rename.

package org.ntust.app.tigerduck.push

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.liveactivity.LiveActivityPreferences
import org.ntust.app.tigerduck.liveactivity.LiveActivitySyncUpdate
import org.ntust.app.tigerduck.liveactivity.LiveActivitySyncValues
import org.ntust.app.tigerduck.notification.AssignmentNotificationScheduler
import org.ntust.app.tigerduck.notification.AssignmentReminderOffset
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.floor

/** The settings-document namespace this file reads and writes. */
internal const val NOTIFICATION_SETTINGS_NAMESPACE = "notification"

private val syncGson = Gson()

/**
 * Exactly the assignment-reminder values the `notification` settings
 * document's `assignments` section carries — the local half of the
 * cross-device mapping, mirroring [LiveActivitySyncValues] and iOS's
 * `NotificationSettingsSync.LocalPreferences` (`isAssignmentReminderEnabled`
 * / `assignmentReminderOffsets`).
 *
 * Not persisted and never Gson-*de*serialized — built from
 * [org.ntust.app.tigerduck.data.preferences.AppPreferences] and immediately
 * encoded onto the wire — so CLAUDE.md's upgrade-safe-persistence rule does
 * not apply here, the same reasoning [LiveActivitySyncValues]'s KDoc gives.
 */
internal data class AssignmentSyncValues(
    val enabled: Boolean,
    val offsets: Set<AssignmentReminderOffset>,
)

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
 * The `live_activity` keys this client owns, as a JSON object ready to
 * splice over whatever the server currently holds — i.e.
 * `{"live_activity": {...}}` and nothing else.
 *
 * Encoded from [NotificationSettingsDocument] rather than assembled from
 * string literals so the wire keys come from one place, the model's
 * `@SerializedName`s. `assignments` and `courses` are left null and Gson
 * omits nulls, which is what keeps this object to `live_activity` alone:
 * `assignments`, which Android also owns, goes out in its own push
 * ([AssignmentSyncValues.documentUpdates]), and `courses` belongs to
 * somebody else, so neither appears in these `updates` and this merge
 * cannot touch them.
 *
 * Field-by-field mapping — the fixed set of five (do not add or infer
 * fields beyond these):
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
 * The `live_activity` section's wire keys, restated as literals rather
 * than derived from [LiveActivitySection]/[NotificationSettingsDocument]'s
 * `@SerializedName` values at runtime.
 *
 * A by-name reflective lookup (`Class.getDeclaredField`) was tried here and
 * reverted: `Type::property.name` resolves to the property's
 * *pre-obfuscation* Kotlin source name (`kotlin-reflect` is not on this
 * project's classpath, so it comes from the lightweight `PropertyReference`
 * machinery, which embeds that name as a compile-time constant, not a
 * runtime-derived one), and `app/proguard-rules.pro`'s
 * `-keepclassmembers,allowobfuscation` rule for `@SerializedName` fields
 * protects them from *removal*, not from *renaming*. `push` has no
 * package-specific keep rule, so a real release build is free to rename
 * every one of these fields, `getDeclaredField("showInClass")` would throw
 * `NoSuchFieldException` the first time this file is touched, and — since
 * these would be eagerly-initialized top-level properties — every push and
 * pull would fail for the rest of the process, permanently, from the very
 * first attempt. That is strictly worse than the divergence risk it was
 * meant to close, and no test here can see it: this suite runs on
 * unobfuscated bytecode.
 *
 * These are plain `JsonObject.get(key)` string lookups on an already-parsed
 * tree, not reflection of any kind — a `String` literal cannot be renamed
 * by R8, obfuscated or not, the same reasoning [merging] and this file's
 * header already give for why the JSON-level merge itself is
 * obfuscation-proof. What protects these six literals from drifting away
 * from [documentUpdates]'s `@SerializedName`s is
 * `a document written by the push path round-trips through the pull path
 * unchanged` in `NotificationSettingsSyncTest` — it pushes real values
 * through the real write path and feeds the exact result back through
 * these exact literals, so a hand-typo'd key here fails that test rather
 * than shipping silently.
 */
private const val LIVE_ACTIVITY_SECTION_KEY = "live_activity"
private const val SHOW_IN_CLASS_KEY = "show_in_class"
private const val SHOW_CLASS_PREPARING_KEY = "show_class_preparing"
private const val SHOW_ASSIGNMENT_KEY = "show_assignment"
private const val CLASS_PREPARING_LEAD_SECONDS_KEY = "class_preparing_lead_seconds"
private const val ASSIGNMENT_LEAD_SECONDS_KEY = "assignment_lead_seconds"

/**
 * Read-modify-write cycle for the document's `live_activity` section.
 * Everything else in the document travels back exactly as read (see
 * [merging]). On a 409 conflict, adopts the server's document and its
 * revision and retries **exactly once** — never loops.
 *
 * Returns true only when a write actually landed; false when the gates below
 * are closed and nothing was attempted, or when [isCurrentGeneration] turns
 * false partway through and the push is abandoned, so a caller can never
 * read "didn't land" as "succeeded".
 *
 * [isCurrentGeneration] is re-checked before the read and before every
 * write — the read, the write, and the one retry write a 409 can cause —
 * not just once before this function is called. Each of those requests can
 * itself trigger a token refresh or a full relogin, and a stalled network
 * can stretch that to tens of seconds; if another account finishes signing
 * in inside that window, this push must not land in its document. Mirrors
 * iOS's reconcile, which checks `isCurrent()` after every round trip for
 * the same reason. `NotificationSettingsSync.pushNow` — the only
 * production caller — closes over the generation this push was queued
 * under. Defaults to "always current" so every other caller, including
 * this file's tests, is unaffected.
 *
 * All three gates come from spec §6: cloud sync is the master switch,
 * "同步內容 → 即時更新" (`AppPreferences.syncLiveActivity`) decides whether
 * *this* device's Live Update settings are among the things it syncs, and
 * [isLoggedIn] is the session a settings-document PUT needs to mean
 * anything (there is no per-user document without an account). Checking it
 * proactively, rather than firing the request and reading a 401 as "not
 * signed in", also avoids conflating that with a revoked or expired
 * session — also a 401, but a case that deserves different handling. iOS
 * gates its own write on the sync pair. Closed means no request at all,
 * not a request whose result is discarded.
 *
 * [isLoggedIn] is checked here with the other two so all three gates sit
 * under the same plain-JVM tests. The Live Activity settings screen works
 * without an account, so an edit made while signed out is refused by this
 * gate and stays unconfirmed
 * ([LiveActivityPreferences.hasUnconfirmedSyncEdit]); the next sign-in
 * delivers it through [NotificationSettingsSync.pushIfUnconfirmed].
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
    isLoggedIn: Boolean,
    isCurrentGeneration: () -> Boolean = { true },
): Boolean {
    if (!cloudSyncEnabled || !syncLiveActivity || !isLoggedIn) return false
    if (!isCurrentGeneration()) return false

    val current = transport.read()
    if (!isCurrentGeneration()) return false
    // No document yet (404) is the normal state for every user who has never
    // written this namespace: merge over an empty object and PUT with a null
    // base revision, which is the API's "create" case.
    var existing = current?.document ?: JsonObject()
    var baseRevision = current?.revision

    val updates = local.documentUpdates()
    var conflicts = 0
    while (true) {
        if (!isCurrentGeneration()) return false
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
 * [element]'s boolean value, or null if [element] is missing, JSON `null`,
 * or anything other than a genuine JSON boolean.
 *
 * A JSON string or number that merely *looks* boolean-ish (`"true"`, `1`)
 * is rejected rather than coerced: `JsonPrimitive.getAsBoolean()` on a
 * non-boolean primitive silently falls back to
 * `Boolean.parseBoolean(asString)`, which turns any string other than
 * exactly `"true"` into `false`. Requirement 3 (missing/null/wrong-type
 * must degrade to "keep the local value") must not be satisfied by
 * accident because that fallback happened to land on `false`.
 */
private fun JsonElement?.asValidatedBooleanOrNull(): Boolean? {
    if (this == null || !isJsonPrimitive) return null
    val primitive = asJsonPrimitive
    return if (primitive.isBoolean) primitive.asBoolean else null
}

/**
 * [element]'s integer value, or null if [element] is missing, JSON `null`,
 * not a genuine JSON number, a non-finite number, out of [Int] range, or —
 * the load-bearing case — a number with a fractional component.
 *
 * [SettingsDocumentTransport.read] hands back an already-parsed
 * [JsonObject], so every numeric field pulled from it goes through Gson's
 * *parsed-tree* int path (`JsonTreeReader.nextInt()` ->
 * `JsonPrimitive.getAsInt()` -> `Number.intValue()`), not the raw-text
 * `JsonReader.nextInt()` path that validates and throws
 * `NumberFormatException` on a fractional value. The parsed-tree path
 * truncates silently instead: `0.5` reads back as `0`, no exception, and
 * `0` is a value this code would otherwise happily treat as a real user
 * preference. Gson raises nothing to catch here, so this app has to reject
 * a fractional value itself before calling [com.google.gson.JsonPrimitive.getAsInt].
 */
private fun JsonElement?.asValidatedIntOrNull(): Int? {
    if (this == null || !isJsonPrimitive) return null
    val primitive = asJsonPrimitive
    if (!primitive.isNumber) return null
    val value = primitive.asDouble
    if (value.isNaN() || value.isInfinite()) return null
    if (value != floor(value)) return null
    if (value < Int.MIN_VALUE.toDouble() || value > Int.MAX_VALUE.toDouble()) return null
    return value.toInt()
}

/**
 * Read-and-apply half of the `live_activity` section — the mirror of
 * [pushLiveActivitySettings], and what makes this cross-platform *sync*
 * rather than Android-writes-only replication.
 *
 * Every one of the five fields is independently optional: a missing
 * `live_activity` section, a JSON `null` section, a missing field, or a
 * field of the wrong JSON type ([asValidatedBooleanOrNull],
 * [asValidatedIntOrNull]) all degrade to "keep [preferences]'s current
 * value" rather than to `false`/`0` — the same principle as iOS's
 * `resolveOffsets`: what the document cannot express, the local choice is
 * safer than a guess. The two lead-time fields are additionally clamped
 * into this build's local range by [LiveActivityPreferences.applySyncUpdate]
 * (via [LiveActivityPreferences.assignmentLeadTimeSec] /
 * [LiveActivityPreferences.classPreparingLeadTimeSec]'s own setters). The
 * range matches iOS's sliders, but the document is not held to it (see this
 * file's header), so a value this build's slider could never produce can
 * still arrive here.
 *
 * Same three gates as [pushLiveActivitySettings], for the same reasons:
 * pulling a document into local prefs when the user has not opted into
 * syncing it would be just as wrong as pushing one, and reading a
 * per-user document with no session identifies nobody. Returns true only
 * when a read was actually attempted — closed gates return false with no
 * request made, matching [pushLiveActivitySettings]'s contract; a missing
 * document or section is not a failure, so that case still returns true.
 *
 * [isLocalDirty] guards against the pull silently destroying an edit that
 * has not been confirmed to have reached the server: applying the
 * document unconditionally would mean "edit a value, the push fails, the
 * screen is reopened" ends with the pulled — stale — value overwriting the
 * user's own edit, with no error and no trace of it ever happening. Called
 * *after* the network read completes (not before), so an edit that lands
 * while the read is in flight is caught too, not just one that predates
 * it. When it reports `true`, [onLocalDirty] runs instead of applying
 * anything, so the caller (`NotificationSettingsSync.pullNow`) can ask for
 * a fresh push rather than silently dropping the edit. Both default to
 * "never dirty" so a caller that has no such concept — every existing
 * caller before this — is unaffected.
 *
 * [isCurrentGeneration] is the account guard [pushLiveActivitySettings]
 * already applies, on the read half: checked before the read and again once
 * it comes back, and `false` abandons the pull without applying anything.
 * The document was fetched with the bearer of whoever was signed in when the
 * request went out, and a logout in the meantime means it describes an
 * account that has left — [isLocalDirty] cannot see that, because a logout
 * clears the unconfirmed flags and leaves the local values exactly where the
 * departing account had them, so both halves of that guard read "clean".
 * Applying it anyway writes one user's settings into another's, on a shared
 * phone, one edit away from being pushed into the new account's document.
 * iOS makes the same `isCurrent()` check after every round trip.
 *
 * A thrown exception from [transport] (offline, a non-2xx/404 status, a
 * malformed body) is **not** caught here, matching [pushLiveActivitySettings]:
 * both are plain suspend functions with no opinion on how a caller wants a
 * transport failure handled. See [pullLiveActivitySettingsCatching] for the
 * wrapper that gives it one.
 */
internal suspend fun pullLiveActivitySettings(
    preferences: LiveActivityPreferences,
    transport: SettingsDocumentTransport,
    cloudSyncEnabled: Boolean,
    syncLiveActivity: Boolean,
    isLoggedIn: Boolean,
    isLocalDirty: () -> Boolean = { false },
    onLocalDirty: () -> Unit = {},
    isCurrentGeneration: () -> Boolean = { true },
): Boolean {
    if (!cloudSyncEnabled || !syncLiveActivity || !isLoggedIn) return false
    if (!isCurrentGeneration()) return false

    val section = transport.read()?.document
        ?.get(LIVE_ACTIVITY_SECTION_KEY)
        ?.takeIf { it.isJsonObject }
        ?.asJsonObject

    if (!isCurrentGeneration()) return false
    if (section == null) return true

    if (isLocalDirty()) {
        onLocalDirty()
        return true
    }

    preferences.applySyncUpdate(
        LiveActivitySyncUpdate(
            showInClass = section.get(SHOW_IN_CLASS_KEY).asValidatedBooleanOrNull(),
            showClassPreparing = section.get(SHOW_CLASS_PREPARING_KEY).asValidatedBooleanOrNull(),
            showAssignment = section.get(SHOW_ASSIGNMENT_KEY).asValidatedBooleanOrNull(),
            classPreparingLeadSeconds = section.get(CLASS_PREPARING_LEAD_SECONDS_KEY).asValidatedIntOrNull(),
            assignmentLeadSeconds = section.get(ASSIGNMENT_LEAD_SECONDS_KEY).asValidatedIntOrNull(),
        )
    )
    return true
}

/**
 * [pullLiveActivitySettings], with a transport failure — offline, a
 * non-2xx/404 status, a malformed body — caught and reported through
 * [onFailure] instead of left to propagate, and treated as `false` exactly
 * like a closed gate. A missing document/section or an individually
 * malformed field is not a failure by this definition —
 * [pullLiveActivitySettings] already degrades those to "keep local" on its
 * own; this closes the other half, the transport itself failing to answer
 * at all, which a malformed-document test does not exercise and which
 * nothing previously guarded against.
 *
 * Opening the Live Activity settings screen with no connectivity used to
 * crash the app outright: `SettingsDocumentApiClient.read()` throws on an
 * `IOException` and on any non-2xx, non-404 status, `viewModelScope` has no
 * `CoroutineExceptionHandler`, and an uncaught throw there reaches the
 * default handler and kills the process — the same shape as the crash
 * `di/CoroutineModule.kt` documents for `@ApplicationScope`. A failed pull
 * has to degrade the same way a malformed document already does.
 *
 * [CancellationException] is rethrown untouched, matching structured-
 * concurrency expectations — a coroutine cancelled by navigating away from
 * the settings screen must keep unwinding as a cancellation, not get
 * reported through [onFailure] as an ordinary pull failure.
 *
 * Extracted, like `applyOptOutIfAccepted`, so "a transport failure here
 * must never escape" is unit-testable outside
 * [NotificationSettingsSync.pullNow] — the real caller,
 * `LiveActivitySettingsViewModel`, needs a real Context/Keystore-backed
 * Hilt graph and cannot be constructed in this module's plain-JVM tests.
 */
internal suspend fun pullLiveActivitySettingsCatching(
    preferences: LiveActivityPreferences,
    transport: SettingsDocumentTransport,
    cloudSyncEnabled: Boolean,
    syncLiveActivity: Boolean,
    isLoggedIn: Boolean,
    isLocalDirty: () -> Boolean = { false },
    onLocalDirty: () -> Unit = {},
    isCurrentGeneration: () -> Boolean = { true },
    onFailure: (Throwable) -> Unit = {},
): Boolean = try {
    pullLiveActivitySettings(
        preferences,
        transport,
        cloudSyncEnabled,
        syncLiveActivity,
        isLoggedIn,
        isLocalDirty,
        onLocalDirty,
        isCurrentGeneration,
    )
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    onFailure(e)
    false
}

// ─────────────────────────── Assignments section ───────────────────────────
//
// Same push/pull shape as live_activity above, gated on its own switch
// (`syncAssignmentReminders`) instead of `syncLiveActivity`, sharing
// [merging] and [SettingsDocumentTransport]. Kept as independent functions
// (and an independent read-modify-write cycle) rather than folded into the
// live_activity ones so neither section's push can fail because of the
// other's — [NotificationSettingsSync.pushNow] runs both, in sequence, under
// one queued trigger, which is the "reuse the queue, not the request" this
// was asked to do.

private const val ASSIGNMENTS_SECTION_KEY = "assignments"
private const val ASSIGNMENTS_ENABLED_KEY = "enabled"
private const val REMINDER_OFFSETS_HOURS_KEY = "reminder_offsets_hours"
private const val REMINDER_OFFSETS_MINUTES_KEY = "reminder_offsets_minutes"

/**
 * [element]'s elements as a validated `List<Int>`, or `null` if [element] is
 * missing, JSON `null`, or anything other than a genuine JSON array.
 *
 * Deliberately per-element tolerant rather than all-or-nothing: an element
 * that is not a valid whole number ([asValidatedIntOrNull]) is dropped, not
 * treated as invalidating the whole array — matching the backend's own
 * `reminders.py:_numbers`, which filters non-numeric entries out of an
 * otherwise-valid list rather than discarding the list. This is what makes
 * the *presence* of the array (a JSON list, however messy its contents)
 * the authority for precedence, independent of whether every element in it
 * happens to be clean — the same "is a list" test §4.6 and the backend use
 * for `reminder_offsets_minutes` vs. `reminder_offsets_hours`.
 */
private fun JsonElement?.asValidatedIntListOrNull(): List<Int>? {
    if (this == null || !isJsonArray) return null
    return asJsonArray.mapNotNull { it.asValidatedIntOrNull() }
}

/**
 * Document offsets → the local `Set<AssignmentReminderOffset>` to store.
 * Mirrors iOS's `NotificationSettingsSync.resolveOffsets`
 * (`Services/Sync/NotificationSettingsDocument.swift`) field for field:
 *
 * - [documentMinutes] present (a list, including empty): authoritative and
 *   complete, sub-hour offsets included. An entry matching no case in this
 *   build (a future client's extra offset, or malformed data) is dropped
 *   rather than failing the pull — see [AssignmentReminderOffset.fromMinutes].
 *   An empty list is a real answer: the user turned every offset off.
 * - only [documentHours] present: a reader older than `reminder_offsets_minutes`
 *   wrote this document, or wrote it before this offset existed. The
 *   whole-hour offsets come from the document; this build's own sub-hour
 *   selections are **kept**, because a field that cannot structurally carry
 *   them is not evidence the user turned them off. Without this, a
 *   30-minute reminder would be silently deleted the first time any client
 *   that only understands hours touched the document.
 * - neither: the document says nothing about offsets, so [currentLocal] is
 *   returned unchanged.
 */
internal fun resolveOffsets(
    documentMinutes: List<Int>?,
    documentHours: List<Int>?,
    currentLocal: Set<AssignmentReminderOffset>,
): Set<AssignmentReminderOffset> {
    if (documentMinutes != null) {
        return documentMinutes.mapNotNull(AssignmentReminderOffset::fromMinutes).toSet()
    }
    if (documentHours == null) return currentLocal
    val fromDocument = documentHours.mapNotNull { hours ->
        // hours comes straight off a document the backend does not validate
        // (`SettingsPut.document: dict`); `hours * 60` overflowing Int would
        // otherwise be an arithmetic crash rather than a values-that-don't-
        // match degradation.
        val minutes = hours.toLong() * 60
        if (minutes < Int.MIN_VALUE || minutes > Int.MAX_VALUE) null
        else AssignmentReminderOffset.fromMinutes(minutes.toInt())
    }.toSet()
    val localSubHour = currentLocal.filter { it.reminderOffsetMinutes < 60 }
    return fromDocument + localSubHour
}

/**
 * The `assignments` section update: `enabled` plus both offset lists, ready
 * to splice over whatever [existing] currently holds via [merging].
 *
 * [reminderOffsetsMinutes] is written as the complete set this device
 * selected **plus** any minute value already in [existing]'s
 * `reminder_offsets_minutes` that no local [AssignmentReminderOffset] can
 * represent. Without this, a value only a different (future, or foreign)
 * client's enum understands would be permanently deleted the moment this
 * device edits *any* offset and pushes — `reminder_offsets_minutes` is a
 * key this build explicitly writes, so unlike a section it doesn't own,
 * [merging]'s "leave keys you don't mention alone" safety net does not
 * apply to it. [reminderOffsetsHours] is derived from that same merged
 * minutes set (only the whole-hour-divisible values), so it never drifts
 * out of step with it — including for a preserved foreign value that
 * happens to be a whole number of hours.
 *
 * A foreign value that exists *only* in a legacy `reminder_offsets_hours`
 * entry with no `reminder_offsets_minutes` counterpart at all (a pre-2.1.0
 * write) is not separately preserved: every 2.1.0+ writer mirrors a value
 * it cares about into minutes, so that shape can only come from a client
 * older than this whole feature, and `reminder_offsets_hours` has always
 * been the lossy field by design (see [AssignmentsSection]'s KDoc).
 */
internal fun AssignmentSyncValues.documentUpdates(existing: JsonObject): JsonObject {
    val existingAssignments = existing.get(ASSIGNMENTS_SECTION_KEY)
        ?.takeIf { it.isJsonObject }
        ?.asJsonObject
    val knownMinutes = offsets.map { it.reminderOffsetMinutes }.toSet()
    val foreignMinutes = existingAssignments?.get(REMINDER_OFFSETS_MINUTES_KEY).asValidatedIntListOrNull()
        .orEmpty()
        .filter { AssignmentReminderOffset.fromMinutes(it) == null }
    val minutes = (knownMinutes + foreignMinutes).sortedDescending()
    val hours = minutes.filter { it % 60 == 0 }.map { it / 60 }.sortedDescending()

    val section = JsonObject()
    section.addProperty(ASSIGNMENTS_ENABLED_KEY, enabled)
    section.add(REMINDER_OFFSETS_HOURS_KEY, JsonArray().apply { hours.forEach { add(it) } })
    section.add(REMINDER_OFFSETS_MINUTES_KEY, JsonArray().apply { minutes.forEach { add(it) } })

    val root = JsonObject()
    root.add(ASSIGNMENTS_SECTION_KEY, section)
    return root
}

/**
 * Read-modify-write cycle for the document's `assignments` section —
 * the [pushLiveActivitySettings] of this section. Everything else in the
 * document, `courses` and any section this build has never heard of
 * included, travels back exactly as read (see [merging]). On a 409,
 * adopts the server's document and its revision and retries **exactly
 * once** — never loops. See [pushLiveActivitySettings] for why each of the
 * three gates below sits where it does and why the generation is re-checked
 * before the read and before every write.
 *
 * The update is recomputed from the freshly-read [merging] target on every
 * loop iteration (including after a conflict rebase), not built once up
 * front: the foreign-minute preservation [documentUpdates] does depends on
 * what the *current* document holds, and after a 409 that is the winner's
 * document, not the stale one this call started with.
 */
internal suspend fun pushAssignmentSettings(
    local: AssignmentSyncValues,
    transport: SettingsDocumentTransport,
    cloudSyncEnabled: Boolean,
    syncAssignmentReminders: Boolean,
    isLoggedIn: Boolean,
    isCurrentGeneration: () -> Boolean = { true },
): Boolean {
    if (!cloudSyncEnabled || !syncAssignmentReminders || !isLoggedIn) return false
    if (!isCurrentGeneration()) return false

    val current = transport.read()
    if (!isCurrentGeneration()) return false
    var existing = current?.document ?: JsonObject()
    var baseRevision = current?.revision

    var conflicts = 0
    while (true) {
        if (!isCurrentGeneration()) return false
        val result = transport.write(merging(local.documentUpdates(existing), into = existing), baseRevision)
        when (result) {
            is SettingsWriteResult.Written -> return true
            is SettingsWriteResult.Conflict -> {
                conflicts++
                if (conflicts > 1) {
                    throw SettingsDocumentApiException(
                        "write $NOTIFICATION_SETTINGS_NAMESPACE: still conflicting after one rebase"
                    )
                }
                existing = result.server.document
                baseRevision = result.server.revision
            }
        }
    }
}

/**
 * Read half of the `assignments` section — the [pullLiveActivitySettings]
 * of this section, except that it returns what to apply instead of applying
 * it, because the store it would apply to is the caller's. `enabled`
 * degrades to [current]'s value exactly like the five live_activity fields;
 * the offset set goes through [resolveOffsets] instead of a plain
 * validated-or-keep check, since what "the document can't express it, keep
 * local" means for offsets depends on *which* of the two offset fields is
 * present (see that function's KDoc).
 *
 * Returns the values the local store should now hold, resolved against
 * [current], or `null` when there is nothing to apply:
 * - a gate is closed, so no request is made (matching
 *   [pullLiveActivitySettings]'s `false`);
 * - [isCurrentGeneration] reports that the account the read was issued under
 *   has since left;
 * - the document has no `assignments` object (missing, or JSON `null`);
 * - [isLocalDirty] reports that a local edit must win.
 *
 * In the last three cases `null` is the whole answer, never "[current],
 * unchanged": [current] is a snapshot taken before the read, and handing it
 * back would have the caller write it over an edit made while the read was
 * on the wire.
 *
 * [isLocalDirty] and [isCurrentGeneration] are both asked once the read has
 * come back, as in [pullLiveActivitySettings] — see that function for what
 * the account guard catches that the dirty check structurally cannot. When
 * [isLocalDirty] reports `true`, [onLocalDirty] runs so the caller can have
 * that edit's push sent; a departed account gets no such courtesy, since its
 * edit is not this session's to send.
 */
internal suspend fun pullAssignmentSettings(
    current: AssignmentSyncValues,
    transport: SettingsDocumentTransport,
    cloudSyncEnabled: Boolean,
    syncAssignmentReminders: Boolean,
    isLoggedIn: Boolean,
    isLocalDirty: () -> Boolean = { false },
    onLocalDirty: () -> Unit = {},
    isCurrentGeneration: () -> Boolean = { true },
): AssignmentSyncValues? {
    if (!cloudSyncEnabled || !syncAssignmentReminders || !isLoggedIn) return null
    if (!isCurrentGeneration()) return null

    val section = transport.read()?.document
        ?.get(ASSIGNMENTS_SECTION_KEY)
        ?.takeIf { it.isJsonObject }
        ?.asJsonObject

    if (!isCurrentGeneration()) return null
    if (isLocalDirty()) {
        onLocalDirty()
        return null
    }
    if (section == null) return null

    return AssignmentSyncValues(
        enabled = section.get(ASSIGNMENTS_ENABLED_KEY).asValidatedBooleanOrNull() ?: current.enabled,
        offsets = resolveOffsets(
            documentMinutes = section.get(REMINDER_OFFSETS_MINUTES_KEY).asValidatedIntListOrNull(),
            documentHours = section.get(REMINDER_OFFSETS_HOURS_KEY).asValidatedIntListOrNull(),
            currentLocal = current.offsets,
        ),
    )
}

/**
 * [pullAssignmentSettings], with a transport failure caught and reported
 * through [onFailure] instead of left to propagate — the assignments mirror
 * of [pullLiveActivitySettingsCatching]; see that function's KDoc for why
 * this distinction (a degraded-but-successful read vs. the transport itself
 * failing to answer) matters and for the crash this shape prevents.
 */
internal suspend fun pullAssignmentSettingsCatching(
    current: AssignmentSyncValues,
    transport: SettingsDocumentTransport,
    cloudSyncEnabled: Boolean,
    syncAssignmentReminders: Boolean,
    isLoggedIn: Boolean,
    isLocalDirty: () -> Boolean = { false },
    onLocalDirty: () -> Unit = {},
    isCurrentGeneration: () -> Boolean = { true },
    onFailure: (Throwable) -> Unit = {},
): AssignmentSyncValues? = try {
    pullAssignmentSettings(
        current,
        transport,
        cloudSyncEnabled,
        syncAssignmentReminders,
        isLoggedIn,
        isLocalDirty,
        onLocalDirty,
        isCurrentGeneration,
    )
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    onFailure(e)
    null
}

/**
 * How many additional attempts [NotificationSettingsSync]'s coalesced push
 * loop makes after a push fails, before giving up until the next trigger:
 * an edit, either section's 同步內容 switch turning on, or — while the edit
 * is still unconfirmed — a sign-in, a successful full sync, or either
 * settings screen opening. Bounded so a persistent failure (offline,
 * server down) cannot retry forever.
 */
internal const val MAX_PUSH_RETRIES = 2

/**
 * Whether the coalesced push loop should schedule another attempt after
 * [consecutiveFailures] consecutive push failures.
 *
 * Extracted as a pure function — no [Channel], no `delay()` — so the retry
 * *policy* is unit-testable without the real coroutine timing
 * [NotificationSettingsSync]'s `init` block runs the loop on. Same
 * reasoning as `applyOptOutIfAccepted` being lifted out of
 * `PushRegistrationService`.
 */
internal fun shouldRetryAfterPushFailure(
    consecutiveFailures: Int,
    maxRetries: Int = MAX_PUSH_RETRIES,
): Boolean = consecutiveFailures in 1..maxRetries

/**
 * How long to wait before the retry following the [consecutiveFailures]-th
 * consecutive push failure (so `pushRetryBackoffMillis(1)` is the delay
 * before the first retry).
 *
 * The loop's only prior spacing was the fixed 250 ms coalescing
 * `delay(DEBOUNCE_MS)` at the top of every iteration, so with
 * [MAX_PUSH_RETRIES] retries all attempts used to land within about a
 * second of the first failure — no defense at all against the dominant
 * real failure mode, the device being offline, which does not resolve
 * itself in under a second. Real, increasing spacing gives a transient
 * outage a chance to clear before the budget is spent; it does not make
 * the retry unbounded — [shouldRetryAfterPushFailure] still stops it.
 */
internal fun pushRetryBackoffMillis(consecutiveFailures: Int): Long =
    if (consecutiveFailures <= 1) 5_000L else 30_000L

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
 *
 * Every push is queued through one of two entry points, and which one a
 * caller uses is the protocol: [markUnconfirmedAndPush] /
 * [markAssignmentUnconfirmedAndPush] for a change this device made, which
 * the other devices must now see, and [pushIfUnconfirmed] for everything
 * that only wants a change still pending delivered, for either section.
 * [cancelPendingPushes] ends all of it at logout.
 */
/**
 * The assignment-reminder values [NotificationSettingsSync] reads and writes
 * — the `notification` document's `assignments` section is backed by
 * [org.ntust.app.tigerduck.data.preferences.AppPreferences], which (like
 * [SettingsDocumentApiClient]) needs a real `Context` and so cannot be
 * constructed in this module's plain-JVM tests. A three-property interface
 * rather than six separate get/set lambdas — the same information, but a
 * test fake implements it as one small class instead of a constructor call
 * with six trailing closures.
 */
internal interface AssignmentReminderSyncStore {
    var enabled: Boolean
    var offsets: Set<AssignmentReminderOffset>

    /** The assignments-section mirror of `LiveActivityPreferences.hasUnconfirmedSyncEdit`. */
    var hasUnconfirmedEdit: Boolean
}

/**
 * What a pull that changes the assignment-reminder settings does to the
 * reminders already armed on this device. Android fires these reminders
 * itself (the backend delivers assignment reminders only to iPhone and
 * iPad), so a setting adopted from another device has to reach
 * [org.ntust.app.tigerduck.notification.AssignmentNotificationScheduler]
 * the way this device's own edits do. An interface for the same reason as
 * [AssignmentReminderSyncStore]: the scheduler and the assignment cache
 * both need a real `Context`.
 */
internal interface AssignmentReminderAlarms {
    /** Cancels every assignment reminder armed on this device. */
    fun cancelAll()

    /**
     * Re-arms a reminder for every cached, unfinished assignment, at the
     * offsets stored at the moment it arms them.
     */
    suspend fun rescheduleFromCache()
}

@Singleton
class NotificationSettingsSync internal constructor(
    private val transport: SettingsDocumentTransport,
    private val liveActivityPreferences: LiveActivityPreferences,
    private val assignmentStore: AssignmentReminderSyncStore,
    private val assignmentAlarms: AssignmentReminderAlarms,
    private val cloudSyncEnabled: () -> Boolean,
    private val syncLiveActivity: () -> Boolean,
    private val syncAssignmentReminders: () -> Boolean,
    private val isLoggedIn: () -> Boolean,
    syncLiveActivityChanged: Flow<Unit>,
    syncAssignmentRemindersChanged: Flow<Unit>,
    scope: CoroutineScope,
) {
    // The real entry point: Hilt calls this (the @Inject constructor), which
    // adapts the app's singletons to the plain types of the primary
    // constructor above. Split this way so NotificationSettingsSyncTest can
    // build the real class on the plain JVM: SettingsDocumentApiClient,
    // AppPreferences, AuthTokenManager, the scheduler and the cache all need
    // a real Context or the Android Keystore. Same split, for the same
    // reason, as LiveActivityPreferences.
    @Inject constructor(
        client: SettingsDocumentApiClient,
        liveActivityPreferences: LiveActivityPreferences,
        appPreferences: AppPreferences,
        authTokenManager: AuthTokenManager,
        scheduler: AssignmentNotificationScheduler,
        dataCache: DataCache,
        @ApplicationScope scope: CoroutineScope,
    ) : this(
        transport = object : SettingsDocumentTransport {
            override suspend fun read(): SettingsDocumentEnvelope<JsonObject>? =
                client.read(NOTIFICATION_SETTINGS_NAMESPACE, JsonObject::class.java)

            override suspend fun write(
                document: JsonObject,
                baseRevision: Long?,
            ): SettingsWriteResult<JsonObject> =
                client.write(NOTIFICATION_SETTINGS_NAMESPACE, document, baseRevision, JsonObject::class.java)
        },
        liveActivityPreferences = liveActivityPreferences,
        assignmentStore = object : AssignmentReminderSyncStore {
            override var enabled: Boolean
                get() = appPreferences.notifyAssignments
                set(value) { appPreferences.notifyAssignments = value }
            override var offsets: Set<AssignmentReminderOffset>
                get() = appPreferences.notifyAssignmentOffsets
                set(value) { appPreferences.notifyAssignmentOffsets = value }
            override var hasUnconfirmedEdit: Boolean
                get() = appPreferences.hasUnconfirmedAssignmentSyncEdit
                set(value) { appPreferences.hasUnconfirmedAssignmentSyncEdit = value }
        },
        assignmentAlarms = object : AssignmentReminderAlarms {
            override fun cancelAll() = scheduler.cancelAllTracked()

            override suspend fun rescheduleFromCache() {
                val assignments = dataCache.loadAssignments().filter { !it.isCompleted }
                val safetyNetIds =
                    dataCache.loadIgnoredAssignments() + dataCache.loadMarkedCompletedAssignments()
                scheduler.scheduleAll(assignments, safetyNetIds, appPreferences.notifyAssignmentOffsets)
            }
        },
        cloudSyncEnabled = { appPreferences.cloudSyncEnabled },
        syncLiveActivity = { appPreferences.syncLiveActivity },
        syncAssignmentReminders = { appPreferences.syncAssignmentReminders },
        isLoggedIn = { authTokenManager.isLoggedIn },
        syncLiveActivityChanged = appPreferences.syncLiveActivityChanged,
        syncAssignmentRemindersChanged = appPreferences.syncAssignmentRemindersChanged,
        scope = scope,
    )

    // CONFLATED: a slider's onValueChange fires on every pointer move, so
    // dozens of enqueue calls collapse into one pending push, and the one
    // that runs always reads the newest preference values. Each item is the
    // `generation` it was queued under.
    private val pending = Channel<Int>(Channel.CONFLATED)

    // Bumped by cancelPendingPushes() at logout. A push queued under an
    // older generation belongs to the account that signed out, so the loop
    // drops it rather than sending it over whichever account is signed in
    // by the time it runs.
    private val generation = AtomicInteger(0)

    // Held by each queued push for the whole of its read-modify-write, and
    // by pullNow() from its snapshot of the local values to its apply, so
    // neither can land in the middle of the other. Without it, an edit's
    // push could clear the unconfirmed flag while a pull's GET, sent before
    // the edit, was still on the wire, and that pull would then apply the
    // older document over the confirmed edit. iOS gets the same guarantee by
    // running its reconcile as a link on the one chain its pushes run on
    // (NotificationSettingsPushQueue.enqueueReconcile).
    private val documentLock = Mutex()

    // How many pushes in a row have failed outright (an exception from
    // pushNow(), never a closed-gate no-op — see the init loop below). The
    // loop bumps and reads it on the application scope's dispatcher, while
    // both entry points reset it from whichever thread calls them: the main
    // thread, a sync coroutine, or a relogin running inside some request.
    // There is no lock, and the race is benign: an interleaving can lose one
    // bump or one reset, which moves the retry budget by at most one
    // attempt. It cannot make retries unbounded, because only an entry point
    // resets it and the retry never goes through one.
    private var consecutiveFailures = 0

    init {
        scope.launch {
            for (queuedGeneration in pending) {
                // Settle before reading the preferences: a drag that is still
                // in progress should produce one PUT carrying its final value,
                // not one per step. Same 250 ms coalescing window
                // PushRegistrationService.scheduleRegister uses.
                delay(DEBOUNCE_MS)
                // Queued before a logout: it belongs to the account that left.
                if (queuedGeneration != generation.get()) continue
                runCatching { documentLock.withLock { pushNow(queuedGeneration) } }.fold(
                    onSuccess = { succeeded ->
                        // A `false` here means nothing was sent, on purpose:
                        // no section had an unconfirmed edit, every dirty
                        // section's gate was closed (not logged in, cloud
                        // sync off, or that section's 同步內容 switch off), or
                        // a logout abandoned the push. An intentional no-op,
                        // not a failure to retry. The next real trigger
                        // re-enqueues.
                        if (succeeded) consecutiveFailures = 0
                    },
                    onFailure = { e ->
                        if (e is CancellationException) throw e
                        Log.w(TAG, "notification settings push failed", e)
                        consecutiveFailures++
                        // Re-enqueue for another attempt, bounded and spaced
                        // out: a persistent failure (offline, server down)
                        // must neither retry forever nor burn all its
                        // attempts in the same second a transient blip
                        // would. Straight into the queue rather than through
                        // an entry point, which would reset
                        // consecutiveFailures and make this unbounded — and
                        // only if no logout came during the wait, or it
                        // would displace a push the next account queued.
                        // Giving up is not the end of the story either: the
                        // edit stays unconfirmed, so the next sign-in, full
                        // sync or settings-screen visit sends it again
                        // through pushIfUnconfirmed().
                        if (shouldRetryAfterPushFailure(consecutiveFailures)) {
                            delay(pushRetryBackoffMillis(consecutiveFailures))
                            if (queuedGeneration == generation.get()) pending.trySend(queuedGeneration)
                        } else {
                            Log.w(
                                TAG,
                                "notification settings push: giving up after $consecutiveFailures failures",
                            )
                        }
                    },
                )
            }
        }
        // Turning "同步內容 → 即時更新" back on doesn't itself change any of
        // the five synced values, so nothing else here would ever notice
        // and (re-)push them. Mirrors the appLanguageChanged collector in
        // PushRegistrationService.init.
        scope.launch {
            syncLiveActivityChanged.collect {
                if (syncLiveActivity()) markUnconfirmedAndPush()
            }
        }
        // Same reasoning, for "同步內容 → 作業到期提醒": turning it back on
        // doesn't itself change notifyAssignments/notifyAssignmentOffsets, so
        // without this the just-ungated section would stay stale server-side
        // until some unrelated edit happened to trigger a push. Mirrors
        // iOS's shouldPushOnDeviceSwitchChange (true only on the off->on
        // transition, which is exactly what "a change arrived on this flow"
        // means here since AppPreferences only emits on an actual change).
        scope.launch {
            syncAssignmentRemindersChanged.collect {
                if (syncAssignmentReminders()) markAssignmentUnconfirmedAndPush()
            }
        }
    }

    /**
     * Marks this device's Live Update values unconfirmed
     * ([LiveActivityPreferences.hasUnconfirmedSyncEdit]) and queues a push
     * of them. Returns immediately; the write happens on the application
     * scope.
     *
     * For exactly the two triggers that make this device's values the ones
     * the user's other devices should now see: an edit to one of the five
     * synced values
     * ([org.ntust.app.tigerduck.ui.screen.settings.LiveActivitySettingsViewModel]),
     * and "同步內容 → 即時更新" turning on (this class's `init`), which
     * republishes values that stopped syncing while it was off — iOS pushes
     * on that same off-to-on edge. Nothing else may call this. A marked
     * device keeps its values through every pull and pushes them over
     * whatever the other devices wrote, so a sign-in or a sync that marked
     * would overwrite the shared settings with this device's stale ones.
     * Those use [pushIfUnconfirmed].
     *
     * Resets [consecutiveFailures] so every fresh trigger gets its own full
     * retry budget — without this, a bad streak left the counter stuck at
     * [MAX_PUSH_RETRIES] forever, and every later trigger got exactly one
     * attempt and zero retries.
     */
    fun markUnconfirmedAndPush() {
        liveActivityPreferences.hasUnconfirmedSyncEdit = true
        consecutiveFailures = 0
        pending.trySend(generation.get())
    }

    /**
     * The assignments-section mirror of [markUnconfirmedAndPush]: marks
     * [AssignmentReminderSyncStore.hasUnconfirmedEdit] and queues a push
     * through the same channel. Called for exactly the two triggers that
     * make this device's assignment-reminder settings the ones the user's
     * other devices should now see: an edit to the master switch or an
     * offset
     * ([org.ntust.app.tigerduck.ui.screen.settings.AssignmentReminderSettingsViewModel]),
     * and "同步內容 → 作業到期提醒" turning on (this class's `init`).
     */
    fun markAssignmentUnconfirmedAndPush() {
        assignmentStore.hasUnconfirmedEdit = true
        consecutiveFailures = 0
        pending.trySend(generation.get())
    }

    /**
     * Queues a push only if an earlier edit — live_activity's, assignments',
     * or both — is still unconfirmed, and never marks anything: with nothing
     * pending it does nothing at all, and the next pull is free to adopt
     * what another device wrote.
     *
     * The catch-up for an edit whose push has not landed — one made while
     * signed out, or while cloud sync or the relevant "同步內容" switch was
     * off, or one whose retries ran out. Called from each of
     * [org.ntust.app.tigerduck.auth.AuthService]'s sign-in paths (a fresh
     * login, the v2→v3 migration and the silent relogin), after every
     * successful full sync (`HomeBackendSync.pull`), and by [pullNow] when it
     * finds an edit it must not overwrite.
     *
     * The relogin is one more reason this must never mark: a lapsed refresh
     * token sends `AuthTokenManager.authHeader()` into
     * `AuthService.attemptRelogin` from inside whatever request is being
     * built, a pull's included, and a pull whose own relogin had marked the
     * device would skip applying the document it had just read.
     *
     * Resets [consecutiveFailures] when it queues, for the same reason
     * [markUnconfirmedAndPush] does.
     */
    fun pushIfUnconfirmed() {
        if (!liveActivityPreferences.hasUnconfirmedSyncEdit && !assignmentStore.hasUnconfirmedEdit) return
        consecutiveFailures = 0
        pending.trySend(generation.get())
    }

    /**
     * Drops every queued push and clears the unconfirmed-edit flag. Called
     * from `AuthService.logout()`: both belong to the account that is
     * leaving, and neither may reach whoever signs in next. The flag would
     * make that sign-in push this device's values into the new account's
     * document, and a queued push — one still in its debounce, or a retry
     * still in its backoff — would run under the new session. Mirrors iOS's
     * `cancelNotificationSettingsPushes()`.
     *
     * A push already past the loop's own generation check above is not
     * stopped here, but it still cannot land under whoever signs in next:
     * [pushLiveActivitySettings] re-checks the generation this bump just
     * changed before its read and before every write, and abandons the push
     * the instant it no longer matches — the same way iOS's reconcile
     * checks `isCurrent()` after every round trip. That check is what
     * actually stops it; a request that somehow slipped past every one of
     * those checks would in any case be built after logout has wiped the
     * tokens, and go out without one and fail.
     */
    fun cancelPendingPushes() {
        generation.incrementAndGet()
        liveActivityPreferences.hasUnconfirmedSyncEdit = false
        assignmentStore.hasUnconfirmedEdit = false
    }

    /**
     * Reads the shared `notification` document and applies its
     * `live_activity` and `assignments` sections to [liveActivityPreferences]
     * / [assignmentStore] — see [pullLiveActivitySettings] /
     * [pullAssignmentSettings] for exactly what happens when a section is
     * missing, null, or individually malformed, and for what each store's
     * own unconfirmed-edit flag protects against. A transport failure
     * (offline, a non-2xx/404 status) is caught and logged rather than
     * thrown for either — see [pullLiveActivitySettingsCatching] /
     * [pullAssignmentSettingsCatching] — so this can never be the thing that
     * crashes the caller.
     *
     * Runs under [documentLock], so a queued push cannot land between a read
     * here and the apply that follows it: a push that comes due while this
     * runs waits for it, and this waits for a push already under way.
     *
     * The assignment values are applied only if, once the read has come
     * back, no edit is waiting to be pushed and the store still holds
     * exactly what it held when this pull started — the pair iOS checks
     * after every round trip (`!isPushPending()` and
     * `LocalPreferences(from: store) == expected`). The flag catches an edit
     * made while the read was on the wire; the comparison catches one whose
     * values are written but whose flag is not set yet. That check and the
     * writes after it have no suspension point between them, and every
     * caller runs this on the main thread, where the settings screens make
     * their edits, so no edit can land between the two either.
     *
     * Both sections are additionally abandoned, applying nothing, if the
     * account changed while their read was on the wire — the generation
     * [cancelPendingPushes] bumps, captured here and re-checked by each pull
     * exactly as each push re-checks it. Neither of the two guards above can
     * stand in for it: a logout clears both unconfirmed flags and leaves the
     * local values untouched, so both read "clean" for a document that
     * belongs to the account that has left.
     *
     * A pull that changes the assignment values also brings the reminders
     * already armed on this device into line, through [assignmentAlarms]:
     * cancelled when reminders are now off, re-armed from the assignment
     * cache otherwise. Android fires these reminders itself, so without this
     * a switch turned off on another device would keep firing here.
     *
     * The two pulls are independent: each is gated on its own switch and
     * dirty flag, and one failing or being gated off does not stop the
     * other. Returns `true` if *either* was actually attempted (matching
     * each individual function's own "attempted" contract), so a caller that
     * only cares about "did anything happen" (`LiveActivitySettingsViewModel`)
     * keeps working unchanged.
     *
     * Called when the Live Activity settings screen opens
     * ([org.ntust.app.tigerduck.ui.screen.settings.LiveActivitySettingsViewModel]),
     * identically when the assignment-reminder settings screen opens
     * ([org.ntust.app.tigerduck.ui.screen.settings.AssignmentReminderSettingsViewModel]),
     * and after every successful full sync
     * ([org.ntust.app.tigerduck.ui.screen.home.HomeBackendSync.pull], right
     * after its [pushIfUnconfirmed]), so another device's change reaches this
     * device's alarms without either screen being opened. Each caller
     * additionally wraps this call itself: two independent guards against the
     * one failure mode this repo has already documented twice as a process
     * kill.
     */
    suspend fun pullNow(): Boolean {
        var assignmentsChanged = false
        val attempted = documentLock.withLock {
            // The account this pull belongs to, captured alongside the local
            // snapshot below and re-checked once each read comes back. A
            // logout bumps it, so a response that arrives after one is
            // abandoned instead of applied — see pullLiveActivitySettings.
            val pulledGeneration = generation.get()
            val isCurrentGeneration = { pulledGeneration == generation.get() }

            val liveActivityAttempted = pullLiveActivitySettingsCatching(
                preferences = liveActivityPreferences,
                transport = transport,
                cloudSyncEnabled = cloudSyncEnabled(),
                syncLiveActivity = syncLiveActivity(),
                isLoggedIn = isLoggedIn(),
                isLocalDirty = { liveActivityPreferences.hasUnconfirmedSyncEdit },
                onLocalDirty = { pushIfUnconfirmed() },
                isCurrentGeneration = isCurrentGeneration,
                onFailure = { e -> Log.w(TAG, "pulling live_activity settings failed", e) },
            )

            val cloudSync = cloudSyncEnabled()
            val assignmentsSynced = syncAssignmentReminders()
            val loggedIn = isLoggedIn()
            var assignmentReadFailed = false
            val expected = assignmentValues()
            val pulled = pullAssignmentSettingsCatching(
                current = expected,
                transport = transport,
                cloudSyncEnabled = cloudSync,
                syncAssignmentReminders = assignmentsSynced,
                isLoggedIn = loggedIn,
                isLocalDirty = { assignmentStore.hasUnconfirmedEdit || assignmentValues() != expected },
                onLocalDirty = { pushIfUnconfirmed() },
                isCurrentGeneration = isCurrentGeneration,
                onFailure = { e ->
                    assignmentReadFailed = true
                    Log.w(TAG, "pulling assignment settings failed", e)
                },
            )
            if (pulled != null && pulled != expected) {
                if (pulled.enabled != expected.enabled) assignmentStore.enabled = pulled.enabled
                if (pulled.offsets != expected.offsets) assignmentStore.offsets = pulled.offsets
                assignmentsChanged = true
            }

            // A response the account guard abandoned is not an applied read,
            // whatever the gates said when it went out — and the alarm work
            // below hangs off assignmentsChanged, which that abandon leaves
            // false, so a logout's cancelled reminders stay cancelled.
            val assignmentsAttempted =
                cloudSync && assignmentsSynced && loggedIn && !assignmentReadFailed && isCurrentGeneration()
            liveActivityAttempted || assignmentsAttempted
        }

        // Outside the lock: local work that no push has to wait for.
        if (assignmentsChanged) {
            if (assignmentStore.enabled) assignmentAlarms.rescheduleFromCache() else assignmentAlarms.cancelAll()
        }
        return attempted
    }

    private fun assignmentValues() =
        AssignmentSyncValues(enabled = assignmentStore.enabled, offsets = assignmentStore.offsets)

    /**
     * Pushes whichever of the two sections this class owns is actually
     * unconfirmed, sequentially under the one queued trigger: live_activity
     * first (unchanged from before assignments existed), then assignments.
     * Sequential, not concurrent, so the assignments push's own read sees
     * whatever the live_activity push just wrote rather than racing it into
     * an avoidable 409.
     *
     * Each is gated on its own unconfirmed-edit flag *in addition to* its
     * own sync switch: every path that enqueues a push — [markUnconfirmedAndPush],
     * [markAssignmentUnconfirmedAndPush], [pushIfUnconfirmed], the off→on
     * switch collectors, and the failure handler's own re-`trySend` — sets
     * or requires at least one of the two flags first, so this never skips
     * a push that was actually asked for. What it does prevent: one
     * section's trigger (an assignments edit, say) re-sending the *other*
     * section's values on every unrelated queue run. Those values are only
     * this device's copy, and another device may have changed that section
     * since this one last pulled: an unconditional push would write the
     * stale copy over the newer values on every device. It would also cost
     * a request per section on every run.
     *
     * Each clears only its own flag, only if it both succeeded and nothing
     * changed locally while it ran — see [pushLiveActivitySettings]'s KDoc
     * (`canClearPendingMarker`) for why.
     *
     * Returns `true` if *either* section actually pushed, so the retry loop
     * above only treats "neither was dirty, or every dirty gate was closed"
     * as the no-op case; either section throwing propagates immediately
     * (the other is not attempted that pass), and the whole call is
     * retried together on the next attempt, exactly as a single-section
     * push already was.
     */
    private suspend fun pushNow(queuedGeneration: Int): Boolean {
        var liveActivitySucceeded = false
        if (liveActivityPreferences.hasUnconfirmedSyncEdit) {
            val sentLiveActivity = liveActivityPreferences.syncSnapshot()
            liveActivitySucceeded = pushLiveActivitySettings(
                local = sentLiveActivity,
                transport = transport,
                cloudSyncEnabled = cloudSyncEnabled(),
                syncLiveActivity = syncLiveActivity(),
                isLoggedIn = isLoggedIn(),
                isCurrentGeneration = { queuedGeneration == generation.get() },
            )
            // Cleared only by a push that landed, and only if the five
            // values still equal what it sent. A gate-closed `false`, an
            // abandoned push (the account changed mid-flight — see
            // pushLiveActivitySettings) or a thrown failure all leave the
            // flag set, or the edit would read as confirmed when nothing
            // was sent. So does an edit that landed while the request was
            // in flight: the server holds the older values, and that
            // edit's own push, queued behind this one, must still find the
            // flag set — or a process death before it runs would lose the
            // edit with the flag already reading "confirmed". Mirrors
            // iOS's canClearPendingMarker.
            if (liveActivitySucceeded && liveActivityPreferences.syncSnapshot() == sentLiveActivity) {
                liveActivityPreferences.hasUnconfirmedSyncEdit = false
            }
        }

        var assignmentsSucceeded = false
        if (assignmentStore.hasUnconfirmedEdit) {
            val sentAssignments = AssignmentSyncValues(enabled = assignmentStore.enabled, offsets = assignmentStore.offsets)
            assignmentsSucceeded = pushAssignmentSettings(
                local = sentAssignments,
                transport = transport,
                cloudSyncEnabled = cloudSyncEnabled(),
                syncAssignmentReminders = syncAssignmentReminders(),
                isLoggedIn = isLoggedIn(),
                isCurrentGeneration = { queuedGeneration == generation.get() },
            )
            val currentAssignments =
                AssignmentSyncValues(enabled = assignmentStore.enabled, offsets = assignmentStore.offsets)
            if (assignmentsSucceeded && currentAssignments == sentAssignments) {
                assignmentStore.hasUnconfirmedEdit = false
            }
        }

        return liveActivitySucceeded || assignmentsSucceeded
    }

    private companion object {
        const val TAG = "Push.NotifSettings"
        const val DEBOUNCE_MS = 250L
    }
}
