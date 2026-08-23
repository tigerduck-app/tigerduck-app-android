package org.ntust.app.tigerduck.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.model.SemesterInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Server-driven semester list, sourced from the public `querycourse` catalogue.
 *
 * [SemesterCodes.heuristic] is a gregorian-month guess, so it lags whenever
 * NTUST publishes a term early. On 2026-08-20 it still said 114-2 while the
 * school had already opened 115-1, which broke the class table two ways:
 *
 *  1. The picker walked back four terms from the heuristic, so 115-1 never
 *     appeared in it.
 *  2. The 選課 system had *already* flipped to 115-1, and its 選課清單 page
 *     carries no term marker anywhere in the HTML — so those enrolments were
 *     filed under the heuristic's 114-2 and both terms rendered into one grid.
 *
 * `api/semestersinfo` is the same endpoint the official course-query site uses
 * to populate its own semester menu. `LoginEnable` marks the single term the
 * 選課 system is operating on, which is exactly the attribution the 選課清單
 * scrape is missing.
 *
 * Every reader falls back to the month heuristic when the endpoint is
 * unreachable, so a failed fetch degrades to the pre-catalogue behaviour
 * rather than emptying the picker.
 */
@Singleton
class SemesterCatalog @Inject constructor(
    private val sessionManager: NtustSessionManager,
    private val appPreferences: AppPreferences,
) {
    /**
     * Terms the picker offers, newest first. Falls back to walking back from
     * the month heuristic until the first successful [refresh].
     */
    fun availableSemesters(): List<String> {
        val cached = appPreferences.semesterCatalogTerms
        if (cached.isEmpty()) return SemesterCodes.walkBack(FALLBACK_TERM, 4)
        return cached.take(PICKER_DEPTH)
    }

    /**
     * The term the picker should open on: the user's last pick, or the newest
     * published term when they have never picked one.
     *
     * The stored pick is passed in rather than read here so the rule stays
     * testable, and callers deliberately do *not* persist the fallback — an
     * untouched picker should keep tracking the newest term rather than
     * freezing on whichever one happened to be newest at first launch.
     */
    fun selectedSemester(storedPick: String?): String =
        storedPick
            ?: availableSemesters().firstOrNull()
            ?: FALLBACK_TERM

    /**
     * The term the 選課 system is currently serving — the one whose enrolments
     * [CourseService.fetchEnrolledCourseNos] returns.
     *
     * This runs *ahead* of the term actually in session (選課 for the next term
     * opens weeks before it starts), so it is deliberately not a replacement
     * for [CourseService.currentSemesterCode]; it answers "which bucket do the
     * 選課清單 course numbers belong in", nothing else.
     */
    fun selectionSemesterCode(): String =
        appPreferences.semesterCatalogSelection ?: FALLBACK_TERM

    /**
     * Refreshes both cached values when the last successful fetch has aged out.
     * Safe to call from every course-fetch entry point.
     */
    suspend fun refreshIfStale() {
        val last = appPreferences.semesterCatalogRefreshedAt
        // Wall-clock, not AppClock: this is a network cache TTL, which the
        // debug clock deliberately does not move (see AppClock's contract).
        if (System.currentTimeMillis() - last <= REFRESH_TTL_MS) return
        refresh()
    }

    /**
     * Refreshes both cached values. Failures are swallowed — every caller
     * degrades to the month heuristic, which is the pre-existing behaviour.
     */
    suspend fun refresh() {
        val list = runCatching { fetch() }.getOrElse {
            Log.w(TAG, "Semester catalogue fetch failed; keeping previous values", it)
            return
        }
        val terms = list.mapNotNull { it.semester?.takeIf(String::isNotBlank) }
        if (terms.isEmpty()) return
        appPreferences.semesterCatalogTerms = terms
        appPreferences.semesterCatalogRefreshedAt = System.currentTimeMillis()
        // Exactly one entry carries LoginEnable. If NTUST ever ships zero, keep
        // the previous value rather than falling back to the heuristic that
        // caused this bug in the first place.
        openTerm(list)?.let { appPreferences.semesterCatalogSelection = it }
    }

    private suspend fun fetch(): List<SemesterInfo> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(SEMESTERS_API)
            // querycourse does strict content negotiation and answers HTTP 500
            // with an XML error body when Accept prefers text/html — the shared
            // client's default header would do exactly that.
            .header("Accept", "application/json")
            .get()
            .build()
        sessionManager.client.newCall(request).execute().use { response ->
            decodeSemesters(response.body.string())
        }
    }

    companion object {
        private const val TAG = "SemesterCatalog"

        /**
         * What every reader falls back to before the first successful fetch,
         * or when the endpoint is unreachable.
         *
         * The pinned term, *not* [SemesterCodes.heuristic]. The heuristic is
         * the known-stale answer this whole class exists to replace — anchoring
         * the fallbacks on it would drop 115-1 out of the picker and skip the
         * 選課 fetch entirely whenever the catalogue is unreachable, which is
         * the original bug. It also buys no protection against a future
         * overlap: during one, the heuristic names the term in session, so it
         * would mis-attribute 選課 enrolments exactly as the pin does.
         *
         * Matches iOS, whose fallbacks resolve through its own
         * `currentSemesterCode()` — pinned to the same term.
         */
        private val FALLBACK_TERM: String get() = AppConstants.CurrentTerm.CODE

        private const val SEMESTERS_API =
            "https://querycourse.ntust.edu.tw/QueryCourse/api/semestersinfo"

        /**
         * Term boundaries move on the scale of weeks, so this only has to beat
         * the month heuristic — hourly is plenty, and it keeps the fetch off
         * the hot path when several callers refresh at once.
         */
        private const val REFRESH_TTL_MS = 60L * 60L * 1000L

        /**
         * Terms offered by the semester picker. Six rather than the previous
         * four because the catalogue interleaves 暑期 terms (`114H`) between
         * the regular ones, so four slots would no longer reach back two full
         * years.
         */
        internal const val PICKER_DEPTH = 6

        private val gson = Gson()

        /**
         * Split out from [fetch] so the two things that actually matter about
         * the payload — its shape, and that the open term is picked by
         * `LoginEnable` rather than by position — are testable offline.
         *
         * Returns empty on malformed JSON; [refresh] reads that as "keep the
         * previous values" rather than emptying the picker.
         */
        internal fun decodeSemesters(json: String): List<SemesterInfo> {
            val type = object : TypeToken<List<SemesterInfo>>() {}.type
            return runCatching { gson.fromJson<List<SemesterInfo>?>(json, type) }
                .getOrNull()
                .orEmpty()
                // A row can arrive with a null Semester; drop it here so no
                // caller has to re-check.
                .filter { !it.semester.isNullOrBlank() }
        }

        internal fun openTerm(list: List<SemesterInfo>): String? =
            list.firstOrNull { it.loginEnable }?.semester?.takeIf(String::isNotBlank)
    }
}
