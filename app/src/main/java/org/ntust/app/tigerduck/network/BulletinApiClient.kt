package org.ntust.app.tigerduck.network

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.auth.guardedBy
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.backend.BackendError
import org.ntust.app.tigerduck.network.backend.BackendException
import org.ntust.app.tigerduck.network.model.BulletinDetail
import org.ntust.app.tigerduck.network.model.BulletinListResponse
import org.ntust.app.tigerduck.network.model.SubscriptionListRequest
import org.ntust.app.tigerduck.network.model.SubscriptionListRule
import org.ntust.app.tigerduck.network.model.SubscriptionRule
import org.ntust.app.tigerduck.network.model.SubscriptionsListResponse
import org.ntust.app.tigerduck.network.model.TaxonomyResponse
import org.ntust.app.tigerduck.shared.account.SignIn
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

    private val acceptInterceptor = okhttp3.Interceptor { chain ->
        chain.proceed(
            chain.request().newBuilder()
                .header("Accept", "application/json")
                .build()
        )
    }

    private val client = baseClient.newBuilder()
        .addInterceptor(acceptInterceptor)
        .guardedBy(authTokenManager)
        .build()

    private val api = BackendServices(BulletinApi::class.java, client)

    /**
     * The Bearer Authorization header of a request of [signIn]'s, signing in
     * first when there is no session. Without one, or once [signIn] has ended,
     * it throws [org.ntust.app.tigerduck.auth.NotSignedInException] and the
     * request is not sent.
     */
    private suspend fun authHeader(signIn: SignIn): String = authTokenManager.requireAuthHeader(signIn)

    suspend fun fetchTaxonomy(): TaxonomyResponse = getJson { it.taxonomy() }

    suspend fun fetchList(
        cursor: Int? = null,
        limit: Int = 30,
        includeDeleted: Boolean = false,
    ): BulletinListResponse = getJson { it.bulletins(limit, cursor, if (includeDeleted) "true" else null) }

    suspend fun fetchDetail(id: Int): BulletinDetail = getJson { it.bulletin(id) }

    // Subscriptions — device_id is implicit via Bearer JWT. The list is read with one GET, and
    // saved whole ([saveSubscriptions]).

    suspend fun fetchSubscriptions(signIn: SignIn): SubscriptionsListResponse =
        getJsonAuthed(signIn) { service, auth -> service.subscriptions(auth) }

    /**
     * This device's rules become [rules], in one PUT of the whole list, each rule kept by its
     * `client_id` (`docs/api-v4.md`, "Subscriptions"). Answers the rules as the backend keeps them,
     * in the order sent. Nothing is sent for a rule without a `client_id`, which the backend refuses.
     */
    suspend fun saveSubscriptions(rules: List<SubscriptionRule>, signIn: SignIn): List<SubscriptionRule> =
        withContext(Dispatchers.IO) {
            val list = SubscriptionListRequest(
                rules.map { rule ->
                    SubscriptionListRule(
                        clientId = rule.clientId ?: throw BulletinApiException("saveSubscriptions: a rule without a client_id"),
                        name = rule.name,
                        orgs = rule.orgs,
                        tags = rule.tags,
                        mode = rule.mode,
                        enabled = rule.enabled,
                    )
                },
            )
            api.at(baseUrl).subscriptionList(authHeader(signIn), list).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) {
                    throw BackendException(
                        BackendError(response.code, BackendError.codeIn(text)),
                        "saveSubscriptions failed: HTTP ${response.code} $text",
                    )
                }
                if (text.isBlank()) throw BulletinApiException("saveSubscriptions: empty body")
                gson.fromJson(text, SubscriptionsListResponse::class.java)?.items
                    ?: throw BulletinApiException("saveSubscriptions: null response")
            }
        }

    /**
     * Concrete (non-generic) [T] only. `T::class.java` erases type parameters,
     * so calling this with e.g. `List<BulletinSummary>` deserializes to
     * `List<LinkedTreeMap<*,*>>` and the cast only blows up later when items
     * are accessed. For parameterised types, deserialize with a `TypeToken`.
     */
    private suspend inline fun <reified T> getJson(crossinline call: (BulletinApi) -> Call): T =
        withContext(Dispatchers.IO) { parsed(call(api.at(baseUrl))) }

    private suspend inline fun <reified T> getJsonAuthed(signIn: SignIn, crossinline call: (BulletinApi, String) -> Call): T =
        withContext(Dispatchers.IO) {
            val service = api.at(baseUrl)
            parsed(call(service, authHeader(signIn)))
        }

    private inline fun <reified T> parsed(call: Call): T {
        val url = call.request().url.toString()
        return call.execute().use { response ->
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
