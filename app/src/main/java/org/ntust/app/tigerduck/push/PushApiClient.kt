package org.ntust.app.tigerduck.push

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.ntust.app.tigerduck.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

class PushApiException(message: String) : Exception(message)

@Singleton
class PushApiClient @Inject constructor(
    baseClient: OkHttpClient,
) {

    private val baseUrl = BuildConfig.PUSH_BASE_URL.trimEnd('/')
    private val sharedSecret = BuildConfig.PUSH_SHARED_SECRET
    private val gson = Gson()
    private val jsonType = "application/json".toMediaType()

    private val logging = HttpLoggingInterceptor { msg ->
        Log.d("TigerDuck-Push", msg)
    }.apply {
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.HEADERS
        else HttpLoggingInterceptor.Level.NONE
        redactHeader("X-Push-Token")
    }

    private val client = baseClient.newBuilder()
        .addInterceptor { chain ->
            val builder = chain.request().newBuilder()
                .header("Accept", "application/json")
            if (sharedSecret.isNotEmpty()) {
                builder.header("X-Push-Token", sharedSecret)
            }
            chain.proceed(builder.build())
        }
        .addInterceptor(logging)
        .build()

    suspend fun register(req: DeviceRegisterRequest): DeviceRegisterResponse =
        withContext(Dispatchers.IO) {
            val body = gson.toJson(req).toRequestBody(jsonType)
            val request = Request.Builder()
                .url("$baseUrl/devices/register")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw PushApiException("register failed: HTTP ${response.code} $text")
                }
                gson.fromJson(text, DeviceRegisterResponse::class.java)
                    ?: throw PushApiException("register: empty body")
            }
        }

    suspend fun unregister(deviceId: String) = withContext(Dispatchers.IO) {
        val body = gson.toJson(DeviceUnregisterRequest(deviceId)).toRequestBody(jsonType)
        val request = Request.Builder()
            .url("$baseUrl/devices/unregister")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw PushApiException("unregister failed: HTTP ${response.code}")
            }
        }
    }
}
