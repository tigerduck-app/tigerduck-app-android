package org.ntust.app.tigerduck.push

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.auth.guardedBy
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.BackendServices
import org.ntust.app.tigerduck.network.backend.BackendError
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.shared.account.SignIn
import javax.inject.Inject
import javax.inject.Singleton

// The change feed's answer when the changes after the asked revision are gone.
private const val HTTP_CONFLICT = 409

/** One entry of the change feed: only what a poll's plan reads (`ChangeFeedPlan`). */
data class SyncChange(val revision: Long, val entityType: String)

/**
 * One page of `GET /sync?since_revision=`, oldest change first, as the backend
 * answers it; as the iPhone's `PushAPI.SyncChangesPage`.
 */
data class SyncChangesPage(
    val currentRevision: Long,
    val returnedUntilRevision: Long,
    val hasMore: Boolean,
    val changes: List<SyncChange>,
)

/**
 * A page as read off the wire. A field missing or a change unreadable reads as
 * what makes the poll run a full sync (more to come, revision 0, no entity
 * type), never as a change it may pass over.
 */
internal fun parseChangesPage(json: JSONObject): SyncChangesPage {
    val changes = json.optJSONArray("changes")
    return SyncChangesPage(
        currentRevision = json.optLong("current_revision", 0),
        returnedUntilRevision = json.optLong("returned_until_revision", 0),
        hasMore = json.optBoolean("has_more", true),
        changes = (0 until (changes?.length() ?: 0)).map { i ->
            val change = changes?.optJSONObject(i)
            SyncChange(change?.optLong("revision", 0) ?: 0, change?.optString("entity_type").orEmpty())
        },
    )
}

@Singleton
class SyncApiClient @Inject constructor(
    private val authTokenManager: AuthTokenManager,
    private val prefs: AppPreferences,
    baseClient: OkHttpClient,
) {
    // Resolve per-call so the debug API-endpoint override applies at once, as PushApiClient
    // does: pinned at construction, auth and full-sync traffic ignored what push honored.
    private val baseUrl: String
        get() = resolveAnnouncementEndpoint(prefs).url.trimEnd('/')

    private val client = baseClient.newBuilder()
        .guardedBy(authTokenManager)
        .build()

    private val api = BackendServices(SyncApi::class.java, client)

    suspend fun fetchRevision(signIn: SignIn): Long = withContext(Dispatchers.IO) {
        val authHeader = authTokenManager.requireAuthHeader(signIn)
        api.at(baseUrl).revision(authHeader).execute().use { response ->
            if (!response.isSuccessful) {
                throw PushApiException("sync/revision failed: HTTP ${response.code}", response.code)
            }
            val json = JSONObject(response.body.string())
            json.optLong("revision", 0)
        }
    }

    /**
     * The changes after [sinceRevision], one page of them; null when the server
     * no longer keeps them, so only a full sync can catch up: a 409
     * `sync_revision_expired`, as every 410 means "update the app". See
     * `ChangeFeedPlan`.
     */
    suspend fun fetchChanges(sinceRevision: Long, signIn: SignIn): SyncChangesPage? = withContext(Dispatchers.IO) {
        val authHeader = authTokenManager.requireAuthHeader(signIn)
        api.at(baseUrl).changes(authHeader, sinceRevision).execute().use { response ->
            if (!response.isSuccessful) {
                // Only a 409 says which with its code; any other failure goes as it came, its body unread.
                if (response.code == HTTP_CONFLICT && BackendError.of(response).isRevisionExpired) {
                    return@withContext null
                }
                throw PushApiException("sync changes failed: HTTP ${response.code}", response.code)
            }
            parseChangesPage(JSONObject(response.body.string()))
        }
    }

    /** The snapshot, protocol 2's, with its stamps, tombstone kinds and resets. */
    suspend fun fetchFullSync(signIn: SignIn): BackendSyncResult = withContext(Dispatchers.IO) {
        val authHeader = authTokenManager.requireAuthHeader(signIn)
        val fetchedAtMs = System.currentTimeMillis()
        api.at(baseUrl).full(authHeader).execute().use { response ->
            if (!response.isSuccessful) {
                throw PushApiException("sync/full failed: HTTP ${response.code}", response.code)
            }
            val json = JSONObject(response.body.string())
            parseFullSync(json)
                .copy(fetchedAtMs = fetchedAtMs, rosterRequests = parseRosterRequests(json))
        }
    }
}
