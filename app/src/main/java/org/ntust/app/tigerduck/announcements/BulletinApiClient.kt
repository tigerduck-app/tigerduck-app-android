package org.ntust.app.tigerduck.announcements

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.ntust.app.tigerduck.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

class BulletinApiException(message: String) : Exception(message)

@Singleton
class BulletinApiClient @Inject constructor(
    baseClient: OkHttpClient,
) {

    private val baseUrl = BuildConfig.PUSH_BASE_URL.trimEnd('/')
    private val sharedSecret = BuildConfig.PUSH_SHARED_SECRET
    private val gson = Gson()
    private val jsonType = "application/json".toMediaType()

    // Single shared interceptor instance: redactHeader is a no-op for the
    // unauthenticated `client` (which never sends X-Push-Token) but is
    // load-bearing for `authedClient`, which reuses this same instance.
    // Don't split into per-client loggers without keeping redaction on the
    // authed path.
    private val logging = HttpLoggingInterceptor { msg ->
        Log.d("TigerDuck-Bulletin", msg)
    }.apply {
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.HEADERS
        else HttpLoggingInterceptor.Level.NONE
        redactHeader("X-Push-Token")
    }

    private val acceptInterceptor = okhttp3.Interceptor { chain ->
        chain.proceed(
            chain.request().newBuilder()
                .header("Accept", "application/json")
                .build()
        )
    }

    private val authInterceptor = okhttp3.Interceptor { chain ->
        val req = if (sharedSecret.isNotEmpty()) {
            chain.request().newBuilder().header("X-Push-Token", sharedSecret).build()
        } else chain.request()
        chain.proceed(req)
    }

    // `client` is built eagerly while `authedClient` is `by lazy`. The
    // asymmetry is incidental, not load-bearing — both clone the same
    // thread-safe `baseClient` and have no special init ordering. `authedClient`
    // is lazy only because most installs (no shared secret configured) never
    // touch it, so we skip building it on cold start.
    private val client = baseClient.newBuilder()
        .addInterceptor(acceptInterceptor)
        .addInterceptor(logging)
        .build()

    // Auth interceptor must be added BEFORE logging so the X-Push-Token header
    // is present when the logger sees the request — otherwise redactHeader is
    // a no-op (the header is added downstream and never passes the logger).
    private val authedClient: OkHttpClient by lazy {
        baseClient.newBuilder()
            .addInterceptor(acceptInterceptor)
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .build()
    }

    suspend fun fetchTaxonomy(): TaxonomyResponse = getJson("$baseUrl/bulletins/taxonomy")

    suspend fun fetchList(
        cursor: Int? = null,
        limit: Int = 30,
        includeDeleted: Boolean = false,
    ): BulletinListResponse {
        val url = "$baseUrl/bulletins".toHttpUrl().newBuilder().apply {
            addQueryParameter("limit", limit.toString())
            if (cursor != null) addQueryParameter("cursor", cursor.toString())
            if (includeDeleted) addQueryParameter("include_deleted", "true")
        }.build()
        return getJson(url.toString())
    }

    suspend fun fetchDetail(id: Int): BulletinDetail = getJson("$baseUrl/bulletins/$id")

    suspend fun fetchSubscriptions(deviceId: String): SubscriptionsResponse =
        getJson("$baseUrl/devices/$deviceId/subscriptions", authed = true)

    suspend fun putSubscriptions(
        deviceId: String,
        rules: List<SubscriptionRule>,
    ): SubscriptionsResponse = withContext(Dispatchers.IO) {
        val body = gson.toJson(SubscriptionsPutRequest(rules)).toRequestBody(jsonType)
        val request = Request.Builder()
            .url("$baseUrl/devices/$deviceId/subscriptions")
            .put(body)
            .build()
        authedClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw BulletinApiException("subscriptions PUT failed: HTTP ${response.code}")
            }
            // Gson throws JsonSyntaxException on "" instead of returning null,
            // so the null branch alone wouldn't surface our message.
            if (text.isBlank()) throw BulletinApiException("subscriptions PUT: empty body")
            gson.fromJson(text, SubscriptionsResponse::class.java)
                ?: throw BulletinApiException("subscriptions PUT: empty body")
        }
    }

    private suspend inline fun <reified T> getJson(url: String, authed: Boolean = false): T =
        withContext(Dispatchers.IO) {
            val c = if (authed) authedClient else client
            val request = Request.Builder().url(url).get().build()
            c.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw BulletinApiException("GET $url failed: HTTP ${response.code}")
                }
                // Gson throws JsonSyntaxException on "" instead of returning
                // null, so the null branch alone wouldn't surface our message.
                if (text.isBlank()) throw BulletinApiException("GET $url: empty body")
                gson.fromJson(text, T::class.java)
                    ?: throw BulletinApiException("GET $url: empty body")
            }
        }
}
