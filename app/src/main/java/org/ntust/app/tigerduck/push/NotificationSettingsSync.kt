// Writes this device's Live Update preferences into the `notification`
// settings-document namespace (`GET/PUT /v3/settings/notification`, design
// spec §4.6), and reads them back. Android owns exactly one key in that
// document — `live_activity`. Everything else belongs to somebody else:
// `assignments` and `courses` to iOS/the backend, plus whatever sections a
// newer build of either platform adds to the same namespace.
//
// The read half ([pullLiveActivitySettings]) is what makes this sync
// rather than one-way replication: without it, an iOS user's edits to
// their Live Activity lead times are invisible on Android. Every field it
// reads degrades to "keep the local value" when the document can't express
// it — missing, null, or the wrong JSON type — never to `false`/`0`. The
// two lead times are additionally clamped into this build's local range.
// That range equals iOS's slider ranges (1 h–8 h, 5 min–4 h), but nothing
// holds the document to it: the backend does not validate these fields,
// and iOS applies no floor to its assignment lead time when it loads or
// pulls one. So the document can hold a value this build's own slider
// could never produce.
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
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.liveactivity.LiveActivityPreferences
import org.ntust.app.tigerduck.liveactivity.LiveActivitySyncUpdate
import org.ntust.app.tigerduck.liveactivity.LiveActivitySyncValues
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.floor

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
): Boolean {
    if (!cloudSyncEnabled || !syncLiveActivity || !isLoggedIn) return false

    val section = transport.read()?.document
        ?.get(LIVE_ACTIVITY_SECTION_KEY)
        ?.takeIf { it.isJsonObject }
        ?.asJsonObject
        ?: return true

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
    onFailure: (Throwable) -> Unit = {},
): Boolean = try {
    pullLiveActivitySettings(
        preferences, transport, cloudSyncEnabled, syncLiveActivity, isLoggedIn, isLocalDirty, onLocalDirty,
    )
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    onFailure(e)
    false
}

/**
 * How many additional attempts [NotificationSettingsSync]'s coalesced push
 * loop makes after a push fails, before giving up until the next trigger:
 * an edit, the live-activity sync switch turning on, or — while the edit
 * is still unconfirmed — a sign-in, a successful full sync, or the Live
 * Activity settings screen opening. Bounded so a persistent failure
 * (offline, server down) cannot retry forever.
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
 * caller uses is the protocol: [markUnconfirmedAndPush] for a change this
 * device made, which the other devices must now see, and
 * [pushIfUnconfirmed] for everything that only wants a change still
 * pending delivered. [cancelPendingPushes] ends both at logout.
 */
@Singleton
class NotificationSettingsSync internal constructor(
    private val transport: SettingsDocumentTransport,
    private val liveActivityPreferences: LiveActivityPreferences,
    private val cloudSyncEnabled: () -> Boolean,
    private val syncLiveActivity: () -> Boolean,
    private val isLoggedIn: () -> Boolean,
    syncLiveActivityChanged: Flow<Unit>,
    scope: CoroutineScope,
) {
    // The real entry point: Hilt calls this (the @Inject constructor), which
    // adapts the app's singletons to the plain types of the primary
    // constructor above. Split this way so NotificationSettingsSyncTest can
    // build the real class on the plain JVM: SettingsDocumentApiClient,
    // AppPreferences and AuthTokenManager all need a real Context or the
    // Android Keystore. Same split, for the same reason, as
    // LiveActivityPreferences.
    @Inject constructor(
        client: SettingsDocumentApiClient,
        liveActivityPreferences: LiveActivityPreferences,
        appPreferences: AppPreferences,
        authTokenManager: AuthTokenManager,
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
        cloudSyncEnabled = { appPreferences.cloudSyncEnabled },
        syncLiveActivity = { appPreferences.syncLiveActivity },
        isLoggedIn = { authTokenManager.isLoggedIn },
        syncLiveActivityChanged = appPreferences.syncLiveActivityChanged,
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
                runCatching { pushNow(queuedGeneration) }.fold(
                    onSuccess = { succeeded ->
                        // A `false` here means a gate was closed (not logged
                        // in, cloud sync off, or live-activity sync off) — an
                        // intentional no-op, not a failure to retry. The next
                        // real trigger re-enqueues.
                        if (succeeded) consecutiveFailures = 0
                    },
                    onFailure = { e ->
                        if (e is CancellationException) throw e
                        Log.w(TAG, "live_activity settings push failed", e)
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
                                "live_activity settings push: giving up after $consecutiveFailures failures",
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
     * Queues a push only if an earlier edit is still unconfirmed, and never
     * marks anything: with nothing pending it does nothing at all, and the
     * next pull is free to adopt what another device wrote.
     *
     * The catch-up for an edit whose push has not landed — one made while
     * signed out, or while cloud sync or "同步內容 → 即時更新" was off, or one
     * whose retries ran out. Called from each of
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
        if (!liveActivityPreferences.hasUnconfirmedSyncEdit) return
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
    }

    /**
     * Reads the shared `notification` document and applies its
     * `live_activity` section to [liveActivityPreferences] — see
     * [pullLiveActivitySettings] for exactly what happens when the section
     * is missing, null, or individually malformed, and for what
     * [LiveActivityPreferences.hasUnconfirmedSyncEdit] protects against. A
     * transport failure (offline, a non-2xx/404 status) is caught and
     * logged rather than thrown — see [pullLiveActivitySettingsCatching] —
     * so this can never be the thing that crashes the caller.
     *
     * Called when the Live Activity settings screen opens
     * ([org.ntust.app.tigerduck.ui.screen.settings.LiveActivitySettingsViewModel]),
     * which additionally wraps this call itself: two independent guards
     * against the one failure mode this repo has already documented twice
     * as a process kill.
     */
    suspend fun pullNow(): Boolean = pullLiveActivitySettingsCatching(
        preferences = liveActivityPreferences,
        transport = transport,
        cloudSyncEnabled = cloudSyncEnabled(),
        syncLiveActivity = syncLiveActivity(),
        isLoggedIn = isLoggedIn(),
        isLocalDirty = { liveActivityPreferences.hasUnconfirmedSyncEdit },
        onLocalDirty = { pushIfUnconfirmed() },
        onFailure = { e -> Log.w(TAG, "pulling live_activity settings failed", e) },
    )

    private suspend fun pushNow(queuedGeneration: Int): Boolean {
        val sent = liveActivityPreferences.syncSnapshot()
        val succeeded = pushLiveActivitySettings(
            local = sent,
            transport = transport,
            cloudSyncEnabled = cloudSyncEnabled(),
            syncLiveActivity = syncLiveActivity(),
            isLoggedIn = isLoggedIn(),
            isCurrentGeneration = { queuedGeneration == generation.get() },
        )
        // Cleared only by a push that landed, and only if the five values
        // still equal what it sent. A gate-closed `false`, an abandoned push
        // (the account changed mid-flight — see pushLiveActivitySettings) or
        // a thrown failure all leave the flag set, or the edit would read as
        // confirmed when nothing was sent. So does an edit that landed while
        // the request was in flight: the server holds the older values, and
        // that edit's own push, queued behind this one, must still find the
        // flag set — or a process death before it runs would lose the edit
        // with the flag already reading "confirmed". Mirrors iOS's
        // canClearPendingMarker.
        if (succeeded && liveActivityPreferences.syncSnapshot() == sent) {
            liveActivityPreferences.hasUnconfirmedSyncEdit = false
        }
        return succeeded
    }

    private companion object {
        const val TAG = "Push.NotifSettings"
        const val DEBOUNCE_MS = 250L
    }
}
