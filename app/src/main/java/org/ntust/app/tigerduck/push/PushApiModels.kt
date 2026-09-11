package org.ntust.app.tigerduck.push

import com.google.gson.annotations.SerializedName

// v3 device registration DTOs.
// @SerializedName is load-bearing on every field: these classes have no R8
// keep rule, so an unannotated field gets renamed in release builds and Gson
// silently leaves it null after deserialization.

data class PushTokenIn(
    @SerializedName("provider") val provider: String = "fcm",
    @SerializedName("token_kind") val tokenKind: String = "standard",
    @SerializedName("token_value") val tokenValue: String,
    @SerializedName("bundle_id") val bundleId: String = "org.ntust.app.tigerduck",
    @SerializedName("scope_key") val scopeKey: String = "",
)

data class DeviceRegisterRequest(
    @SerializedName("client_device_id") val clientDeviceId: String,
    @SerializedName("platform") val platform: String = "android",
    /**
     * Form factor, for operator targeting — see [PushIdentity.deviceClass].
     * `platform` is "android" for phones and tablets alike, so the
     * distinction has to ride here.
     */
    @SerializedName("device_class") val deviceClass: String? = null,
    @SerializedName("app_version") val appVersion: String? = null,
    @SerializedName("os_version") val osVersion: String? = null,
    @SerializedName("push_token") val pushToken: PushTokenIn? = null,
    @SerializedName("cloud_sync_enabled") val cloudSyncEnabled: Boolean? = null,
    /**
     * BCP-47 tag for the language the app is actually rendering, so the
     * server can compose push copy in it. Sent unconditionally — a device
     * fact, not a preference.
     */
    @SerializedName("locale") val locale: String? = null,
)

data class DeviceRegisterResponse(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("push_token_id") val pushTokenId: Int?,
)

/**
 * Device-only registration for an app that has never been signed in.
 *
 * Carries no account, just enough for an operator to see that an Android
 * device is running the app and to send it a custom push. Every field is
 * @SerializedName'd because this class is Gson-serialised and the `push`
 * package has no R8 keep rule — see the upgrade-safe-persistence skill.
 */
data class AnonymousDeviceRequest(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("platform") val platform: String = "android",
    @SerializedName("device_class") val deviceClass: String,
    @SerializedName("push_token") val pushToken: String? = null,
    @SerializedName("bundle_id") val bundleId: String = "",
    /**
     * The signed-out half of the server-push opt-out.
     *
     * `PATCH /devices/{id}/preferences` needs a session and writes
     * `user_devices`, but operator targeting resolves signed-out devices
     * from `device_registrations` — so without this the toggle had no way
     * to reach the row that actually decides, and a device that opted out
     * kept receiving custom push.
     */
    @SerializedName("server_push_enabled") val serverPushEnabled: Boolean? = null,
)

/**
 * "Keep reminding me about classes on this holiday."
 *
 * Only sent when cloud sync is on — the guard itself works without it, and
 * a device with sync off keeps the choice locally.
 */
data class HolidayOverrideRequest(
    @SerializedName("notify") val notify: Boolean,
)

data class UpdateDevicePreferencesRequest(
    @SerializedName("server_push_enabled") val serverPushEnabled: Boolean? = null,
    @SerializedName("sync_courses") val syncCourses: Boolean? = null,
    @SerializedName("sync_course_colors") val syncCourseColors: Boolean? = null,
    @SerializedName("sync_course_names") val syncCourseNames: Boolean? = null,
    @SerializedName("sync_assignments") val syncAssignments: Boolean? = null,
    @SerializedName("cloud_sync_enabled") val cloudSyncEnabled: Boolean? = null,
    /**
     * BCP-47 tag for the language the in-app picker just switched to, sent
     * so server-composed push copy (the Moodle-reauth notification most of
     * all) stops arriving in the language the device happened to register
     * with. `@SerializedName` is mandatory here, same as every other field
     * in this file: `push` has no R8 keep rule, so an unannotated field
     * would serialize under a renamed key in release builds and the
     * backend would never see it. The backend applies `locale` only when
     * non-null (`server/routes/user_devices.py`), so every other PATCH
     * that omits it — every call site but the one that sends this — leaves
     * a previously-set value alone.
     */
    @SerializedName("locale") val locale: String? = null,
)

data class DevicePreferencesResponse(
    @SerializedName("device_id") val deviceId: String? = null,
    @SerializedName("server_push_enabled") val serverPushEnabled: Boolean = true,
    @SerializedName("sync_courses") val syncCourses: Boolean = true,
    @SerializedName("sync_course_colors") val syncCourseColors: Boolean = true,
    @SerializedName("sync_course_names") val syncCourseNames: Boolean = true,
    @SerializedName("sync_assignments") val syncAssignments: Boolean = true,
    @SerializedName("cloud_sync_enabled") val cloudSyncEnabled: Boolean = true,
)
