package org.ntust.app.tigerduck.clubs

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.UiLanguageMonitor
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.util.logSummary
import java.text.Collator
import java.text.Normalizer
import java.time.Instant
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** The directory as the subscribe screen shows it. */
data class ClubDirectoryState(
    /** Every club, by attribute and then by name. */
    val clubs: List<ClubCard> = emptyList(),
    /** Why the last fetch failed; null once one succeeds. */
    val failure: Throwable? = null,
    /** Whether the last fetch failed in the club system's nightly maintenance ([ClubApiClient.isMaintenance]). */
    val underMaintenance: Boolean = false,
)

/**
 * The clubs of clubs.ntust.edu.tw, as the subscribe screen lists and searches them. The stored
 * answer is fetched again only once it is older than the `max-age` it came with. It is public,
 * the same for every student, so it is the device's and a sign-out keeps it, as on iOS.
 */
@Singleton
class ClubDirectory internal constructor(
    private val prefs: SharedPreferences,
    private val client: ClubApiClient,
    private val chinese: () -> Boolean,
    languageChanges: Flow<Unit>,
    scope: CoroutineScope,
    private val now: () -> Instant = Instant::now,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        client: ClubApiClient,
        appPreferences: AppPreferences,
        uiLanguage: UiLanguageMonitor,
        @ApplicationScope scope: CoroutineScope,
    ) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        client,
        { AppLanguageManager.resolvedCourseApiLanguage(appPreferences.appLanguage) != "en" },
        uiLanguage.changes,
        scope,
    )

    private val _state = MutableStateFlow(ClubDirectoryState())
    val state: StateFlow<ClubDirectoryState> = _state.asStateFlow()

    private val refreshLock = Mutex()

    /** When the stored answer was fetched, epoch milliseconds; null for none. */
    @Volatile
    private var storedAtMs: Long? = null

    @Volatile
    private var maxAgeSeconds = 0L

    init {
        val payload = prefs.getString(KEY_PAYLOAD, null)
        val read = payload?.let { runCatching { ClubApiList.clubs(it) }.getOrNull() }
        if (read != null) {
            storedAtMs = runCatching { prefs.getLong(KEY_STORED_AT, 0L) }.getOrNull()
            maxAgeSeconds = storedLifetime(runCatching { prefs.getLong(KEY_MAX_AGE, 0L) }.getOrNull())
            _state.value = ClubDirectoryState(listed(read, chinese()))
        }
    }

    init {
        // The order is the interface's language's, and the directory outlives a switch of it (and
        // the fetch that would sort it again waits out `max-age`): sort again, with no fetch. After a
        // fetch in flight, not during it, which would put the new list in the language it began in.
        scope.launch {
            languageChanges.collect {
                refreshLock.withLock { _state.update { state -> state.copy(clubs = listed(state.clubs, chinese())) } }
            }
        }
    }

    fun club(id: Int): ClubCard? = _state.value.clubs.firstOrNull { it.id == id }

    fun displayName(club: ClubCard): String = club.displayName(chinese())

    /** The clubs whose Chinese or English name holds every word of [query], in list order. */
    fun search(query: String): List<ClubCard> = matching(query, _state.value.clubs)

    /**
     * Fetches the directory once the stored answer is older than its `max-age`; a call during a
     * fetch waits for it, and finds the answer fresh. A failed fetch keeps the stored answer.
     */
    suspend fun refresh() = refreshLock.withLock {
        if (isFresh(storedAtMs, maxAgeSeconds, now())) return@withLock
        try {
            val answer = client.clubs()
            val read = ClubApiList.clubs(answer.body)
            val at = now()
            prefs.edit()
                .putString(KEY_PAYLOAD, answer.body)
                .putLong(KEY_STORED_AT, at.toEpochMilli())
                .putLong(KEY_MAX_AGE, answer.maxAgeSeconds)
                .apply()
            storedAtMs = at.toEpochMilli()
            maxAgeSeconds = storedLifetime(answer.maxAgeSeconds)
            _state.value = ClubDirectoryState(listed(read, chinese()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "club directory refresh failed: ${e.logSummary()}")
            _state.value = _state.value.copy(failure = e, underMaintenance = ClubApiClient.isMaintenance(e, now()))
        }
    }

    companion object {
        private const val TAG = "ClubDirectory"
        private const val PREFS_NAME = "club_directory"
        private const val KEY_PAYLOAD = "payload"
        private const val KEY_STORED_AT = "stored_at"
        private const val KEY_MAX_AGE = "max_age"

        /**
         * A stored `max-age` as it was written, at most a day ([ClubApiClient.maxAge]); anything
         * else, below zero, past a day or unreadable, is a copy this build did not write, and stale.
         */
        internal fun storedLifetime(seconds: Long?): Long =
            seconds?.takeIf { it in 0..ClubApiClient.LONGEST_MAX_AGE_SECONDS } ?: 0L

        /** Whether an answer fetched at [storedAtMs] is still within [maxAgeSeconds] at [now], as iOS's `FreshnessPolicy`; never throws. */
        internal fun isFresh(storedAtMs: Long?, maxAgeSeconds: Long, now: Instant): Boolean {
            val stored = storedAtMs ?: return false
            val age = runCatching { Math.subtractExact(now.toEpochMilli(), stored) }.getOrNull() ?: return false
            return age < maxAgeSeconds * 1000
        }

        /** The order of the list's groups; a club of another attribute, or of none, comes after them. */
        val ATTRIBUTE_ORDER = listOf("學藝性", "藝術性", "體育性", "聯誼性", "服務性", "自治性")

        /** By attribute, then by the name shown, then by id. */
        fun listed(clubs: List<ClubCard>, chinese: Boolean): List<ClubCard> {
            val collator = Collator.getInstance(if (chinese) Locale.TRADITIONAL_CHINESE else Locale.ENGLISH)
                .apply { strength = Collator.SECONDARY }
            fun group(club: ClubCard) = ATTRIBUTE_ORDER.indexOf(club.attribute).takeIf { it >= 0 } ?: ATTRIBUTE_ORDER.size
            return clubs.sortedWith(
                compareBy<ClubCard> { group(it) }
                    .thenComparator { a, b -> collator.compare(a.displayName(chinese), b.displayName(chinese)) }
                    .thenBy { it.id },
            )
        }

        /** The clubs whose Chinese or English name holds every word of [query], case and width aside. */
        fun matching(query: String, clubs: List<ClubCard>): List<ClubCard> {
            val words = query.split(Regex("\\s+")).filter { it.isNotEmpty() }.map(::folded)
            if (words.isEmpty()) return clubs
            return clubs.filter { club ->
                val names = listOfNotNull(club.name, club.enName).map(::folded)
                words.all { word -> names.any { word in it } }
            }
        }

        private fun folded(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    }
}
