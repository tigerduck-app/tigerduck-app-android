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
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint

/**
 * Manages v3 JWT access and refresh tokens.
 * Tokens are stored in EncryptedSharedPreferences via CredentialManager.
 */
class AuthTokenManager(
    private val credentials: CredentialManager,
    private val httpClient: OkHttpClient,
    private val prefs: AppPreferences,
    private val deviceUuid: String,
) {
    private val refreshMutex = Mutex()
    private val jsonType = "application/json".toMediaType()
    var onRefreshFailed: (suspend () -> Boolean)? = null

    // Resolve per-call so the debug API-endpoint override applies immediately,
    // matching PushApiClient — pinning at construction made login/refresh
    // traffic ignore an override that push traffic honored, minting tokens on
    // one host and presenting them to another.
    private val baseUrl: String
        get() = resolveAnnouncementEndpoint(prefs).url.trimEnd('/')

    val isLoggedIn: Boolean get() = credentials.v3RefreshToken != null

    suspend fun authHeader(): String? {
        val access = validAccessToken() ?: return null
        return "Bearer $access"
    }

    /**
     * Non-suspending snapshot of the current bearer header, or null if no
     * access token is stored. Does NOT trigger a refresh — intended for
     * capturing the header before [logout] wipes the tokens, so a fire-and-
     * forget device-unregister DELETE can still authenticate.
     */
    fun currentAuthHeader(): String? =
        credentials.v3AccessToken?.let { "Bearer $it" }

    suspend fun login(
        studentId: String,
        password: String,
        moodleToken: String?,
        moodlePrivateToken: String?,
        platform: String = "android",
    ): LoginResult = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("student_id", studentId)
            put("password", password)
            put("moodle_token", moodleToken)
            put("moodle_private_token", moodlePrivateToken)
            put("device_info", JSONObject().apply {
                put("client_device_id", deviceUuid)
                put("platform", platform)
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
                        if (onRefreshFailed?.invoke() == true) {
                            return@use credentials.v3AccessToken
                        }
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
            if (onRefreshFailed?.invoke() == true) {
                return credentials.v3AccessToken
            }
            logout()
            null
        }
    }

    data class LoginResult(val accessToken: String, val deviceId: String)
    class AuthException(message: String) : Exception(message)
}
