package org.ntust.app.tigerduck.ui.screen.score

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.model.CourseGrade
import org.ntust.app.tigerduck.data.model.ScoreReport
import org.ntust.app.tigerduck.data.model.SemesterRanking
import org.ntust.app.tigerduck.network.NetworkChecker
import org.ntust.app.tigerduck.network.NtustScoreError
import org.ntust.app.tigerduck.network.NtustScoreService
import javax.inject.Inject

@HiltViewModel
class ScoreViewModel @Inject constructor(
    private val authService: AuthService,
    private val scoreService: NtustScoreService,
    private val networkChecker: NetworkChecker,
) : ViewModel() {

    enum class RankingScope { SEMESTER, CUMULATIVE;
        val displayName: String get() = if (this == SEMESTER) "學期" else "累計"
    }

    private val _report = MutableStateFlow(ScoreReport.EMPTY)
    val report: StateFlow<ScoreReport> = _report.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _rankingScope = MutableStateFlow(RankingScope.SEMESTER)
    val rankingScope: StateFlow<RankingScope> = _rankingScope.asStateFlow()

    private val _collapsedTerms = MutableStateFlow<Set<String>>(emptySet())
    val collapsedTerms: StateFlow<Set<String>> = _collapsedTerms.asStateFlow()

    val isLoggedIn: StateFlow<Boolean> = authService.authState

    private var hasLoaded = false
    private var hasSeededCollapse = false

    /** Courses grouped by term, newest-first. */
    val groupedCourses: List<Pair<String, List<CourseGrade>>>
        get() = _report.value.courses
            .groupBy { it.term }
            .map { (term, list) -> term to list.sortedBy { it.index ?: 0 } }
            .sortedByDescending { it.first }

    /** Rankings in chronological order for trend display. */
    val rankingTrend: List<SemesterRanking>
        get() = _report.value.rankings.sortedBy { it.term }

    fun ranking(term: String): SemesterRanking? =
        _report.value.rankings.firstOrNull { it.term == term }

    val hasContent: Boolean
        get() = _report.value.courses.isNotEmpty() || _report.value.rankings.isNotEmpty()

    fun load() {
        if (hasLoaded) return
        hasLoaded = true
        val studentId = authService.storedStudentId ?: return

        viewModelScope.launch {
            val cached = scoreService.cachedScoreReport(studentId)
            if (cached != null) {
                _report.value = cached.report
                applyDefaultCollapseRule()
            }
            refresh(force = false)
        }
    }

    fun triggerRefresh() {
        if (_isRefreshing.value) return
        viewModelScope.launch { refresh(force = true) }
    }

    fun setRankingScope(scope: RankingScope) {
        _rankingScope.value = scope
    }

    fun toggleCollapse(term: String) {
        val current = _collapsedTerms.value
        _collapsedTerms.value = if (term in current) current - term else current + term
    }

    fun isCollapsed(term: String): Boolean = term in _collapsedTerms.value

    private suspend fun refresh(force: Boolean) {
        if (_isRefreshing.value) return
        val studentId = authService.storedStudentId
        val password = authService.storedPassword
        if (studentId == null || password == null) {
            _errorMessage.value = "未登入"
            return
        }
        if (!networkChecker.isAvailable()) return

        _isRefreshing.value = true
        _errorMessage.value = null
        try {
            val fresh = scoreService.fetchScoreReport(studentId, password, forceRefresh = force)
            _report.value = fresh
            applyDefaultCollapseRule()
        } catch (e: NtustScoreError) {
            _errorMessage.value = when (e) {
                is NtustScoreError.NotAuthenticated -> "未登入"
                is NtustScoreError.RedirectedToSSO -> "登入已過期"
                is NtustScoreError.InvalidResponse -> "伺服器回應異常"
                is NtustScoreError.ParseFailed -> "無法解析成績資料"
            }
        } catch (e: Exception) {
            _errorMessage.value = e.message ?: "載入失敗"
        } finally {
            _isRefreshing.value = false
        }
    }

    /** Collapse every term except the most recent the first time data lands. */
    private fun applyDefaultCollapseRule() {
        val terms = _report.value.courses.map { it.term }.toSet()
        val latest = terms.maxOrNull() ?: return
        if (!hasSeededCollapse) {
            _collapsedTerms.value = terms.filter { it != latest }.toSet()
            hasSeededCollapse = true
        } else {
            // Drop stale terms that no longer exist.
            _collapsedTerms.value = _collapsedTerms.value.intersect(terms)
        }
    }
}
