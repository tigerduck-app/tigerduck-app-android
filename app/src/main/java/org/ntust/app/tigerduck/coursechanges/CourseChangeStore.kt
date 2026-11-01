package org.ntust.app.tigerduck.coursechanges

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.BackendServices
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.shared.timeline.CourseChanges
import org.ntust.app.tigerduck.util.logSummary
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The school's course changes for its current term, from the backend's feed, kept on the device
 * so the class table and the alarms read them offline. As the iPhone's `CourseChangeStore`.
 *
 * Like the academic calendar, the feed is public, the school's own notices, so it is fetched
 * without an account, whatever the sync setting and the flavor; but nothing is asked before
 * onboarding ends. Each answer is the whole term and replaces the stored one; a 304, a 404 (no
 * current term) or a failure keeps it. The copy is kept with the endpoint it came from: another
 * endpoint's feed is dropped, not shown.
 */
@Singleton
class CourseChangeStore internal constructor(
    private val prefs: SharedPreferences,
    client: OkHttpClient,
    private val endpoint: () -> String,
    private val onboarded: () -> Boolean,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        baseClient: OkHttpClient,
        appPreferences: AppPreferences,
    ) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        baseClient,
        { resolveAnnouncementEndpoint(appPreferences).url },
        { appPreferences.hasCompletedOnboarding },
    )

    // Past any HTTP cache, which could answer from a copy the ETag no longer matches; the store
    // keeps its own copy and sends its own If-None-Match.
    private val api = BackendServices(CourseChangeApi::class.java, client.newBuilder().cache(null).build())
    private val refreshMutex = Mutex()

    // Guards the stored copy against a move: a request is registered, a move drops the copy, and
    // an answer is kept, each whole under it, so none lands between another's steps.
    private val lock = Any()

    /** The request out now, for [endpointMoved] to cancel. */
    private var inFlight: okhttp3.Call? = null

    /** Moves so far; an answer to a request made before the last is not kept. */
    private var generation = 0

    // Lazy, so injecting the store on the app's start costs no disk read.
    private val _changes: MutableStateFlow<CourseChanges> by lazy { MutableStateFlow(loadFromDisk()) }

    /** The stored feed; [CourseChanges.EMPTY] before the first answer, when every screen looks as before. */
    val changes: StateFlow<CourseChanges> get() = _changes.asStateFlow()

    /** The stored feed, for callers with no coroutine to collect [changes] in (alarms, widgets). */
    fun current(): CourseChanges = _changes.value

    /**
     * Runs [action] for each feed after the one held when this starts, until cancelled: the
     * caller has already used the held one, as the watch publish at launch has. A feed equal to
     * the last, as after a 304, runs nothing; conflated, as only the last of a burst matters.
     */
    suspend fun onEachNewFeed(action: suspend () -> Unit) {
        changes.drop(1).conflate().collect { action() }
    }

    /**
     * Fetches the feed again; one at a time. Returns whether the backend answered, a 304 included.
     * A feed fetched from an endpoint that is no longer the one is dropped.
     */
    suspend fun refresh(): Boolean = refreshMutex.withLock {
        if (!onboarded()) return@withLock false
        withContext(Dispatchers.IO) {
            val (base, asked, call) = synchronized(lock) {
                val base = endpoint()
                if (prefs.getString(KEY_ENDPOINT, null) != base) forget()
                // Read first, so a stored feed this build cannot read is dropped with its ETag
                // rather than kept behind a 304.
                _changes.value
                val etag = prefs.getString(KEY_ETAG, null)
                    ?.takeIf { it.isNotEmpty() && prefs.getString(KEY_PAYLOAD, null) != null }
                val call = api.at(base).courseChanges("application/json", etag)
                inFlight = call
                Triple(base, generation, call)
            }
            runCatching {
                call.execute().use { response ->
                    when {
                        response.code == HTTP_NOT_MODIFIED -> true
                        response.isSuccessful -> {
                            val body = response.body.string()
                            val parsed = CourseChanges.decode(body)
                            synchronized(lock) {
                                if (generation != asked || endpoint() != base) return@use false
                                prefs.edit()
                                    .putString(KEY_PAYLOAD, body)
                                    .putString(KEY_ETAG, response.header("ETag").orEmpty())
                                    .putString(KEY_ENDPOINT, base)
                                    .apply()
                                _changes.value = parsed
                            }
                            true
                        }
                        else -> false
                    }
                }
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                Log.w(TAG, "course changes refresh failed: ${e.logSummary()}")
                false
            }.also { synchronized(lock) { if (inFlight === call) inFlight = null } }
        }
    }

    /**
     * For a move to another backend: drops the stored feed at once and cancels a request still
     * out at the old one, which would keep the next refresh waiting behind it. The caller fetches
     * the new backend's next.
     */
    fun endpointMoved() = synchronized(lock) {
        generation++
        inFlight?.cancel()
        forget()
    }

    /** Drops the stored feed, as for another endpoint's. */
    private fun forget() {
        prefs.edit().remove(KEY_PAYLOAD).remove(KEY_ETAG).remove(KEY_ENDPOINT).apply()
        _changes.value = CourseChanges.EMPTY
    }

    private fun loadFromDisk(): CourseChanges {
        val raw = prefs.getString(KEY_PAYLOAD, null) ?: return CourseChanges.EMPTY
        if (prefs.getString(KEY_ENDPOINT, null) != endpoint()) return CourseChanges.EMPTY
        return runCatching { CourseChanges.decode(raw) }.getOrElse {
            // A feed this build cannot read would stay forever behind its ETag: dropped, the next
            // refresh asks for it whole.
            Log.w(TAG, "stored course changes unreadable; discarding: ${it.logSummary()}")
            prefs.edit().remove(KEY_PAYLOAD).remove(KEY_ETAG).apply()
            CourseChanges.EMPTY
        }
    }

    companion object {
        private const val TAG = "CourseChanges"
        private const val PREFS_NAME = "course_changes"
        private const val KEY_PAYLOAD = "payload"
        private const val KEY_ETAG = "etag"
        private const val KEY_ENDPOINT = "endpoint"
        private const val HTTP_NOT_MODIFIED = 304
    }
}
