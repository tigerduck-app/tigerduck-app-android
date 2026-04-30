package org.ntust.app.tigerduck.push

import com.google.gson.annotations.SerializedName

data class DeviceRegisterRequest(
    @SerializedName("user_id") val userId: String,
    @SerializedName("device_id") val deviceId: String,
    val platform: String = "android",
    @SerializedName("pts_token_hex") val ptsTokenHex: String,
    @SerializedName("device_token_hex") val deviceTokenHex: String? = null,
    @SerializedName("bundle_id") val bundleId: String = "org.ntust.app.tigerduck",
)

data class DeviceRegisterResponse(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("user_id") val userId: String,
    val platform: String,
    @SerializedName("registered_at") val registeredAt: String?,
)

data class DeviceUnregisterRequest(
    @SerializedName("device_id") val deviceId: String,
)
