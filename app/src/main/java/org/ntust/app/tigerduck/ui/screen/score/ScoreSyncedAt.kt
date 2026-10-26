package org.ntust.app.tigerduck.ui.screen.score

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.data.cache.DataCache

/**
 * When the grades on the Score page were fetched, for its "Last synced" row:
 * the score cache's own [DataCache.ScoreReportSnapshot.cachedAt], which only a
 * fetch that succeeded writes, per student, so it survives a restart. 0 for
 * never, and from a sign-out on. Apart from [ScoreViewModel] so a test can run
 * it.
 */
internal class ScoreSyncedAt(
    scope: CoroutineScope,
    signedIn: Flow<Boolean>,
    /** The student's cached grades; see [org.ntust.app.tigerduck.network.NtustScoreService.cachedScoreReport]. */
    private val snapshot: suspend (studentId: String) -> DataCache.ScoreReportSnapshot?,
) {
    private val _value = MutableStateFlow(0L)
    val value: StateFlow<Long> = _value.asStateFlow()

    init {
        // The grades were the account's that left.
        scope.launch { signedIn.collect { if (!it) _value.value = 0L } }
    }

    /** The page opened on [cached], the snapshot it painted before asking the server. */
    fun show(cached: DataCache.ScoreReportSnapshot?) {
        _value.value = cached?.cachedAt?.time ?: 0L
    }

    /**
     * Runs [refresh], then reads the date again: the fetch's own, or the
     * cached one a refresh the cache answered within its lifetime kept. A
     * refresh that throws leaves it where it was.
     */
    suspend fun <T> afterRefresh(studentId: String, refresh: suspend () -> T): T =
        refresh().also { show(snapshot(studentId)) }
}
