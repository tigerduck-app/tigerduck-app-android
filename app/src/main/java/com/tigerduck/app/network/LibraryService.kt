package com.tigerduck.app.network

import com.google.gson.Gson
import com.tigerduck.app.data.preferences.CredentialManager
import com.tigerduck.app.network.model.LibraryLoginRequest
import com.tigerduck.app.network.model.LibraryLoginResponse
import com.tigerduck.app.network.model.LibraryQRRequest
import com.tigerduck.app.network.model.LibraryQRResponse
import android.util.Log
import kotlinx.coroutines.Dispatchers
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
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .build()
    private val gson = Gson()

    suspend fun login(username: String, password: String): String = withContext(Dispatchers.IO) {
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

            if (loginResponse.error.code != 0 || loginResponse.data == null) {
                throw LibraryServiceError.LoginFailed(loginResponse.error.message)
            }

            credentials.libraryUsername = username
            credentials.libraryPassword = password
            credentials.libraryToken = loginResponse.data.token
            credentials.libraryTokenExpiry = loginResponse.data.expirationTimeStamp

            loginResponse.data.token
        }
    }

    suspend fun ensureToken(): String {
        val token = credentials.libraryToken
        if (token != null && credentials.isLibraryTokenValid) return token

        val username = credentials.libraryUsername
            ?: throw LibraryServiceError.CredentialsNotFound
        val password = credentials.libraryPassword
            ?: throw LibraryServiceError.CredentialsNotFound

        return login(username, password)
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

            if (qrResponse.error.code != 0 || qrResponse.data == null) {
                throw LibraryServiceError.QRGenerationFailed(qrResponse.error.message)
            }
            qrResponse.data
        }
    }
}
