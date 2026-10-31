// Writes this device's Live Update preferences, its assignment-reminder
// settings AND its class-reminder settings into the `notification`
// settings-document namespace (`GET/PUT /v3/settings/notification`, design
// spec §4.6), and reads them back. Android owns three keys in that document:
// `live_activity` and `assignments`, each gated on its own "同步內容" switch
// (`syncLiveActivity` / `syncAssignmentReminders`) plus `cloudSyncEnabled`,
// and `courses`, gated on `cloudSyncEnabled` alone as on iOS. Whatever
// section a newer build of either platform adds to the same namespace this
// file only ever round-trips unchanged.
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
// Within a section this file owns, a push writes only the fields this device
// changed since it last synced with the document, plus any the document does
// not hold yet; every other field keeps the server's current value. Each
// section's last synced values are recorded ([LastSyncedSections]) as the base
// of that three-way merge ([keepingOwnChanges]). Writing the whole section from
// local values used to take back another device's change to a different field
// of it: a push still pending when that change landed, or one a sync switch
// turned back on sent, carried this device's older copy of every field. iOS
// 2.9.2 merges the same way, with no backend change; its rules are in the Apple
// repo's docs/decisions/0002-notification-settings-json-merge.md.
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

import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.liveactivity.LiveActivityPreferences
import org.ntust.app.tigerduck.liveactivity.LiveActivitySyncUpdate
import org.ntust.app.tigerduck.liveactivity.LiveActivitySyncValues
import org.ntust.app.tigerduck.notification.AssignmentNotificationScheduler
import org.ntust.app.tigerduck.notification.AssignmentReminderOffset
import org.ntust.app.tigerduck.notification.ClassReminders
import org.ntust.app.tigerduck.util.logSummary
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

    /**
     * `PUT /v3/settings/notification` with [document], compare-and-swapping on [baseRevision].
     *
     * [isCurrent] is asked once the request carries its bearer, just before it
     * goes out; false sends nothing and returns [SettingsWriteResult.Abandoned].
     * See [pushLiveActivitySettings] for why it cannot be asked any earlier.
     */
    suspend fun write(
        document: JsonObject,
        baseRevision: Long?,
        isCurrent: () -> Boolean,
    ): SettingsWriteResult<JsonObject>
}

/**
 * A [SettingsDocumentTransport] that goes to the network for the first
 * [read] and answers every later one with what that read produced — the same
 * envelope, or the same failure thrown again.
 *
 * [NotificationSettingsSync.pullNow] wraps one of these around the real
 * transport for the span of a single pull. The two sections it applies,
 * `live_activity` and `assignments`, are independent functions that each
 * read the document they own a section of — which is the right shape for
 * them, but it means the *same* document was fetched twice per pull, now on
 * every successful full sync rather than only when a settings screen opens.
 * One request answers both.
 *
 * Nothing else about either section changes: each still makes no request at
 * all when its own gate is closed (whichever section reads first pays for
 * it), still asks its dirty check and its generation check after the read
 * has come back, and still degrades on its own terms.
 *
 * A failure is cached and rethrown rather than retried, so a pull cannot
 * turn into two requests on exactly the path where requests are failing;
 * both sections then report the same failure, which is what one shared read
 * means. [CancellationException] is never cached — it belongs to the
 * coroutine being cancelled, not to the document.
 */
private class SingleReadTransport(
    private val delegate: SettingsDocumentTransport,
    /** The document is known to be missing, so the read answers as a 404 without going out. */
    private val knownMissing: Boolean = false,
    /** Runs when the read finds no document. */
    private val onMissing: () -> Unit = {},
) : SettingsDocumentTransport {
    private var answered = false
    private var envelope: SettingsDocumentEnvelope<JsonObject>? = null
    private var failure: Exception? = null

    override suspend fun read(): SettingsDocumentEnvelope<JsonObject>? {
        if (!answered) {
            try {
                if (!knownMissing) {
                    envelope = delegate.read()
                    if (envelope == null) onMissing()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
            }
            answered = true
        }
        failure?.let { throw it }
        return envelope
    }

    override suspend fun write(
        document: JsonObject,
        baseRevision: Long?,
        isCurrent: () -> Boolean,
    ): SettingsWriteResult<JsonObject> = delegate.write(document, baseRevision, isCurrent)
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
 * The part of [updates] (`{"<section>": {...}}`, every key a push of
 * [section] would write) this device has a reason to write over [server]: a
 * field it changed since [lastSynced] (the section as it last synced it), and
 * a field [server]'s section does not hold at all. An empty object when there
 * is none, so [merging] it would change nothing.
 *
 * Every other field keeps the value [server] holds now, which may be another
 * device's change this device has not pulled yet; sending this device's copy
 * of it would take that change back. A field both devices changed goes to
 * whichever writes last, as every field did before.
 *
 * [local] and [lastSynced] are in the document's keys
 * ([LiveActivitySyncValues.syncedSection] and its two siblings). [fieldOf]
 * names the field an update key belongs to, for a key that only ever moves
 * with another: `reminder_offsets_hours` is derived from
 * `reminder_offsets_minutes`.
 *
 * A null [lastSynced] means nothing is recorded, so nothing counts as
 * changed and only what [server] lacks is written: a value nobody has written
 * cannot be a newer one. An empty [lastSynced] ([SENDS_EVERY_FIELD]) makes
 * every field count as changed, which is how every push wrote before this
 * merge, and what the one push an update finds pending does
 * ([LastSyncedSections]). Otherwise a field [lastSynced] does not hold counts
 * as unchanged: a record an earlier build wrote lacks a field a later build
 * adds, and this device has no claim to having changed it, so another
 * device's value of it stays.
 */
internal fun keepingOwnChanges(
    updates: JsonObject,
    section: String,
    local: JsonObject,
    lastSynced: JsonObject?,
    server: JsonObject,
    fieldOf: (String) -> String = { it },
): JsonObject {
    val wanted = updates.get(section)?.takeIf { it.isJsonObject }?.asJsonObject ?: return JsonObject()
    val held = server.get(section)?.takeIf { it.isJsonObject }?.asJsonObject?.keySet().orEmpty()
        .mapTo(mutableSetOf(), fieldOf)
    val kept = JsonObject()
    for ((key, value) in wanted.entrySet()) {
        val field = fieldOf(key)
        val changedHere = when {
            lastSynced == null -> false
            lastSynced.isEmpty -> true
            else -> lastSynced.has(field) && local.get(field) != lastSynced.get(field)
        }
        if (changedHere || field !in held) kept.add(key, value)
    }
    return if (kept.isEmpty) JsonObject() else JsonObject().apply { add(section, kept) }
}

/** [text] as a recorded section ([LastSyncedSections]), or null when there is none or it does not parse. */
internal fun recordedSection(text: String?): JsonObject? {
    if (text == null) return null
    return try {
        JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
    } catch (e: JsonParseException) {
        null
    }
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
 * `assignments` and `courses`, which Android also owns, go out in pushes of
 * their own ([AssignmentSyncValues.documentUpdates],
 * [ClassReminderSyncValues.documentUpdates]), so neither appears in these
 * `updates` and this merge cannot touch them.
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
 * These values as the `live_activity` section holds them: what
 * [LastSyncedSections.liveActivity] records, and what a push compares with it
 * field by field ([keepingOwnChanges]).
 */
internal fun LiveActivitySyncValues.syncedSection(): JsonObject =
    documentUpdates().getAsJsonObject(LIVE_ACTIVITY_SECTION_KEY)

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
 * [merging]), and so does every field of this section this device has not
 * changed since [lastSynced] ([keepingOwnChanges]). On a 409 conflict,
 * adopts the server's document and its revision and retries **exactly once**
 * — never loops.
 *
 * Returns true when a write landed, or when the document already holds what
 * this device has to write, so nothing is sent; false when the
 * gates below are closed and nothing was attempted, or when
 * [isCurrentGeneration] turns false partway through and the push is
 * abandoned, so a caller can never read "didn't land" as "succeeded".
 *
 * [lastSynced] is the section as this device last synced it
 * ([NotificationSettingsSync] passes what [LastSyncedSections] records, null
 * when nothing is). It defaults to an empty object, which makes every field
 * count as changed: the wire mapping's own tests write the whole section.
 *
 * [isCurrentGeneration] is checked before the read, again once the read
 * comes back, and — through [SettingsDocumentTransport.write] — for every
 * write, the one retry a 409 can cause included, once that request carries
 * its bearer and just before it is sent. Each request can itself trigger a
 * token refresh or a full relogin, a stalled network can stretch that to
 * tens of seconds, and a relogin signs in with whatever credentials are
 * stored by the time it runs. A check made before the bearer is resolved
 * would let a push queued under one account go out with the next
 * account's token and land in its document. Mirrors iOS's reconcile, which
 * checks `isCurrent()` after every round trip for the same reason. `NotificationSettingsSync.pushNow` — the only
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
 * ([LiveActivityPreferences.hasUnconfirmedSyncEdit]). The next sign-in
 * starts the section's record from those values and runs the push through
 * [NotificationSettingsSync.pushIfUnconfirmed], so the edit gives way to the
 * account's settings and fills in only what its document lacks
 * ([LastSyncedSections]).
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
    lastSynced: JsonObject? = JsonObject(),
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
    val own = local.syncedSection()
    var conflicts = 0
    while (true) {
        // Against each document it is merged over, the 409's included: which
        // fields that document holds decides what still has to be written.
        val merged = merging(
            keepingOwnChanges(updates, LIVE_ACTIVITY_SECTION_KEY, own, lastSynced, existing),
            into = existing,
        )
        // A write that changes nothing still moves the revision, which sends
        // every other device to read the document again.
        if (merged == existing) return true
        val result = transport.write(merged, baseRevision, isCurrentGeneration)
        when (result) {
            is SettingsWriteResult.Written -> return true
            SettingsWriteResult.Abandoned -> return false
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
    // `>= 60` as well as whole-hour: 0 and negatives divide cleanly too, and a
    // foreign minute value of either shape would otherwise be mirrored into
    // reminder_offsets_hours, which is the field a pre-2.1.0 reader acts on and
    // has never carried anything below one hour. They stay in minutes, where a
    // reader that understands the newer field can see them for what they are.
    val hours = minutes.filter { it >= 60 && it % 60 == 0 }.map { it / 60 }.sortedDescending()

    val section = JsonObject()
    section.addProperty(ASSIGNMENTS_ENABLED_KEY, enabled)
    section.add(REMINDER_OFFSETS_HOURS_KEY, JsonArray().apply { hours.forEach { add(it) } })
    section.add(REMINDER_OFFSETS_MINUTES_KEY, JsonArray().apply { minutes.forEach { add(it) } })

    val root = JsonObject()
    root.add(ASSIGNMENTS_SECTION_KEY, section)
    return root
}

/** These values as the `assignments` section holds them: what [LastSyncedSections.assignments] records. */
internal fun AssignmentSyncValues.syncedSection(): JsonObject =
    documentUpdates(JsonObject()).getAsJsonObject(ASSIGNMENTS_SECTION_KEY)

/** The field an `assignments` key belongs to: the hours list is derived from the minutes and moves with it. */
private fun assignmentsField(key: String): String =
    if (key == REMINDER_OFFSETS_HOURS_KEY) REMINDER_OFFSETS_MINUTES_KEY else key

/**
 * Read-modify-write cycle for the document's `assignments` section —
 * the [pushLiveActivitySettings] of this section. Everything else in the
 * document, the other two sections and any this build has never heard of
 * included, travels back exactly as read (see [merging]). On a 409,
 * adopts the server's document and its revision and retries **exactly
 * once** — never loops. See [pushLiveActivitySettings] for why each of the
 * three gates below sits where it does, and where and why the generation is
 * re-checked.
 *
 * The update is recomputed from the freshly-read [merging] target on every
 * loop iteration (including after a conflict rebase), not built once up
 * front: the foreign-minute preservation [documentUpdates] does depends on
 * what the *current* document holds, and after a 409 that is the winner's
 * document, not the stale one this call started with. So is which fields
 * it writes: only those this device changed since [lastSynced], and those
 * the document lacks ([keepingOwnChanges]), the two offset lists counting as
 * one field. [lastSynced] and the return value are as in
 * [pushLiveActivitySettings].
 */
internal suspend fun pushAssignmentSettings(
    local: AssignmentSyncValues,
    transport: SettingsDocumentTransport,
    cloudSyncEnabled: Boolean,
    syncAssignmentReminders: Boolean,
    isLoggedIn: Boolean,
    isCurrentGeneration: () -> Boolean = { true },
    lastSynced: JsonObject? = JsonObject(),
): Boolean {
    if (!cloudSyncEnabled || !syncAssignmentReminders || !isLoggedIn) return false
    if (!isCurrentGeneration()) return false

    val current = transport.read()
    if (!isCurrentGeneration()) return false
    var existing = current?.document ?: JsonObject()
    var baseRevision = current?.revision

    val own = local.syncedSection()
    var conflicts = 0
    while (true) {
        val merged = merging(
            keepingOwnChanges(
                local.documentUpdates(existing), ASSIGNMENTS_SECTION_KEY, own, lastSynced, existing, ::assignmentsField,
            ),
            into = existing,
        )
        if (merged == existing) return true
        val result = transport.write(merged, baseRevision, isCurrentGeneration)
        when (result) {
            is SettingsWriteResult.Written -> return true
            SettingsWriteResult.Abandoned -> return false
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

// ──────────────────────────── Courses section ─────────────────────────────
//
// Class reminders: `courses.enabled` and `courses.reminder_offsets_minutes`,
// which iOS writes from its Class reminders screen and the backend reads to
// push class reminders to iPhones and iPads (`server/push/course_reminders.py`,
// `_course_prefs`). This phone posts its own reminders, so a value adopted
// here is what arms them. Same push/pull shape as `assignments`, gated on
// cloud sync and the session only: iOS has no 同步內容 switch for it either,
// and syncs it whenever cloud sync is on.
//
// As for the other sections, a pull adopts a `courses` section the document
// holds, and a push goes out only for an edit made on this device; nothing
// here seeds a missing section. So the first pull after the update that
// brought class reminders may adopt an iPhone's settings over the ones
// `ClassReminders.migrateIfNeeded` carried over from the Live Update, and
// those carried-over values never overwrite the iPhone's.

private const val COURSES_SECTION_KEY = "courses"
private const val COURSES_ENABLED_KEY = "enabled"

/**
 * The class reminder values the `courses` section carries, plus
 * [hasPickedLead], which decides whether a push writes the lead time. The
 * local half of the mapping, as [AssignmentSyncValues] is for `assignments`;
 * iOS's `isClassReminderEnabled` / `classReminderLeadMinutes` /
 * `hasPickedClassReminderLead`. Not persisted, never Gson-deserialized.
 */
internal data class ClassReminderSyncValues(
    val enabled: Boolean,
    val leadMinutes: Int,
    val hasPickedLead: Boolean,
)

/**
 * The lead time to hold for the document's `courses.reminder_offsets_minutes`
 * ([documentMinutes]), and whether it still counts as picked here. Mirrors
 * iOS's `resolveClassReminderLead`:
 *
 * - no list: the document says nothing, so nothing changes;
 * - a list: its smallest value when that is one of
 *   [ClassReminders.LEAD_CHOICES_MINUTES], the current lead time otherwise.
 *   Either way the pick is cleared, as the list is now this device's last
 *   pick or another client's choice, and a push leaves it alone.
 */
internal fun resolveClassReminderLead(
    documentMinutes: List<Int>?,
    currentMinutes: Int,
    currentPicked: Boolean,
): Pair<Int, Boolean> {
    if (documentMinutes == null) return currentMinutes to currentPicked
    val smallest = documentMinutes.minOrNull()?.takeIf { it in ClassReminders.LEAD_CHOICES_MINUTES }
    return (smallest ?: currentMinutes) to false
}

/**
 * The `courses` section update, ready to splice over [existing] via
 * [merging]: `enabled` always, and `reminder_offsets_minutes` as the one
 * lead time only when the user picked it here since the document's list was
 * adopted, or the document has no list. iOS's `coursesSection`.
 *
 * Left out otherwise, so the merge keeps the server's list, which may hold a
 * value this build has no choice for. Also left out for a lead time carried
 * over from the Live Update that is none of the choices: an iPhone would not
 * adopt it and would show a different lead time from the one the backend
 * then reminds it at.
 */
internal fun ClassReminderSyncValues.documentUpdates(existing: JsonObject): JsonObject {
    val existingOffsets = existing.get(COURSES_SECTION_KEY)
        ?.takeIf { it.isJsonObject }
        ?.asJsonObject
        ?.get(REMINDER_OFFSETS_MINUTES_KEY)
        .asValidatedIntListOrNull()

    val section = JsonObject()
    section.addProperty(COURSES_ENABLED_KEY, enabled)
    if (writesLead(listHeld = existingOffsets != null)) {
        section.add(REMINDER_OFFSETS_MINUTES_KEY, JsonArray().apply { add(leadMinutes) })
    }

    val root = JsonObject()
    root.add(COURSES_SECTION_KEY, section)
    return root
}

/** Whether a push writes the lead time over a document that holds a list ([listHeld]) or none; see [documentUpdates]. */
private fun ClassReminderSyncValues.writesLead(listHeld: Boolean): Boolean =
    (hasPickedLead || !listHeld) && leadMinutes in ClassReminders.LEAD_CHOICES_MINUTES

/**
 * These values as the `courses` section holds them: what
 * [LastSyncedSections.courses] records, and what a push compares with it
 * field by field ([keepingOwnChanges]). The lead time is the one
 * [documentUpdates] writes over a document that holds a list: the lead time
 * when it was picked here, and JSON null when it was not, which is how iOS's
 * `before` leaves the minutes out of a copy with no pick. So a lead time
 * picked after the document's list was adopted counts as changed even when it
 * is the one the screen already showed, and goes up over a list this device
 * did not pick (`[7]`, `[10, 30]`), as on iOS. Null rather than left out: a
 * field the record does not hold counts as unchanged. Over a document with no
 * list the lead time goes up regardless, as a field the document lacks.
 */
internal fun ClassReminderSyncValues.syncedSection(): JsonObject = JsonObject().apply {
    addProperty(COURSES_ENABLED_KEY, enabled)
    add(
        REMINDER_OFFSETS_MINUTES_KEY,
        if (writesLead(listHeld = true)) JsonArray().apply { add(leadMinutes) } else JsonNull.INSTANCE,
    )
}

/**
 * Read-modify-write cycle for the document's `courses` section — the
 * [pushAssignmentSettings] of this section, with the same 409 handling,
 * generation checks and field-by-field merge, and the update recomputed from
 * each document it is merged over. Gated on [cloudSyncEnabled] and
 * [isLoggedIn] only.
 */
internal suspend fun pushClassReminderSettings(
    local: ClassReminderSyncValues,
    transport: SettingsDocumentTransport,
    cloudSyncEnabled: Boolean,
    isLoggedIn: Boolean,
    isCurrentGeneration: () -> Boolean = { true },
    lastSynced: JsonObject? = JsonObject(),
): Boolean {
    if (!cloudSyncEnabled || !isLoggedIn) return false
    if (!isCurrentGeneration()) return false

    val current = transport.read()
    if (!isCurrentGeneration()) return false
    var existing = current?.document ?: JsonObject()
    var baseRevision = current?.revision

    val own = local.syncedSection()
    var conflicts = 0
    while (true) {
        val merged = merging(
            keepingOwnChanges(local.documentUpdates(existing), COURSES_SECTION_KEY, own, lastSynced, existing),
            into = existing,
        )
        if (merged == existing) return true
        val result = transport.write(merged, baseRevision, isCurrentGeneration)
        when (result) {
            is SettingsWriteResult.Written -> return true
            SettingsWriteResult.Abandoned -> return false
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
 * Read half of the `courses` section — the [pullAssignmentSettings] of this
 * section, returning the values to apply or null when there is nothing to
 * apply, for the same reasons. `enabled` keeps [current]'s value unless the
 * document holds a real boolean; the lead time goes through
 * [resolveClassReminderLead].
 */
internal suspend fun pullClassReminderSettings(
    current: ClassReminderSyncValues,
    transport: SettingsDocumentTransport,
    cloudSyncEnabled: Boolean,
    isLoggedIn: Boolean,
    isLocalDirty: () -> Boolean = { false },
    onLocalDirty: () -> Unit = {},
    isCurrentGeneration: () -> Boolean = { true },
): ClassReminderSyncValues? {
    if (!cloudSyncEnabled || !isLoggedIn) return null
    if (!isCurrentGeneration()) return null

    val section = transport.read()?.document
        ?.get(COURSES_SECTION_KEY)
        ?.takeIf { it.isJsonObject }
        ?.asJsonObject

    if (!isCurrentGeneration()) return null
    if (isLocalDirty()) {
        onLocalDirty()
        return null
    }
    if (section == null) return null

    val (leadMinutes, picked) = resolveClassReminderLead(
        documentMinutes = section.get(REMINDER_OFFSETS_MINUTES_KEY).asValidatedIntListOrNull(),
        currentMinutes = current.leadMinutes,
        currentPicked = current.hasPickedLead,
    )
    return ClassReminderSyncValues(
        enabled = section.get(COURSES_ENABLED_KEY).asValidatedBooleanOrNull() ?: current.enabled,
        leadMinutes = leadMinutes,
        hasPickedLead = picked,
    )
}

/** [pullClassReminderSettings], with a transport failure reported through [onFailure] instead of thrown. */
internal suspend fun pullClassReminderSettingsCatching(
    current: ClassReminderSyncValues,
    transport: SettingsDocumentTransport,
    cloudSyncEnabled: Boolean,
    isLoggedIn: Boolean,
    isLocalDirty: () -> Boolean = { false },
    onLocalDirty: () -> Unit = {},
    isCurrentGeneration: () -> Boolean = { true },
    onFailure: (Throwable) -> Unit = {},
): ClassReminderSyncValues? = try {
    pullClassReminderSettings(
        current,
        transport,
        cloudSyncEnabled,
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
 * an edit, or — while the edit is still unconfirmed — a sign-in, a
 * successful full sync, or a settings screen opening. Bounded so a
 * persistent failure (offline, server down) cannot retry forever.
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
 * No setting is persisted by this class, so there is no fail-closed
 * question to answer: [LiveActivityPreferences] and the two stores are the
 * source of truth and have already committed the user's choice by the time a
 * push is enqueued. The document is a copy of them, not the record of them —
 * unlike `applyOptOutIfAccepted`, where the local pref *is* the thing the
 * server has to accept first. What this class does persist is bookkeeping
 * about the sync itself: the unconfirmed-edit flags, through those stores,
 * what each section last synced ([LastSyncedSections]), and whether the
 * update's step has run ([LastSyncedSeed]).
 *
 * Every push is queued through one of two kinds of entry point, and which
 * one a caller uses is the protocol: [markUnconfirmedAndPush] /
 * [markAssignmentUnconfirmedAndPush] / [markClassReminderUnconfirmedAndPush]
 * for a change this device made, which the other devices must now see, and
 * [pushIfUnconfirmed] for everything that only wants a change still pending
 * delivered, for any section.
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
 * The class reminder values [NotificationSettingsSync] reads and writes, the
 * `courses` section's [AssignmentReminderSyncStore]. Backed by
 * [org.ntust.app.tigerduck.data.preferences.AppPreferences], whose writes to
 * [enabled] and [leadMinutes] signal LiveActivityManager to re-arm the
 * reminders, a pull's included.
 */
internal interface ClassReminderSyncStore {
    var enabled: Boolean
    var leadMinutes: Int
    var hasPickedLead: Boolean

    /** The courses-section mirror of `LiveActivityPreferences.hasUnconfirmedSyncEdit`. */
    var hasUnconfirmedEdit: Boolean
}

/**
 * Each section of the `notification` document this device owns, as it last
 * synced it: the values it last wrote there or took from there, as JSON in
 * the document's own keys ([LiveActivitySyncValues.syncedSection],
 * [AssignmentSyncValues.syncedSection], [ClassReminderSyncValues.syncedSection]),
 * or null when nothing is recorded. The base of the field-by-field merge: a
 * field whose local value still equals the recorded one is not this device's
 * change, so a push leaves the document's value alone ([keepingOwnChanges]).
 * The rules are iOS 2.9.2's:
 *
 * - A sign-in with nothing recorded starts the record from this device's
 *   values, so what was edited while signed out gives way to the account's
 *   document, and an edit made after the sign-in goes up.
 * - A push that lands records what it sent. A pull records each section it
 *   finds with no unconfirmed edit at the values it then holds.
 * - A 同步內容 switch turned back on makes that section's values the record,
 *   so what was edited while it was off gives way too. A push that was
 *   already on the wire then lands without recording.
 * - The first launch of this build, once per device ([LastSyncedSeed]) and
 *   signed in to the school, records each section with no edit pending at
 *   its values. A section with one gets [SENDS_EVERY_FIELD]: that edit was
 *   made on an earlier build, so its push sends every field once, as that
 *   build would have, and then records what it sent. Signed out, the next
 *   sign-in starts the record.
 * - A sign-out clears it, with the unconfirmed-edit flags: it describes the
 *   departing account's document.
 *
 * Backed by [org.ntust.app.tigerduck.data.preferences.AppPreferences], beside
 * the assignments and courses flags rather than in the Live Update's own
 * file, which "reset to defaults" wipes: that reset is an edit to send, and
 * it would find nothing to compare against. Plain JSON text read back by
 * [recordedSection], never bound to a class by Gson, so a field is never
 * filled in for it: a record an earlier build wrote simply lacks a field a
 * later build adds, and [keepingOwnChanges] counts a field the record lacks
 * as unchanged, so that field is not sent over another device's value. A
 * build before this one recorded none, and text that does not parse reads
 * the same way.
 */
internal interface LastSyncedSections {
    var liveActivity: String?
    var assignments: String?
    var courses: String?
}

/**
 * The record ([LastSyncedSections]) of a section whose next push sends every
 * field: an empty object, which shares no field with this device's values.
 */
internal const val SENDS_EVERY_FIELD = "{}"

/**
 * Whether this device has run the update's step: the one the first launch of
 * the build that brought [LastSyncedSections] runs, in
 * [NotificationSettingsSync]'s init. Set by that step's first run, signed in
 * or not. It belongs to the device, not to an account, so a sign-out keeps
 * it; a full reset clears the file it lives in and sets it again
 * (`FullReset`), since nothing from before the reset is left for the step to
 * read. Backed by
 * [org.ntust.app.tigerduck.data.preferences.AppPreferences], which needs a
 * real `Context`, hence an interface, as for [LastSyncedSections].
 */
internal interface LastSyncedSeed {
    var done: Boolean
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
     * Re-arms a reminder for every cached assignment still outstanding, at
     * the offsets stored at the moment it arms them.
     */
    suspend fun rescheduleFromCache()
}

@Singleton
class NotificationSettingsSync internal constructor(
    private val transport: SettingsDocumentTransport,
    private val liveActivityPreferences: LiveActivityPreferences,
    private val assignmentStore: AssignmentReminderSyncStore,
    private val assignmentAlarms: AssignmentReminderAlarms,
    private val classReminderStore: ClassReminderSyncStore,
    private val lastSynced: LastSyncedSections,
    private val lastSyncedSeed: LastSyncedSeed,
    private val cloudSyncEnabled: () -> Boolean,
    private val syncLiveActivity: () -> Boolean,
    private val syncAssignmentReminders: () -> Boolean,
    private val isLoggedIn: () -> Boolean,
    /**
     * Whether the school account is stored, which is what "signed in" means
     * to the user, whatever state the backend session is in: the update's
     * step ([LastSyncedSeed]) asks it, as iOS asks for stored credentials.
     */
    private val schoolAccountStored: () -> Boolean,
    syncLiveActivityChanged: Flow<Unit>,
    syncAssignmentRemindersChanged: Flow<Unit>,
    private val scope: CoroutineScope,
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
        credentials: CredentialManager,
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
                isCurrent: () -> Boolean,
            ): SettingsWriteResult<JsonObject> =
                client.write(NOTIFICATION_SETTINGS_NAMESPACE, document, baseRevision, JsonObject::class.java, isCurrent)
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
                scheduler.scheduleAll(
                    dataCache.loadOutstandingAssignments(),
                    appPreferences.notifyAssignmentOffsets,
                )
            }
        },
        classReminderStore = object : ClassReminderSyncStore {
            override var enabled: Boolean
                get() = appPreferences.classReminderEnabled
                set(value) { appPreferences.classReminderEnabled = value }
            override var leadMinutes: Int
                get() = appPreferences.classReminderLeadMinutes
                set(value) { appPreferences.classReminderLeadMinutes = value }
            override var hasPickedLead: Boolean
                get() = appPreferences.hasPickedClassReminderLead
                set(value) { appPreferences.hasPickedClassReminderLead = value }
            override var hasUnconfirmedEdit: Boolean
                get() = appPreferences.hasUnconfirmedClassReminderSyncEdit
                set(value) { appPreferences.hasUnconfirmedClassReminderSyncEdit = value }
        },
        lastSynced = object : LastSyncedSections {
            override var liveActivity: String?
                get() = appPreferences.lastSyncedLiveActivitySettings
                set(value) { appPreferences.lastSyncedLiveActivitySettings = value }
            override var assignments: String?
                get() = appPreferences.lastSyncedAssignmentReminderSettings
                set(value) { appPreferences.lastSyncedAssignmentReminderSettings = value }
            override var courses: String?
                get() = appPreferences.lastSyncedClassReminderSettings
                set(value) { appPreferences.lastSyncedClassReminderSettings = value }
        },
        lastSyncedSeed = object : LastSyncedSeed {
            override var done: Boolean
                get() = appPreferences.lastSyncedSettingsSeedDone
                set(value) { appPreferences.lastSyncedSettingsSeedDone = value }
        },
        cloudSyncEnabled = { appPreferences.cloudSyncEnabled },
        syncLiveActivity = { appPreferences.syncLiveActivity },
        syncAssignmentReminders = { appPreferences.syncAssignmentReminders },
        isLoggedIn = { authTokenManager.isLoggedIn },
        schoolAccountStored = { credentials.ntustStudentId != null },
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

    /**
     * The sign-in ([generation]) and the sync revision a pull after a full sync last found no
     * document at. A user who never changed a setting has none, and every full sync's pull drew a
     * 404; any device's write of the document moves the revision, so until it moves there is
     * nothing to read.
     */
    @Volatile
    private var missingAt: Pair<Int, Long>? = null

    // Held by each queued push for the whole of its read-modify-write, and
    // by pullNow() from its snapshot of the local values to its apply, so
    // neither can land in the middle of the other. Without it, an edit's
    // push could clear the unconfirmed flag while a pull's GET, sent before
    // the edit, was still on the wire, and that pull would then apply the
    // older document over the confirmed edit. iOS gets the same guarantee by
    // running its reconcile as a link on the one chain its pushes run on
    // (NotificationSettingsPushQueue.enqueueReconcile).
    private val documentLock = Mutex()

    /**
     * Keeps the writers of the records ([LastSyncedSections]) that run on
     * different threads from landing inside each other. Its holders:
     *
     * - a sign-in's start ([pushIfUnconfirmed]), from its session check to
     *   its writes;
     * - a switch restart ([restartRecord]);
     * - a push ([pushNow]), once as it reads what it sends, the record it
     *   merges against and the restart count, and again once it lands, from
     *   its account and count checks to its record write;
     * - a sign-out ([cancelPendingPushes]), for its generation bump and
     *   record clears.
     *
     * So a start or a landing push records before a sign-out clears or not
     * at all: `AuthService.logout()` wipes the session and only then comes
     * here, so a start that follows the clear finds no session, and a push
     * that follows it finds the generation moved. And a landing push never
     * records over a restart that came while it was on the wire. Guards
     * [liveActivityRestarts] and [assignmentRestarts].
     *
     * Two writers stay outside it, and are safe there:
     *
     * - A pull ([recordCleanSectionsAsSynced]) runs under [documentLock],
     *   which keeps a push out, and on `Dispatchers.Main`, with nothing
     *   suspending between its session check and its writes. A restart runs
     *   on Main too, and so does every caller of `AuthService.logout()`, so
     *   neither lands in the middle. A start on another thread only fills a
     *   record that is missing, with this device's values, which is what the
     *   pull writes as well.
     * - The update's step ([startRecordsOnUpdate]) runs in the constructor,
     *   before the queue and the switch collectors start and before anything
     *   else can reach this instance.
     *
     * Never held across a suspension, so the restart, which must not wait on
     * [documentLock] (a pull holds that for the whole of its read), waits at
     * most for a few field reads and writes. Nothing done under it takes
     * another lock: [documentLock] is a coroutine Mutex that cannot be taken
     * without suspending, `isLoggedIn` reads the token store without
     * `AuthTokenManager`'s `signInLock`, and no change listener is registered
     * on the stores it touches. Nor does anyone come here holding one:
     * `AuthService.logout()` calls in after its `accountLock` is released,
     * and every sign-in path calls in from a suspending function. The one
     * nesting is a push, or a pull leaving a section to one, taking this
     * inside [documentLock], always in that order.
     */
    private val recordLock = Any()

    /**
     * How many times a 同步內容 switch turned back on has restarted the
     * `live_activity` / `assignments` record ([restartRecord]). A push reads
     * its section's count with the record it merges against, and once it
     * lands records what it sent only if the count has not moved since. A
     * restart that came while the push was on the wire made the section's
     * values at that turn-on the record; an older push putting back what it
     * sent would make an edit made while the switch was off read as this
     * device's change, and the next push would send it instead of taking the
     * account's value. The `courses` section has no switch, so no count.
     *
     * In memory only: a count has only to outlast a push that is on the wire,
     * and a process death ends that push too, before its landing records
     * anything.
     */
    private var liveActivityRestarts = 0
    private var assignmentRestarts = 0

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

    /**
     * The sign-in ([generation]) under which [pullNow] left a section to an
     * unconfirmed edit's push, and so runs again once a push lands with
     * nothing left to send ([takeOwedPull]); null when no pull is owed.
     * Dropped at sign-out.
     */
    @Volatile
    private var owedPull: Int? = null

    init {
        startRecordsOnUpdate()
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
                        if (succeeded) {
                            consecutiveFailures = 0
                            if (takeOwedPull(queuedGeneration)) {
                                scope.launch { pullOnMain(what = "pull after a push landed") }
                            }
                        }
                    },
                    onFailure = { e ->
                        if (e is CancellationException) throw e
                        Log.w(TAG, "notification settings push failed: ${e.logSummary()}")
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
        // Turning "同步內容 → 即時更新" or "同步內容 → 作業到期提醒" back on
        // changes none of its section's values, so nothing else here would
        // notice: see restartRecord. Only the off->on transition acts, which
        // is what a change on these flows with the switch now on means, since
        // AppPreferences emits only on an actual change.
        scope.launch {
            syncLiveActivityChanged.collect {
                switchChanged(isOn = syncLiveActivity, section = "live_activity") {
                    restartRecord(liveActivity = true)
                }
            }
        }
        scope.launch {
            syncAssignmentRemindersChanged.collect {
                switchChanged(isOn = syncAssignmentReminders, section = "assignments") {
                    restartRecord(assignments = true)
                }
            }
        }
    }

    /**
     * One change of the 同步內容 switch for [section]: when [isOn] says it is
     * now on, [restart]s the section's record on `Dispatchers.Main`, where the
     * settings screens make their edits, and starts the pull that adopts the
     * section, which runs by itself.
     *
     * The pull is not waited for. A collector that waited out its read let
     * every change made meanwhile queue up behind it, and the restart of a
     * change taken that late recorded the section's values as they were by
     * then: an edit made after the switch came on was in them, so its push
     * found nothing changed, sent nothing, and the pull took the edit back.
     * Taken at once, each restart records the values of its own turn-on.
     * The pull still goes through [pullNow], under [documentLock] like every
     * other pull and push; the restart, as before, does not take that lock,
     * which a pull holds for the whole of its read. It takes [recordLock]
     * instead, which no one holds across a round trip, so a push on the wire
     * cannot put its older record back over it.
     */
    private suspend fun switchChanged(isOn: () -> Boolean, section: String, restart: () -> Unit) {
        val turnedOn = try {
            withContext(Dispatchers.Main) { isOn().also { if (it) restart() } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "restarting the $section record after its switch came on failed: ${e.logSummary()}")
            false
        }
        if (turnedOn) scope.launch { pullOnMain(what = "adopting $section after its switch came on") }
    }

    /**
     * Runs [pullNow] on `Dispatchers.Main`, which its check-and-apply
     * requires; the application scope's dispatcher is Default. A failure is
     * logged under [what] and goes no further.
     */
    private suspend fun pullOnMain(what: String) {
        try {
            withContext(Dispatchers.Main) { pullNow() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "notification settings $what failed: ${e.logSummary()}")
        }
    }

    /**
     * A 同步內容 switch turned back on takes its section from the account's
     * document before anything is sent, as iOS 2.9.2 does. This device's
     * values went stale while the switch was off, and sending them, as this
     * once did, took back every change another device made in the meantime.
     * So the section's values here, as they are when the switch comes on
     * ([switchChanged]), become its record ([LastSyncedSections]), and the
     * pull that follows adopts the section. An edit made while the
     * switch was off gives way with them; one made after it goes up. With no
     * real record (signed out, or a push an update found pending) there is
     * nothing to restart.
     *
     * An edit made while the switch was off is still unconfirmed, so that
     * pull leaves the section to its push, which now has nothing to send, and
     * runs again once it lands ([takeOwedPull]).
     *
     * A push already on the wire when the switch came on read the record
     * before this restart; when it lands, it leaves this record alone
     * ([liveActivityRestarts]).
     */
    private fun restartRecord(liveActivity: Boolean = false, assignments: Boolean = false) = synchronized(recordLock) {
        fun restartable(text: String?) = recordedSection(text)?.isEmpty == false
        if (liveActivity && restartable(lastSynced.liveActivity)) {
            liveActivityRestarts++
            lastSynced.liveActivity = liveActivityPreferences.syncSnapshot().syncedSection().toString()
        }
        if (assignments && restartable(lastSynced.assignments)) {
            assignmentRestarts++
            lastSynced.assignments = assignmentValues().syncedSection().toString()
        }
    }

    /**
     * Whether a pull is owed once the push queued under [queuedGeneration]
     * has landed, and if so takes it. [pullNow] leaves a section with an
     * unconfirmed edit to that edit's push; once a push lands with nothing
     * left to send, the pull runs again, so the device takes the section's
     * other fields from the document right after, as iOS's reconcile does
     * (`NotificationSettingsPushQueue`). Nothing is held while it waits, so an
     * edit made in the meantime is kept: it is unconfirmed again, and the
     * pull leaves it alone.
     */
    private fun takeOwedPull(queuedGeneration: Int): Boolean {
        if (owedPull != queuedGeneration || queuedGeneration != generation.get()) return false
        if (hasEditLeftToSend()) return false
        owedPull = null
        return true
    }

    /** Whether some section has an unconfirmed edit that a push could send now. */
    private fun hasEditLeftToSend(): Boolean {
        if (!cloudSyncEnabled() || !isLoggedIn()) return false
        return liveActivityPreferences.hasUnconfirmedSyncEdit && syncLiveActivity() ||
            assignmentStore.hasUnconfirmedEdit && syncAssignmentReminders() ||
            classReminderStore.hasUnconfirmedEdit
    }

    /**
     * Marks this device's Live Update values unconfirmed
     * ([LiveActivityPreferences.hasUnconfirmedSyncEdit]) and queues a push
     * of them. Returns immediately; the write happens on the application
     * scope.
     *
     * For exactly the one trigger that makes this device's values the ones
     * the user's other devices should now see: an edit to one of the five
     * synced values
     * ([org.ntust.app.tigerduck.ui.screen.settings.LiveActivitySettingsViewModel]).
     * Nothing else may call this, "同步內容 → 即時更新" turning back on
     * included: that takes the section from the document instead
     * ([restartRecord]). A marked device keeps its values through
     * every pull, so a sign-in or a sync that marked would hold this device's
     * stale values against the shared ones. Those use [pushIfUnconfirmed].
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
     * through the same channel. Called only for an edit to the master switch
     * or an offset
     * ([org.ntust.app.tigerduck.ui.screen.settings.AssignmentReminderSettingsViewModel]);
     * "同步內容 → 作業到期提醒" turning back on takes the section from the
     * document instead ([restartRecord]).
     */
    fun markAssignmentUnconfirmedAndPush() {
        assignmentStore.hasUnconfirmedEdit = true
        consecutiveFailures = 0
        pending.trySend(generation.get())
    }

    /**
     * The courses-section mirror of [markUnconfirmedAndPush]: marks
     * [ClassReminderSyncStore.hasUnconfirmedEdit] and queues a push. Called
     * only for an edit to the class reminder's switch or lead time
     * ([org.ntust.app.tigerduck.ui.screen.settings.ClassReminderSettingsViewModel]).
     */
    fun markClassReminderUnconfirmedAndPush() {
        classReminderStore.hasUnconfirmedEdit = true
        consecutiveFailures = 0
        pending.trySend(generation.get())
    }

    /**
     * Queues a push only if an earlier edit, to any of the three sections,
     * is still unconfirmed, and never marks anything: with nothing
     * pending it does nothing at all, and the next pull is free to adopt
     * what another device wrote.
     *
     * The catch-up for an edit whose push has not landed — one made while
     * cloud sync was off or the session had lapsed, or one whose retries ran
     * out. An edit made while signed out, or while its "同步內容" switch was
     * off, comes through here too, but its section's record holds it by then
     * ([LastSyncedSections]), so it gives way and only what the document
     * lacks goes up. Called from each of
     * [org.ntust.app.tigerduck.auth.AuthService]'s sign-in paths (a fresh
     * login and the automatic sign-in), after every
     * successful full sync (`HomeBackendSync.pull`), and by [pullNow] when it
     * finds an edit it must not overwrite. Signed in with nothing recorded, it
     * first starts the record, which is what makes it the sign-in's; so each
     * sign-in path calls it as soon as its backend login returns, before
     * anything else it waits for, or an edit made in between would be pushed
     * against no record and lost (`followTypedSignIn`).
     *
     * The automatic sign-in is one more reason this must never mark: a
     * lapsed refresh token or a missing session sends
     * `AuthTokenManager.authHeader()` into it from inside whatever request is
     * being built, a pull's included, and a pull whose own sign-in had marked
     * the device would skip applying the document it had just read.
     *
     * Resets [consecutiveFailures] when it queues, for the same reason
     * [markUnconfirmedAndPush] does.
     */
    fun pushIfUnconfirmed() {
        // Every sign-in path calls this once the session exists: a signed-in
        // account with nothing recorded starts its record from this device's
        // values, so an edit made while signed out gives way to the
        // account's document (LastSyncedSections). The session check and the
        // start are one step against a sign-out (recordLock), and the
        // automatic sign-in runs this off the main thread.
        synchronized(recordLock) {
            if (isLoggedIn()) startRecordsWhereMissing(sendEveryFieldOfAPendingEdit = false)
        }
        if (!liveActivityPreferences.hasUnconfirmedSyncEdit &&
            !assignmentStore.hasUnconfirmedEdit &&
            !classReminderStore.hasUnconfirmedEdit
        ) return
        consecutiveFailures = 0
        pending.trySend(generation.get())
    }

    /**
     * Drops every queued push and any pull owed after one, and clears the
     * unconfirmed-edit flags and the record of what each section last synced
     * ([LastSyncedSections]). Called
     * from `AuthService.logout()`: all of it belongs to the account that is
     * leaving, and none of it may reach whoever signs in next. The flag would
     * make that sign-in push this device's values into the new account's
     * document, and a queued push — one still in its debounce, or a retry
     * still in its backoff — would run under the new session. Mirrors iOS's
     * `cancelNotificationSettingsPushes()`.
     *
     * A push already past the loop's own generation check above is not
     * stopped here, but it still cannot land under whoever signs in next:
     * [pushLiveActivitySettings] and [pushAssignmentSettings] re-check the
     * generation this bump just changed around their read, and every write
     * re-checks it once its bearer is resolved, just before it is sent —
     * abandoning the push the instant it no longer matches, the same way
     * iOS's reconcile checks `isCurrent()` after every round trip. The
     * write's check is the one that counts: a request built after this
     * logout can still carry a token, because resolving it may run a
     * relogin with whoever's credentials are stored by then.
     *
     * A push that has already landed checks the generation once more before
     * it records what it sent, and that check and its record write are one
     * step with this bump and the record clears ([recordLock]). Without that,
     * a sign-out between the two would leave the departing account's record
     * behind for whoever signs in next. The flags stay outside the lock: a
     * landing push only ever clears them too.
     */
    fun cancelPendingPushes() {
        synchronized(recordLock) {
            generation.incrementAndGet()
            lastSynced.liveActivity = null
            lastSynced.assignments = null
            lastSynced.courses = null
        }
        liveActivityPreferences.hasUnconfirmedSyncEdit = false
        assignmentStore.hasUnconfirmedEdit = false
        classReminderStore.hasUnconfirmedEdit = false
        owedPull = null
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
     * their edits, so no edit can land between the two either — enforced by
     * [requireMainThread] rather than left to a caller to remember.
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
     * dirty flag, and one being gated off does not stop the other. They do
     * share their read — one GET answers both, through [SingleReadTransport]
     * — so a transport failure is now reported by both rather than by one,
     * which is what a single request means. Returns `true` if *either* was
     * actually attempted (matching each individual function's own
     * "attempted" contract), so a caller that only cares about "did anything
     * happen" (`LiveActivitySettingsViewModel`) keeps working unchanged.
     *
     * Called when the Live Activity settings screen opens
     * ([org.ntust.app.tigerduck.ui.screen.settings.LiveActivitySettingsViewModel]),
     * identically when the assignment-reminder settings screen opens
     * ([org.ntust.app.tigerduck.ui.screen.settings.AssignmentReminderSettingsViewModel]),
     * after every successful full sync
     * ([org.ntust.app.tigerduck.ui.screen.home.HomeBackendSync.pull], right
     * after its [pushIfUnconfirmed]), and by
     * [org.ntust.app.tigerduck.notification.BackgroundSyncWorker] after its
     * own, before it arms any reminder — so another device's change reaches
     * this device's alarms without either screen, or the app, being opened.
     * Each caller
     * additionally wraps this call itself: two independent guards against the
     * one failure mode this repo has already documented twice as a process
     * kill.
     *
     * [knownRevision] is the sync revision of the full sync this pull follows:
     * at one a pull already found no document at, it reads none ([missingAt]).
     * A settings screen's pull passes none and always reads.
     *
     * Signed in, each section with no unconfirmed edit is recorded as synced
     * ([LastSyncedSections]) before the read and again after the apply: an
     * edit made while the read is on the wire is told apart from the values
     * before it, and a later edit's push does not send back what the pull
     * applied. A section left to an unconfirmed edit's push owes a second
     * pull once that push lands ([takeOwedPull]).
     */
    suspend fun pullNow(knownRevision: Long? = null): Boolean {
        requireMainThread()
        var assignmentsChanged = false
        val attempted = documentLock.withLock {
            // The account this pull belongs to, captured alongside the local
            // snapshot below and re-checked once each read comes back. A
            // logout bumps it, so a response that arrives after one is
            // abandoned instead of applied — see pullLiveActivitySettings.
            val pulledGeneration = generation.get()
            val isCurrentGeneration = { pulledGeneration == generation.get() }
            // A section with an edit this must not overwrite is left to that
            // edit's push, and this pull runs again once it lands.
            val deferToPush = {
                owedPull = pulledGeneration
                pushIfUnconfirmed()
            }
            recordCleanSectionsAsSynced()
            // One GET for both sections; see SingleReadTransport. None at a
            // revision a pull already found no document at: any device's write
            // of one moves the revision (missingAt).
            val missingHere = knownRevision?.let { pulledGeneration to it }
            val pullTransport = SingleReadTransport(
                transport,
                knownMissing = missingHere != null && missingHere == missingAt,
                onMissing = { if (missingHere != null && isCurrentGeneration()) missingAt = missingHere },
            )
            // Snapshotted before any read goes out, not between the two: what
            // it is for is catching an edit that lands while one is on the
            // wire, and a snapshot taken after the read already contains it.
            val expected = assignmentValues()
            val expectedClassReminder = classReminderValues()

            val liveActivityAttempted = pullLiveActivitySettingsCatching(
                preferences = liveActivityPreferences,
                transport = pullTransport,
                cloudSyncEnabled = cloudSyncEnabled(),
                syncLiveActivity = syncLiveActivity(),
                isLoggedIn = isLoggedIn(),
                isLocalDirty = { liveActivityPreferences.hasUnconfirmedSyncEdit },
                onLocalDirty = deferToPush,
                isCurrentGeneration = isCurrentGeneration,
                onFailure = { e -> Log.w(TAG, "pulling live_activity settings failed: ${e.logSummary()}") },
            )

            val cloudSync = cloudSyncEnabled()
            val assignmentsSynced = syncAssignmentReminders()
            val loggedIn = isLoggedIn()
            var assignmentReadFailed = false
            val pulled = pullAssignmentSettingsCatching(
                current = expected,
                transport = pullTransport,
                cloudSyncEnabled = cloudSync,
                syncAssignmentReminders = assignmentsSynced,
                isLoggedIn = loggedIn,
                isLocalDirty = { assignmentStore.hasUnconfirmedEdit || assignmentValues() != expected },
                onLocalDirty = deferToPush,
                isCurrentGeneration = isCurrentGeneration,
                onFailure = { e ->
                    assignmentReadFailed = true
                    Log.w(TAG, "pulling assignment settings failed: ${e.logSummary()}")
                },
            )
            if (pulled != null && pulled != expected) {
                if (pulled.enabled != expected.enabled) assignmentStore.enabled = pulled.enabled
                if (pulled.offsets != expected.offsets) assignmentStore.offsets = pulled.offsets
                assignmentsChanged = true
            }

            // Class reminders: the same guards as assignments. What is
            // applied re-arms the reminders through AppPreferences' change
            // signal, which LiveActivityManager follows.
            var classReminderReadFailed = false
            val pulledClassReminder = pullClassReminderSettingsCatching(
                current = expectedClassReminder,
                transport = pullTransport,
                cloudSyncEnabled = cloudSync,
                isLoggedIn = loggedIn,
                isLocalDirty = {
                    classReminderStore.hasUnconfirmedEdit || classReminderValues() != expectedClassReminder
                },
                onLocalDirty = deferToPush,
                isCurrentGeneration = isCurrentGeneration,
                onFailure = { e ->
                    classReminderReadFailed = true
                    Log.w(TAG, "pulling class reminder settings failed: ${e.logSummary()}")
                },
            )
            if (pulledClassReminder != null && pulledClassReminder != expectedClassReminder) {
                classReminderStore.enabled = pulledClassReminder.enabled
                classReminderStore.leadMinutes = pulledClassReminder.leadMinutes
                classReminderStore.hasPickedLead = pulledClassReminder.hasPickedLead
            }
            val classReminderAttempted =
                cloudSync && loggedIn && !classReminderReadFailed && isCurrentGeneration()

            // A response the account guard abandoned is not an applied read,
            // whatever the gates said when it went out — and the alarm work
            // below hangs off assignmentsChanged, which that abandon leaves
            // false, so a logout's cancelled reminders stay cancelled.
            val assignmentsAttempted =
                cloudSync && assignmentsSynced && loggedIn && !assignmentReadFailed && isCurrentGeneration()
            if (isCurrentGeneration()) recordCleanSectionsAsSynced()
            liveActivityAttempted || assignmentsAttempted || classReminderAttempted
        }

        // Outside the lock: local work that no push has to wait for.
        if (assignmentsChanged) {
            if (assignmentStore.enabled) assignmentAlarms.rescheduleFromCache() else assignmentAlarms.cancelAll()
        }
        return attempted
    }

    private fun assignmentValues() =
        AssignmentSyncValues(enabled = assignmentStore.enabled, offsets = assignmentStore.offsets)

    private fun classReminderValues() = ClassReminderSyncValues(
        enabled = classReminderStore.enabled,
        leadMinutes = classReminderStore.leadMinutes,
        hasPickedLead = classReminderStore.hasPickedLead,
    )

    /**
     * Records each section with no unconfirmed edit as synced at the values
     * it holds now ([LastSyncedSections]): with no edit of its own pending,
     * none of them is this device's change to send. A section with one keeps
     * the record from before that edit, which is what tells the edit apart.
     * Signed out there is no record to keep: the next sign-in starts it.
     * Called by [pullNow] under [documentLock].
     */
    private fun recordCleanSectionsAsSynced() {
        if (!isLoggedIn()) return
        if (!liveActivityPreferences.hasUnconfirmedSyncEdit) {
            lastSynced.liveActivity = liveActivityPreferences.syncSnapshot().syncedSection().toString()
        }
        if (!assignmentStore.hasUnconfirmedEdit) lastSynced.assignments = assignmentValues().syncedSection().toString()
        if (!classReminderStore.hasUnconfirmedEdit) lastSynced.courses = classReminderValues().syncedSection().toString()
    }

    /**
     * The step the first launch of the build that brought the field-by-field
     * merge runs, once per device ([LastSyncedSeed]): an earlier build kept no
     * record. Signed in, each section with no edit pending is recorded at its
     * values, and one with an edit pending gets [SENDS_EVERY_FIELD]; signed
     * out, nothing is recorded, and the next sign-in starts the record. See
     * [LastSyncedSections].
     *
     * Once, because "an edit is pending and nothing is recorded" means "made
     * on an earlier build" only on that launch. On any later one it is a
     * record that went missing or stopped parsing, and sending every field
     * then would take back what another device changed since.
     *
     * Signed in means the school account is stored, as iOS asks for stored
     * credentials, not that the backend session is: a session that lapsed
     * before the update is the automatic sign-in's to renew, and the edit the
     * earlier build left pending is still this device's. Android never runs
     * before the first unlock (no component is directBootAware), so what is
     * stored always reads back here.
     */
    private fun startRecordsOnUpdate() {
        if (lastSyncedSeed.done) return
        if (schoolAccountStored()) startRecordsWhereMissing(sendEveryFieldOfAPendingEdit = true)
        lastSyncedSeed.done = true
    }

    /**
     * Gives each section with no record (none stored, or one that does not
     * parse) one at its values now; see [LastSyncedSections]. With
     * [sendEveryFieldOfAPendingEdit], the update's step, a section with an
     * unconfirmed edit gets [SENDS_EVERY_FIELD] instead. The caller has
     * checked that an account is signed in, and holds [recordLock] unless it
     * is the update's step, in the constructor.
     *
     * A record that exists is not touched at all: written back, it would take
     * away whatever replaced it since the read here, a restart's included.
     */
    private fun startRecordsWhereMissing(sendEveryFieldOfAPendingEdit: Boolean) {
        fun started(text: String?, pending: Boolean, values: () -> JsonObject): String? = when {
            recordedSection(text) != null -> null
            sendEveryFieldOfAPendingEdit && pending -> SENDS_EVERY_FIELD
            else -> values().toString()
        }
        started(lastSynced.liveActivity, liveActivityPreferences.hasUnconfirmedSyncEdit) {
            liveActivityPreferences.syncSnapshot().syncedSection()
        }?.let { lastSynced.liveActivity = it }
        started(lastSynced.assignments, assignmentStore.hasUnconfirmedEdit) {
            assignmentValues().syncedSection()
        }?.let { lastSynced.assignments = it }
        started(lastSynced.courses, classReminderStore.hasUnconfirmedEdit) {
            classReminderValues().syncedSection()
        }?.let { lastSynced.courses = it }
    }

    /**
     * Fails loudly, in debug builds, if [pullNow] runs anywhere but the main
     * thread.
     *
     * [pullNow]'s dirty check and the writes that follow it have no
     * suspension point between them, which makes them atomic against
     * anything else on the *same* thread — and both settings screens make
     * their edits on the main thread. Against a second thread they are not
     * atomic at all, and [documentLock] does not help: a queued push takes
     * it, a user's tap does not. Every call site switches to
     * `Dispatchers.Main` first, `BackgroundSyncWorker`'s included; one that
     * forgot to — a worker runs on its own executor, and the application
     * scope's dispatcher is `Default` — would silently reopen that window. Hence a check rather than the KDoc
     * line this used to be.
     *
     * Debug only — a release build degrades rather than crashing over a
     * settings sync. This module's plain-JVM unit tests have no Looper at
     * all (`testOptions.unitTests.isReturnDefaultValues`), so the check
     * stands down there; they drive [pullNow] from a single test thread,
     * which is the property being protected.
     */
    private fun requireMainThread() {
        if (!BuildConfig.DEBUG) return
        val mainLooper = Looper.getMainLooper() ?: return
        check(Looper.myLooper() === mainLooper) {
            "pullNow() must run on the main thread, not ${Thread.currentThread().name}"
        }
    }

    /**
     * Pushes whichever of the three sections this class owns is actually
     * unconfirmed, sequentially under the one queued trigger: live_activity
     * first (unchanged from before assignments existed), then assignments,
     * then courses. Sequential, not concurrent, so each push's own read sees
     * whatever the one before it just wrote rather than racing it into an
     * avoidable 409.
     *
     * Each is gated on its own unconfirmed-edit flag *in addition to* its
     * own gates: every path that enqueues a push — [markUnconfirmedAndPush],
     * [markAssignmentUnconfirmedAndPush], [markClassReminderUnconfirmedAndPush],
     * [pushIfUnconfirmed] and the failure handler's own re-`trySend` — sets
     * or requires at least one of the three flags first, so this never skips
     * a push that was actually asked for. A 同步內容 switch turned back on
     * queues none: it pulls instead ([restartRecord]). What it does prevent:
     * one section's trigger (an assignments edit, say) re-sending another
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
     * Each writes only the fields of its section this device changed since
     * [LastSyncedSections] last recorded it ([keepingOwnChanges]), and none
     * at all when the document already holds them; a push that lands records
     * the values it sent, under the account it was queued for. An edit made
     * while it was on the wire then differs from that record, and its own
     * push sends it. A switch turned back on while it was on the wire
     * restarted the record ([restartRecord]), and the push leaves that newer
     * record in place: it reads what it sends, the record it merges against
     * and its section's restart count together, and records only if the count
     * has not moved, both under [recordLock], so no restart lands between
     * those reads or between the check and the write. Nor does a sign-out:
     * its bump and clears take the same lock, in every section, courses
     * included.
     *
     * Returns `true` if any section's push landed (a push whose change the
     * document already held counts as landed), so the retry loop above only
     * treats "no section was dirty, or every dirty section's gate was
     * closed" as the no-op case. A section that throws propagates at once,
     * and the sections after it are not attempted that pass; the whole call
     * is retried together on the next attempt, exactly as a single-section
     * push already was.
     */
    private suspend fun pushNow(queuedGeneration: Int): Boolean {
        var liveActivitySucceeded = false
        if (liveActivityPreferences.hasUnconfirmedSyncEdit) {
            val (sentLiveActivity, liveActivityBase, liveActivityRestartsAtBase) = synchronized(recordLock) {
                Triple(liveActivityPreferences.syncSnapshot(), recordedSection(lastSynced.liveActivity), liveActivityRestarts)
            }
            liveActivitySucceeded = pushLiveActivitySettings(
                local = sentLiveActivity,
                transport = transport,
                cloudSyncEnabled = cloudSyncEnabled(),
                syncLiveActivity = syncLiveActivity(),
                isLoggedIn = isLoggedIn(),
                isCurrentGeneration = { queuedGeneration == generation.get() },
                lastSynced = liveActivityBase,
            )
            if (liveActivitySucceeded) synchronized(recordLock) {
                if (queuedGeneration == generation.get() && liveActivityRestarts == liveActivityRestartsAtBase) {
                    lastSynced.liveActivity = sentLiveActivity.syncedSection().toString()
                }
            }
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
            val (sentAssignments, assignmentsBase, assignmentRestartsAtBase) = synchronized(recordLock) {
                Triple(assignmentValues(), recordedSection(lastSynced.assignments), assignmentRestarts)
            }
            assignmentsSucceeded = pushAssignmentSettings(
                local = sentAssignments,
                transport = transport,
                cloudSyncEnabled = cloudSyncEnabled(),
                syncAssignmentReminders = syncAssignmentReminders(),
                isLoggedIn = isLoggedIn(),
                isCurrentGeneration = { queuedGeneration == generation.get() },
                lastSynced = assignmentsBase,
            )
            if (assignmentsSucceeded) synchronized(recordLock) {
                if (queuedGeneration == generation.get() && assignmentRestarts == assignmentRestartsAtBase) {
                    lastSynced.assignments = sentAssignments.syncedSection().toString()
                }
            }
            val currentAssignments =
                AssignmentSyncValues(enabled = assignmentStore.enabled, offsets = assignmentStore.offsets)
            if (assignmentsSucceeded && currentAssignments == sentAssignments) {
                assignmentStore.hasUnconfirmedEdit = false
            }
        }

        var classReminderSucceeded = false
        if (classReminderStore.hasUnconfirmedEdit) {
            val sentClassReminder = classReminderValues()
            classReminderSucceeded = pushClassReminderSettings(
                local = sentClassReminder,
                transport = transport,
                cloudSyncEnabled = cloudSyncEnabled(),
                isLoggedIn = isLoggedIn(),
                isCurrentGeneration = { queuedGeneration == generation.get() },
                lastSynced = recordedSection(lastSynced.courses),
            )
            // No switch restarts this section, but a sign-out can clear its
            // record, so the check and the write are one step all the same.
            if (classReminderSucceeded) synchronized(recordLock) {
                if (queuedGeneration == generation.get()) {
                    lastSynced.courses = sentClassReminder.syncedSection().toString()
                }
            }
            if (classReminderSucceeded && classReminderValues() == sentClassReminder) {
                classReminderStore.hasUnconfirmedEdit = false
            }
        }

        return liveActivitySucceeded || assignmentsSucceeded || classReminderSucceeded
    }

    private companion object {
        const val TAG = "Push.NotifSettings"
        const val DEBOUNCE_MS = 250L
    }
}
