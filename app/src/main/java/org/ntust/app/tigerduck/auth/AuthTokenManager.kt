package org.ntust.app.tigerduck.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.data.preferences.CredentialManager

/**
 * Manages v3 JWT access and refresh tokens.
 * Tokens are stored in EncryptedSharedPreferences via CredentialManager.
 */
class AuthTokenManager(
    private val credentials: CredentialManager,
    private val httpClient: OkHttpClient,
    private val baseUrl: String,
    private val deviceUuid: String,
) {
    private val refreshMutex = Mutex()
    private val jsonType = "application/json".toMediaType()

    val isLoggedIn: Boolean get() = credentials.v3RefreshToken != null

    suspend fun authHeader(): String? {
        val access = validAccessToken() ?: return null
        return "Bearer $access"
    }

    suspend fun login(
        studentId: String,
        password: String,
        moodleToken: String?,
        moodlePrivateToken: String?,
        platform: String = "android",
        deviceName: String,
    ): LoginResult = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("student_id", studentId)
            put("password", password)
            put("moodle_token", moodleToken)
            put("moodle_private_token", moodlePrivateToken)
            put("device_info", JSONObject().apply {
                put("client_device_id", deviceUuid)
                put("platform", platform)
                put("device_name", deviceName)
                put("app_version", BuildConfig.VERSION_NAME)
                put("os_version", "Android ${android.os.Build.VERSION.RELEASE}")
            })
        }
        val body = json.toString().toRequestBody(jsonType)
        val request = Request.Builder()
            .url("$baseUrl/auth/login")
            .post(body)
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw AuthException("login failed: ${response.code}")
            val result = JSONObject(response.body.string())
            val accessToken = result.getString("access_token")
            val refreshToken = result.getString("refresh_token")
            val expiresIn = result.getInt("expires_in")
            credentials.v3AccessToken = accessToken
            credentials.v3RefreshToken = refreshToken
            credentials.v3TokenExpiresAt = System.currentTimeMillis() + expiresIn * 1000L
            LoginResult(
                accessToken = accessToken,
                deviceId = result.getString("device_id"),
            )
        }
    }

    fun logout() {
        credentials.v3AccessToken = null
        credentials.v3RefreshToken = null
        credentials.v3TokenExpiresAt = 0L
    }

    private suspend fun validAccessToken(): String? {
        val access = credentials.v3AccessToken ?: return null
        val expiresAt = credentials.v3TokenExpiresAt
        if (System.currentTimeMillis() < expiresAt - 30_000) return access
        return refresh()
    }

    private suspend fun refresh(): String? = refreshMutex.withLock {
        // Double-check after acquiring lock
        val access = credentials.v3AccessToken
        val expiresAt = credentials.v3TokenExpiresAt
        if (access != null && System.currentTimeMillis() < expiresAt - 30_000) return access

        val refreshToken = credentials.v3RefreshToken ?: return null
        val json = JSONObject().apply {
            put("refresh_token", refreshToken)
        }
        val body = json.toString().toRequestBody(jsonType)
        val request = Request.Builder()
            .url("$baseUrl/auth/refresh")
            .post(body)
            .build()
        return try {
            withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        logout()
                        return@use null
                    }
                    val result = JSONObject(response.body.string())
                    val newAccess = result.getString("access_token")
                    val newRefresh = result.getString("refresh_token")
                    val expiresIn = result.getInt("expires_in")
                    credentials.v3AccessToken = newAccess
                    credentials.v3RefreshToken = newRefresh
                    credentials.v3TokenExpiresAt = System.currentTimeMillis() + expiresIn * 1000L
                    newAccess
                }
            }
        } catch (e: Exception) {
            logout()
            null
        }
    }

    data class LoginResult(val accessToken: String, val deviceId: String)
    class AuthException(message: String) : Exception(message)
}
