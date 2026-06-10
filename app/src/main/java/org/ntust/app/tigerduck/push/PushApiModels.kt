package org.ntust.app.tigerduck.push

import com.google.gson.annotations.SerializedName

// Backend stores the push token in `pts_token_hex` for both iOS (APNs) and
// Android (FCM); the column name predates Android support but is platform-
// agnostic in practice (see DEBUG.md verification query).
data class DeviceRegisterRequest(
    @SerializedName("user_id") val userId: String,
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("platform") val platform: String = "android",
    @SerializedName("pts_token_hex") val ptsTokenHex: String,
    @SerializedName("bundle_id") val bundleId: String = "org.ntust.app.tigerduck",
    @SerializedName("device_class") val deviceClass: String = "android",
    @SerializedName("server_push_enabled") val serverPushEnabled: Boolean = true,
)

data class DeviceRegisterResponse(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("user_id") val userId: String,
    // @SerializedName is load-bearing on every field here: these classes
    // have no R8 keep rule, so an unannotated field gets renamed in release
    // builds and Gson silently leaves it null after deserialization.
    @SerializedName("platform") val platform: String?,
    @SerializedName("registered_at") val registeredAt: String?,
)

data class DeviceUnregisterRequest(
    @SerializedName("device_id") val deviceId: String,
)

data class UpdateDevicePreferencesRequest(
    @SerializedName("server_push_enabled") val serverPushEnabled: Boolean,
)

data class DevicePreferencesResponse(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("server_push_enabled") val serverPushEnabled: Boolean,
)
