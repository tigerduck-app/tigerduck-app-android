package org.ntust.app.tigerduck.push

import com.google.gson.annotations.SerializedName

// The `notification` settings document — GET/PUT /v3/settings/notification.
// Shape fixed by the v2.1.0 sync-notifications design spec §4.6; do not add
// or remove fields without updating the spec and the backend's validation.
//
// @SerializedName is load-bearing on every field, same as PushApiModels.kt:
// `push` has no R8 keep rule, so an unannotated field gets renamed in
// release builds and Gson silently leaves it null after deserialization
// (see CLAUDE.md / the upgrade-safe-persistence skill).
//
// Every section, and every field inside `LiveActivitySection`, is nullable.
// `live_activity` is new in v2.1.0 — every document written before it exists
// lacks the key entirely, so a non-null type here (even with a Kotlin
// default) would read back null anyway via Gson's Unsafe-allocate path and
// crash the first caller that assumes otherwise. `courses` is owned by
// iOS/the backend (§4.2) — Android only round-trips it so a write here
// never drops what another platform wrote — so it gets the same treatment
// rather than an assumed-present non-null type.

/**
 * The `notification` settings document.
 *
 * Android mutates [assignments] and [liveActivity] (each gated on its own
 * "同步內容" switch, `AppPreferences.syncAssignmentReminders` /
 * `syncLiveActivity`, plus `cloudSyncEnabled`); [courses] is read back and
 * re-sent unchanged so a write from this client never clobbers what
 * iOS/the backend wrote there. See `NotificationSettingsSync` for the
 * read-modify-write cycle that keeps every section this client does not
 * own — [courses], and any key a newer build of either platform adds —
 * untouched.
 */
data class NotificationSettingsDocument(
    @SerializedName("assignments") val assignments: AssignmentsSection? = null,
    @SerializedName("courses") val courses: CoursesSection? = null,
    @SerializedName("live_activity") val liveActivity: LiveActivitySection? = null,
)

/**
 * Assignment due-date reminders (§4.2), shared with iOS/iPadOS.
 *
 * [reminderOffsetsMinutes] is the lossless, authoritative mirror of the
 * selected offsets (sub-hour ones included) — a reader that knows this key
 * prefers it over [reminderOffsetsHours] whenever it is a list, including an
 * empty one (`server/push/reminders.py:_offsets_hours`). Without this field,
 * an Android write that only knew [reminderOffsetsHours] would replace the
 * whole `assignments` section update it sends with the hours-only shape,
 * losing the sub-hour precision an iPhone wrote. [reminderOffsetsHours]
 * stays for readers older than this field.
 */
data class AssignmentsSection(
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("reminder_offsets_hours") val reminderOffsetsHours: List<Int>? = null,
    @SerializedName("reminder_offsets_minutes") val reminderOffsetsMinutes: List<Int>? = null,
)

/** Course-start reminders. Android never writes this section (§4.2). */
data class CoursesSection(
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("reminder_offsets_minutes") val reminderOffsetsMinutes: List<Int>? = null,
)

/**
 * Live Activity / Live Updates preferences, shared across the user's
 * devices — distinct from the device-level `UserDevice.sync_live_activity`
 * opt-in. New in v2.1.0; absent from every document written before it.
 */
data class LiveActivitySection(
    @SerializedName("show_class_preparing") val showClassPreparing: Boolean? = null,
    @SerializedName("show_in_class") val showInClass: Boolean? = null,
    @SerializedName("show_assignment") val showAssignment: Boolean? = null,
    @SerializedName("class_preparing_lead_seconds") val classPreparingLeadSeconds: Int? = null,
    @SerializedName("assignment_lead_seconds") val assignmentLeadSeconds: Int? = null,
)
