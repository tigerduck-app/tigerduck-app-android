package org.ntust.app.tigerduck.clubs

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.auth.session.AccountScoped
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.util.logSummary
import javax.inject.Inject
import javax.inject.Singleton

/** An activity of a subscribed club on one Taipei day, with what the class table draws and shows. */
data class ClubOccurrence(
    /** The activity's id. */
    val id: Int,
    val clubId: Int,
    /** The club's name in the interface's language. */
    val clubName: String,
    /** `0xRRGGBB`. */
    val color: Int,
    /** The club's place in the user's order, 0 first; where two clubs overlap, the lower one shows. */
    val priority: Int,
    /** `yyyy-MM-dd`. */
    val day: String,
    /** Minutes after midnight, Taipei time. */
    val start: Int,
    val end: Int,
    val location: String,
    val kind: ClubActivityKind,
    val name: String,
    val content: String,
)

/** A subscribed club, by priority, as the feed is filtered to them. */
data class SubscribedClub(val id: Int, val color: Int)

/**
 * The school-wide club activity feed as last fetched, a conditional GET with its ETag, which the
 * class table filters to the subscribed clubs. The feed is the same for every student, but the
 * app keeps it with the account it was fetched for, as it fetches it only for one that subscribes:
 * a school sign-out drops it ([SchoolStores][org.ntust.app.tigerduck.auth.session.SchoolStores]).
 */
@Singleton
class ClubActivities internal constructor(
    private val prefs: SharedPreferences,
    private val client: ClubApiClient,
) : AccountScoped {
    @Inject constructor(@ApplicationContext context: Context, client: ClubApiClient) :
        this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE), client)

    private val lock = Any()
    private val refreshLock = Mutex()
    private val _activities = MutableStateFlow(stored())
    val activities: StateFlow<List<ClubActivity>> = _activities.asStateFlow()

    /**
     * Asks whether the feed changed since the stored copy; one at a time. A failed fetch keeps the
     * stored copy, and an answer that comes after [signIn] ended is dropped.
     */
    suspend fun refresh(signIn: SignIn) = refreshLock.withLock {
        if (!signIn.isCurrent) return@withLock
        try {
            val etag = synchronized(lock) { prefs.getString(KEY_ETAG, null)?.takeIf { prefs.contains(KEY_PAYLOAD) } }
            val copy = client.activities(etag) ?: return@withLock
            val read = ClubApiList.activities(copy.body)
            signIn.ifCurrent {
                synchronized(lock) {
                    prefs.edit().putString(KEY_PAYLOAD, copy.body).putString(KEY_ETAG, copy.etag.orEmpty()).apply()
                    if (read != _activities.value) _activities.value = read
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "club activities refresh failed: ${e.logSummary()}")
        }
    }

    /** At the school's sign-out: the feed and its ETag. */
    override fun forgetAccount() = clear()

    fun clear() = synchronized(lock) {
        prefs.edit().remove(KEY_PAYLOAD).remove(KEY_ETAG).apply()
        _activities.value = emptyList()
    }

    private fun stored(): List<ClubActivity> {
        val payload = prefs.getString(KEY_PAYLOAD, null) ?: return emptyList()
        return runCatching { ClubApiList.activities(payload) }.getOrElse {
            // A copy this build cannot read would stay forever behind its ETag: dropped, the next
            // refresh asks for it whole.
            prefs.edit().remove(KEY_PAYLOAD).remove(KEY_ETAG).apply()
            emptyList()
        }
    }

    companion object {
        private const val TAG = "ClubActivities"
        private const val PREFS_NAME = "club_activities"
        private const val KEY_PAYLOAD = "payload"
        private const val KEY_ETAG = "etag"

        /**
         * The activities of [clubs], the subscribed clubs highest priority first, on [days]
         * (`yyyy-MM-dd`), by day, start and priority. Only an activity that starts and ends on one
         * day with both times has a place ([ClubActivity.oneDay]). A club [names] lacks keeps the
         * feed's Chinese name.
         */
        fun occurrences(
            activities: List<ClubActivity>,
            days: Set<String>,
            clubs: List<SubscribedClub>,
            names: Map<Int, String>,
        ): List<ClubOccurrence> {
            val rank = HashMap<Int, Int>()
            clubs.forEachIndexed { index, club -> rank.putIfAbsent(club.id, index) }
            return activities.mapNotNull { activity ->
                val priority = rank[activity.clubId] ?: return@mapNotNull null
                val placed = activity.oneDay?.takeIf { it.day in days } ?: return@mapNotNull null
                ClubOccurrence(
                    id = activity.id, clubId = activity.clubId, clubName = names[activity.clubId] ?: activity.clubName,
                    color = clubs[priority].color, priority = priority, day = placed.day, start = placed.start,
                    end = placed.end, location = activity.location, kind = activity.kind, name = activity.name,
                    content = activity.content,
                )
            }.sortedWith(compareBy<ClubOccurrence>({ it.day }, { it.start }, { it.priority }, { it.id }))
        }
    }
}
