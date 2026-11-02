package org.ntust.app.tigerduck.push

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.auth.guardedBy
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.BackendServices
import org.ntust.app.tigerduck.network.backend.BackendApi
import org.ntust.app.tigerduck.network.backend.BackendError
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.SyncClock
import javax.inject.Inject
import javax.inject.Singleton

// The change feed's answer on /v3 when the changes after the asked revision are gone.
private const val HTTP_CONFLICT = 409
private const val HTTP_GONE = 410

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
    /** Hears the clock values a `/v4` snapshot holds (LWW D4); null only in tests, for none. */
    private val syncClock: SyncClock? = null,
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
     * no longer keeps them, so only a full sync can catch up: any 410 on `/v3`,
     * or `/v4`'s 409 `sync_revision_expired`, as `/v4` keeps every 410 for
     * "update the app". See `ChangeFeedPlan`.
     */
    suspend fun fetchChanges(sinceRevision: Long, signIn: SignIn): SyncChangesPage? = withContext(Dispatchers.IO) {
        val authHeader = authTokenManager.requireAuthHeader(signIn)
        api.at(baseUrl).changes(authHeader, sinceRevision).execute().use { response ->
            if (!response.isSuccessful) {
                // Only a 409 says which with its code; any other failure goes as it came, its body unread.
                if (response.code == HTTP_GONE || (response.code == HTTP_CONFLICT && BackendError.of(response).isRevisionExpired)) {
                    return@withContext null
                }
                throw PushApiException("sync changes failed: HTTP ${response.code}", response.code)
            }
            parseChangesPage(JSONObject(response.body.string()))
        }
    }

    /**
     * The snapshot, read in the shape the base it came from speaks: on `/v4`, protocol 2's, with
     * its stamps, tombstone kinds and resets, and the largest clock value in it heard by the clock;
     * on `/v3` as it always was, those fields null and nothing heard, though `/v3` sends them too.
     */
    suspend fun fetchFullSync(signIn: SignIn): BackendSyncResult = withContext(Dispatchers.IO) {
        val authHeader = authTokenManager.requireAuthHeader(signIn)
        val fetchedAtMs = System.currentTimeMillis()
        // Resolved once, so the shape it is read in is the one the base it went to answers in.
        val base = baseUrl
        api.at(base).full(authHeader).execute().use { response ->
            if (!response.isSuccessful) {
                throw PushApiException("sync/full failed: HTTP ${response.code}", response.code)
            }
            val json = JSONObject(response.body.string())
            parseFullSync(json, protocol2 = BackendApi.of(base) == BackendApi.V4, heard = { syncClock?.heardInSnapshot(it) })
                .copy(fetchedAtMs = fetchedAtMs, rosterRequests = parseRosterRequests(json))
        }
    }
}
