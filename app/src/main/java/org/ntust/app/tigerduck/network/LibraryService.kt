package org.ntust.app.tigerduck.network

import com.google.gson.Gson
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.network.model.LibraryLoginRequest
import org.ntust.app.tigerduck.network.model.LibraryLoginResponse
import org.ntust.app.tigerduck.network.model.LibraryQRRequest
import org.ntust.app.tigerduck.network.model.LibraryQRResponse
import android.util.Log
import org.ntust.app.tigerduck.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import javax.inject.Inject
import javax.inject.Singleton

sealed class LibraryServiceError : Exception() {
    object CredentialsNotFound : LibraryServiceError()
    data class LoginFailed(val msg: String) : LibraryServiceError()
    data class QRGenerationFailed(val msg: String) : LibraryServiceError()
}

@Singleton
class LibraryService @Inject constructor(
    private val credentials: CredentialManager
) {
    private val baseUrl = "https://api.lib.ntust.edu.tw/v1"
    private val loggingInterceptor = HttpLoggingInterceptor { message ->
        Log.d("TigerDuck-HTTP", message)
    }.apply {
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.HEADERS
                else HttpLoggingInterceptor.Level.NONE
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .build()
    private val gson = Gson()
    private val tokenMutex = Mutex()

    suspend fun login(username: String, password: String): String = tokenMutex.withLock {
        loginInternal(username, password)
    }

    private suspend fun loginInternal(username: String, password: String): String = withContext(Dispatchers.IO) {
        val body = gson.toJson(LibraryLoginRequest(username, password))
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$baseUrl/passport/login")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()
                ?: throw LibraryServiceError.LoginFailed("無回應")
            val loginResponse = gson.fromJson(responseBody, LibraryLoginResponse::class.java)

            if (loginResponse.error?.code != 0 || loginResponse.data == null) {
                throw LibraryServiceError.LoginFailed(loginResponse.error?.message ?: "未知錯誤")
            }

            credentials.libraryUsername = username
            credentials.libraryPassword = password
            credentials.libraryToken = loginResponse.data.token
            credentials.libraryTokenExpiry = loginResponse.data.expirationTimeStamp

            loginResponse.data.token
        }
    }

    suspend fun ensureToken(): String = tokenMutex.withLock {
        val token = credentials.libraryToken
        if (token != null && credentials.isLibraryTokenValid) return@withLock token

        val username = credentials.libraryUsername
            ?: throw LibraryServiceError.CredentialsNotFound
        val password = credentials.libraryPassword
            ?: throw LibraryServiceError.CredentialsNotFound

        loginInternal(username, password)
    }

    suspend fun generateQRCode(): String = withContext(Dispatchers.IO) {
        val token = ensureToken()
        val body = gson.toJson(LibraryQRRequest(token))
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$baseUrl/virtual-code/generate")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()
                ?: throw LibraryServiceError.QRGenerationFailed("無回應")
            val qrResponse = gson.fromJson(responseBody, LibraryQRResponse::class.java)

            if (qrResponse.error?.code != 0 || qrResponse.data == null) {
                throw LibraryServiceError.QRGenerationFailed(qrResponse.error?.message ?: "未知錯誤")
            }
            qrResponse.data
        }
    }
}
