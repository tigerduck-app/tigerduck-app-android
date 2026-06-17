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
    @SerializedName("device_name") val deviceName: String? = null,
    @SerializedName("app_version") val appVersion: String? = null,
    @SerializedName("os_version") val osVersion: String? = null,
    @SerializedName("push_token") val pushToken: PushTokenIn? = null,
)

data class DeviceRegisterResponse(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("push_token_id") val pushTokenId: Int?,
)

data class UpdateDevicePreferencesRequest(
    @SerializedName("server_push_enabled") val serverPushEnabled: Boolean,
)

data class DevicePreferencesResponse(
    @SerializedName("device_id") val deviceId: String? = null,
    @SerializedName("server_push_enabled") val serverPushEnabled: Boolean,
)
