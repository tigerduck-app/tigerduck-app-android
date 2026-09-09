package org.ntust.app.tigerduck.network

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.model.BulletinDetail
import org.ntust.app.tigerduck.network.model.BulletinListResponse
import org.ntust.app.tigerduck.network.model.SubscriptionRule
import org.ntust.app.tigerduck.network.model.SubscriptionRuleRequest
import org.ntust.app.tigerduck.network.model.SubscriptionsListResponse
import org.ntust.app.tigerduck.network.model.TaxonomyResponse
import javax.inject.Inject
import javax.inject.Singleton

class BulletinApiException(message: String) : Exception(message)

@Singleton
class BulletinApiClient @Inject constructor(
    baseClient: OkHttpClient,
    private val prefs: AppPreferences,
    private val authTokenManager: AuthTokenManager,
) {

    // Resolved per call so a Debug build's Settings → Developer → API
    // endpoint override takes effect on the next request without an app
    // relaunch. Release builds can never write the override (the screen
    // is DEBUG-gated), so the resolver collapses to the default URL in
    // production. The resolver re-applies the save-time allowlist so a
    // stale stored value `OverrideValidator` would now reject (e.g. an
    // `adb`-set `http://example.com/v2`) cannot reach the subscription
    // endpoints below.
    private val baseUrl: String
        get() = resolveAnnouncementEndpoint(prefs).url
    private val gson = Gson()
    private val jsonType = "application/json".toMediaType()

    private val acceptInterceptor = okhttp3.Interceptor { chain ->
        chain.proceed(
            chain.request().newBuilder()
                .header("Accept", "application/json")
                .build()
        )
    }

    private val client = baseClient.newBuilder()
        .addInterceptor(acceptInterceptor)
        .build()

    /**
     * Builds a Request.Builder with Bearer auth if a v3 token is available.
     * Called from suspension context — must be called with [withContext] if
     * not already on a suspend call stack.
     */
    private suspend fun Request.Builder.addAuthHeader(): Request.Builder {
        val authHeader = authTokenManager.authHeader()
        return if (authHeader != null) header("Authorization", authHeader) else this
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

    // v3 subscription CRUD — device_id is implicit via Bearer JWT.

    suspend fun fetchSubscriptions(): SubscriptionsListResponse =
        getJsonAuthed("$baseUrl/bulletin-subscriptions")

    suspend fun createSubscription(rule: SubscriptionRule): SubscriptionRule =
        withContext(Dispatchers.IO) {
            val reqBody = SubscriptionRuleRequest(
                name = rule.name,
                orgs = rule.orgs,
                tags = rule.tags,
                mode = rule.mode,
                enabled = rule.enabled,
            )
            val body = gson.toJson(reqBody).toRequestBody(jsonType)
            val request = Request.Builder()
                .url("$baseUrl/bulletin-subscriptions")
                .post(body)
                .addAuthHeader()
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) {
                    throw BulletinApiException("createSubscription failed: HTTP ${response.code} $text")
                }
                if (text.isBlank()) throw BulletinApiException("createSubscription: empty body")
                gson.fromJson(text, SubscriptionRule::class.java)
                    ?: throw BulletinApiException("createSubscription: null response")
            }
        }

    suspend fun updateSubscription(id: Int, rule: SubscriptionRule): SubscriptionRule =
        withContext(Dispatchers.IO) {
            val reqBody = SubscriptionRuleRequest(
                name = rule.name,
                orgs = rule.orgs,
                tags = rule.tags,
                mode = rule.mode,
                enabled = rule.enabled,
            )
            val body = gson.toJson(reqBody).toRequestBody(jsonType)
            val request = Request.Builder()
                .url("$baseUrl/bulletin-subscriptions/$id")
                .patch(body)
                .addAuthHeader()
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) {
                    throw BulletinApiException("updateSubscription failed: HTTP ${response.code} $text")
                }
                if (text.isBlank()) throw BulletinApiException("updateSubscription: empty body")
                gson.fromJson(text, SubscriptionRule::class.java)
                    ?: throw BulletinApiException("updateSubscription: null response")
            }
        }

    suspend fun deleteSubscription(id: Int) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/bulletin-subscriptions/$id")
            .delete()
            .addAuthHeader()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw BulletinApiException("deleteSubscription failed: HTTP ${response.code}")
            }
        }
    }

    /**
     * Concrete (non-generic) [T] only. `T::class.java` erases type parameters,
     * so calling this with e.g. `List<BulletinSummary>` deserializes to
     * `List<LinkedTreeMap<*,*>>` and the cast only blows up later when items
     * are accessed. For parameterised types, deserialize with a `TypeToken`.
     */
    private suspend inline fun <reified T> getJson(url: String): T =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) {
                    throw BulletinApiException("GET $url failed: HTTP ${response.code}")
                }
                if (text.isBlank()) throw BulletinApiException("GET $url: empty body")
                gson.fromJson(text, T::class.java)
                    ?: throw BulletinApiException("GET $url: empty body")
            }
        }

    private suspend inline fun <reified T> getJsonAuthed(url: String): T =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .get()
                .addAuthHeader()
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) {
                    throw BulletinApiException("GET $url failed: HTTP ${response.code}")
                }
                if (text.isBlank()) throw BulletinApiException("GET $url: empty body")
                gson.fromJson(text, T::class.java)
                    ?: throw BulletinApiException("GET $url: empty body")
            }
        }
}
