package org.ntust.app.tigerduck.push

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import javax.inject.Inject
import javax.inject.Singleton

class SettingsDocumentApiException(message: String) : Exception(message)

/**
 * A `/v3/settings/{namespace}` document as the server returns it: the body
 * of a successful GET or PUT, and also the `server` half of a 409 conflict
 * body. See `server/routes/settings_docs.py`'s `settings_document_to_dict`.
 *
 * [revision] is what a later [SettingsDocumentApiClient.write] passes back
 * as `baseRevision` to compare-and-swap on top of this exact state.
 */
data class SettingsDocumentEnvelope<T>(
    val document: T,
    val revision: Long,
)

/** Outcome of [SettingsDocumentApiClient.write]. */
sealed interface SettingsWriteResult<out T> {
    /** The PUT applied; the namespace is now at [revision]. */
    data class Written<T>(val revision: Long) : SettingsWriteResult<T>

    /**
     * The PUT was rejected: `baseRevision == null` and a document already
     * existed, or `baseRevision` no longer matched the server's current
     * revision. [server] is the winning state the 409 body carries
     * (`settings_docs.py`'s `_conflict_response`), so a caller can rebase
     * its change onto it and retry instead of silently losing the edit.
     */
    data class Conflict<T>(val server: SettingsDocumentEnvelope<T>) : SettingsWriteResult<T>
}

/**
 * Client for the backend's per-namespace settings-document API
 * (`GET/PUT /v3/settings/{namespace}`) — see `server/routes/settings_docs.py`.
 * `T` is the caller's Gson type for that namespace's JSON shape, e.g.
 * [NotificationSettingsDocument] for the `"notification"` namespace.
 *
 * Mirrors [PushApiClient]'s request building, auth header and error
 * handling. Every DTO round-tripped through this client must be
 * `@SerializedName`-annotated field-by-field: `push` has no R8 keep rule,
 * so an unannotated field is silently null after release-build obfuscation.
 */
@Singleton
class SettingsDocumentApiClient @Inject constructor(
    baseClient: OkHttpClient,
    private val prefs: AppPreferences,
    private val authTokenManager: AuthTokenManager,
) {
    private val baseUrl: String
        get() = resolveAnnouncementEndpoint(prefs).url.trimEnd('/')
    private val gson = Gson()
    private val jsonType = "application/json".toMediaType()

    private val client = baseClient.newBuilder()
        .addInterceptor { chain ->
            val builder = chain.request().newBuilder()
                .header("Accept", "application/json")
            chain.proceed(builder.build())
        }
        .build()

    /** Adds a Bearer Authorization header if a v3 token is available. */
    private suspend fun Request.Builder.addAuthHeader(): Request.Builder {
        val authHeader = authTokenManager.authHeader()
        return if (authHeader != null) header("Authorization", authHeader) else this
    }

    /** Reified convenience for [read] — see that overload for behaviour. */
    suspend inline fun <reified T> read(namespace: String): SettingsDocumentEnvelope<T>? =
        read(namespace, T::class.java)

    /**
     * `GET /v3/settings/{namespace}`.
     *
     * Returns null on 404 — no document has been written for this user in
     * this namespace yet, which is the normal state for every user before
     * their first write to it (e.g. every pre-v2.1.0 user has never written
     * `live_activity`, and may never have written `notification` at all).
     */
    suspend fun <T> read(namespace: String, type: Class<T>): SettingsDocumentEnvelope<T>? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/settings/$namespace")
                .get()
                .addAuthHeader()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 404) return@use null
                val text = response.body.string()
                if (!response.isSuccessful) {
                    throw SettingsDocumentApiException(
                        "read $namespace failed: HTTP ${response.code} $text"
                    )
                }
                if (text.isBlank()) throw SettingsDocumentApiException("read $namespace: empty body")
                parseEnvelope(text, type)
            }
        }

    /** Reified convenience for [write] — see that overload for behaviour. */
    suspend inline fun <reified T> write(
        namespace: String,
        document: T,
        baseRevision: Long?,
    ): SettingsWriteResult<T> = write(namespace, document, baseRevision, T::class.java)

    /**
     * `PUT /v3/settings/{namespace}` with body
     * `{"schema_version": 1, "document": document, "base_revision": baseRevision}`.
     *
     * [baseRevision] null means "create" — a 409 means the namespace already
     * has a document. Non-null means compare-and-swap against that revision
     * — a 409 means someone else wrote first. Either way a conflict comes
     * back as [SettingsWriteResult.Conflict] rather than a thrown exception:
     * it is an expected outcome the caller is meant to handle (rebase and
     * retry), not a transport failure.
     */
    suspend fun <T> write(
        namespace: String,
        document: T,
        baseRevision: Long?,
        type: Class<T>,
    ): SettingsWriteResult<T> = withContext(Dispatchers.IO) {
        val payload = mapOf(
            "schema_version" to 1,
            "document" to document,
            "base_revision" to baseRevision,
        )
        val body = gson.toJson(payload).toRequestBody(jsonType)
        val request = Request.Builder()
            .url("$baseUrl/settings/$namespace")
            .put(body)
            .addAuthHeader()
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (response.code == 409) {
                if (text.isBlank()) throw SettingsDocumentApiException("write $namespace: empty conflict body")
                val serverJson = extractConflictServerJson(text, namespace)
                return@use SettingsWriteResult.Conflict(parseEnvelope(serverJson, type))
            }
            if (!response.isSuccessful) {
                throw SettingsDocumentApiException(
                    "write $namespace failed: HTTP ${response.code} $text"
                )
            }
            if (text.isBlank()) throw SettingsDocumentApiException("write $namespace: empty body")
            SettingsWriteResult.Written(parseEnvelope(text, type).revision)
        }
    }

    /** Parses `{..., "document": {...}, "revision": N, ...}` into [T]. */
    private fun <T> parseEnvelope(text: String, type: Class<T>): SettingsDocumentEnvelope<T> =
        try {
            val root = JSONObject(text)
            val document = gson.fromJson(root.getJSONObject("document").toString(), type)
                ?: throw SettingsDocumentApiException("settings document: null document")
            SettingsDocumentEnvelope(document = document, revision = root.getLong("revision"))
        } catch (e: SettingsDocumentApiException) {
            throw e
        } catch (e: Exception) {
            throw SettingsDocumentApiException("settings document: malformed response: ${e.message}")
        }
}

/**
 * Extracts the `"server"` object from a 409 write-conflict body — shape is
 * `{"error", "namespace", "server": {document, revision, ...}}`, see
 * `server/routes/settings_docs.py`'s `_conflict_response` — and returns it
 * as JSON text for [SettingsDocumentApiClient]'s private `parseEnvelope` to
 * decode.
 *
 * This app points at user-configured self-hosted backends (see
 * `ApiEndpointOverride.kt`), so a 409 body that isn't exactly
 * `{"server": {...}}` — a reverse proxy's own error page, a different
 * server build, or valid JSON simply missing the `server` key — is
 * reachable in normal use, not just a theoretical malformed response. It
 * must surface as [SettingsDocumentApiException], never a raw
 * [org.json.JSONException] that would sail straight past a caller's
 * `catch (e: SettingsDocumentApiException)` rebase-and-retry handler.
 *
 * `internal` rather than `private` solely so a unit test can exercise this
 * exact parsing without driving a whole [SettingsDocumentApiClient] through
 * a real network stack.
 */
internal fun extractConflictServerJson(text: String, namespace: String): String =
    try {
        JSONObject(text).getJSONObject("server").toString()
    } catch (e: JSONException) {
        throw SettingsDocumentApiException("write $namespace: malformed conflict body: ${e.message}")
    }
