package org.ntust.app.tigerduck.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.ScoreReport
import javax.inject.Inject
import javax.inject.Singleton

sealed class NtustScoreError : Exception() {
    object NotAuthenticated : NtustScoreError()
    object RedirectedToSSO : NtustScoreError()
    object InvalidResponse : NtustScoreError()
    object ParseFailed : NtustScoreError()
}

/**
 * Fetches the NTUST StuScoreQueryServ "DisplayAll" HTML, parses it into a
 * [ScoreReport], and caches the result on disk for up to 24h. Mirrors the
 * iOS NTUSTScoreService flow so cached-first gates stay consistent across
 * both clients.
 */
@Singleton
class NtustScoreService @Inject constructor(
    private val sessionManager: NtustSessionManager,
    private val ssoLoginService: SsoLoginService,
    private val dataCache: DataCache,
) {
    private val client: OkHttpClient get() = sessionManager.client

    suspend fun fetchScoreReport(
        studentId: String,
        password: String,
        forceRefresh: Boolean = false
    ): ScoreReport = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            val cached = dataCache.loadScoreReport(studentId)
            if (cached != null &&
                System.currentTimeMillis() - cached.cachedAt.time < CACHE_TTL_MS
            ) return@withContext cached.report
        }

        if (!sessionManager.cookiesValid) {
            val loggedIn = ssoLoginService.ensureServiceLogin(SCORE_ROOT_URL, studentId, password)
            if (!loggedIn) throw NtustScoreError.NotAuthenticated
        }

        val html = fetchHtml(studentId, password)
        val report = NtustScoreParser.parse(html)

        // A successful fetch that parses to a fully empty report usually
        // means the HTML was actually the SSO page disguised as a 200 —
        // don't poison the cache with that.
        if (report == ScoreReport.EMPTY) throw NtustScoreError.ParseFailed

        dataCache.saveScoreReport(report, studentId)
        report
    }

    /** Cached snapshot without the network call, for instant first paint. */
    suspend fun cachedScoreReport(studentId: String): DataCache.ScoreReportSnapshot? =
        dataCache.loadScoreReport(studentId)

    suspend fun invalidateCache(studentId: String) = dataCache.invalidateScoreReport(studentId)

    private suspend fun fetchHtml(studentId: String, password: String): String {
        val (html, finalUrl) = get(SCORE_DISPLAY_URL)
        if (!finalUrl.contains("ssoam2.ntust.edu.tw")) return html

        // SSO bounced us — try one silent re-login and retry once.
        val loggedIn = ssoLoginService.ensureServiceLogin(SCORE_ROOT_URL, studentId, password)
        if (!loggedIn) throw NtustScoreError.NotAuthenticated

        val (retryHtml, retryUrl) = get(SCORE_DISPLAY_URL)
        if (retryUrl.contains("ssoam2.ntust.edu.tw")) throw NtustScoreError.RedirectedToSSO
        return retryHtml
    }

    private fun get(url: String): Pair<String, String> {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw NtustScoreError.InvalidResponse
            val html = response.body?.string() ?: throw NtustScoreError.InvalidResponse
            return html to response.request.url.host
        }
    }

    companion object {
        private const val SCORE_ROOT_URL = "https://stuinfosys.ntust.edu.tw/StuScoreQueryServ/"
        private const val SCORE_DISPLAY_URL =
            "https://stuinfosys.ntust.edu.tw/StuScoreQueryServ/StuScoreQuery/DisplayAll"

        /** 24h — grade updates are a weeks-to-months cadence, stale-hit dominates. */
        private const val CACHE_TTL_MS: Long = 24 * 60 * 60 * 1000
    }
}
