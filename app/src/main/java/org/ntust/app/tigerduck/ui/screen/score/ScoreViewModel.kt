package org.ntust.app.tigerduck.ui.screen.score

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.demo.DemoAccount
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.CourseGrade
import org.ntust.app.tigerduck.data.model.ScoreReport
import org.ntust.app.tigerduck.network.NetworkChecker
import org.ntust.app.tigerduck.network.NtustScoreError
import org.ntust.app.tigerduck.network.NtustScoreService
import org.ntust.app.tigerduck.notification.SyncSource
import org.ntust.app.tigerduck.shared.Course
import javax.inject.Inject

@HiltViewModel
class ScoreViewModel internal constructor(
    authState: StateFlow<Boolean>,
    private val storedStudentId: () -> String?,
    private val storedPassword: () -> String?,
    /** The student's cached grades, with when they were fetched; see [NtustScoreService.cachedScoreReport]. */
    private val cachedSnapshot: suspend (studentId: String) -> DataCache.ScoreReportSnapshot?,
    private val fetchReport: suspend (studentId: String, password: String, force: Boolean) -> ScoreReport,
    private val isOnline: () -> Boolean,
    private val isDemo: () -> Boolean,
    private val strings: (Int) -> String,
    /** The class table's cached courses of a term, portal and hand-added together; see [DataCache.loadCourses]. */
    private val rosterCourses: suspend (term: String) -> List<Course>,
) : ViewModel() {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        authService: AuthService,
        scoreService: NtustScoreService,
        networkChecker: NetworkChecker,
        demoAccount: DemoAccount,
        dataCache: DataCache,
    ) : this(
        authState = authService.authState,
        storedStudentId = { authService.storedStudentId },
        storedPassword = { authService.storedPassword },
        cachedSnapshot = scoreService::cachedScoreReport,
        fetchReport = { id, password, force -> scoreService.fetchScoreReport(id, password, forceRefresh = force) },
        isOnline = networkChecker::isAvailable,
        isDemo = { demoAccount.isActive },
        strings = context::getString,
        rosterCourses = { term -> dataCache.loadCourses(term) },
    )

    /** A refresh found no network; the page says so, as the other tabs do. */
    private val _noNetworkEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val noNetworkEvent: SharedFlow<Unit> = _noNetworkEvent.asSharedFlow()

    enum class RankingScope { SEMESTER, CUMULATIVE }

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

    val isLoggedIn: StateFlow<Boolean> = authState

    private val syncedAt = ScoreSyncedAt(viewModelScope, authState, cachedSnapshot)

    /** For the sync dot's "Last synced" row; see [ScoreSyncedAt]. */
    val syncedAtMs: StateFlow<Long> = syncedAt.value

    private var hasSeededCollapse = false

    /**
     * Whose report [report] is, null while signed out. Score as the first tab is never popped,
     * so this view model outlives a sign-out and the next student's sign-in.
     */
    private var reportStudentId: String? = null

    /** The student [load] has loaded for: once per student, not once per view model. */
    private var loadedFor: String? = null

    /** Runs the current student's loads and refreshes; an account change cancels it. */
    private var accountScope = newAccountScope()

    /** Courses grouped by term, newest-first. */
    val groupedCourses: List<Pair<String, List<CourseGrade>>>
        get() = _report.value.courses
            .groupBy { it.term }
            .map { (term, list) -> term to list.sortedBy { it.index ?: 0 } }
            .sortedByDescending { it.first }

    val hasContent: Boolean
        get() = _report.value.courses.isNotEmpty() || _report.value.rankings.isNotEmpty()

    init {
        // A sign-in from this page's own lock, and a sign-out with another student's sign-in
        // after it, both land while the view model lives.
        viewModelScope.launch { authState.collect { load() } }
    }

    fun load() {
        val studentId = storedStudentId()
        switchAccount(to = studentId)
        if (studentId == null || loadedFor == studentId) return
        loadedFor = studentId

        accountScope.launch {
            val cached = cachedSnapshot(studentId)
            syncedAt.show(cached)
            // report is nullable (Gson Unsafe defense) — skip the instant
            // paint on a degraded snapshot and let refresh() repopulate.
            cached?.report?.let {
                _report.value = it
                applyDefaultCollapseRule()
            }
            refresh(force = false)
        }
    }

    fun triggerRefresh() {
        if (_isRefreshing.value) return
        accountScope.launch { refresh(force = true) }
    }

    /** Empties the page for another student, or for nobody, and drops the previous one's work. */
    private fun switchAccount(to: String?) {
        if (to == reportStudentId) return
        reportStudentId = to
        loadedFor = null
        accountScope.cancel()
        accountScope = newAccountScope()
        _report.value = ScoreReport.EMPTY
        _collapsedTerms.value = emptySet()
        hasSeededCollapse = false
        _errorMessage.value = null
        _isRefreshing.value = false
    }

    /** A child of [viewModelScope] whose failures stay its own, so one refresh's throw cannot cancel the page. */
    private fun newAccountScope(): CoroutineScope =
        CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))

    fun setRankingScope(scope: RankingScope) {
        _rankingScope.value = scope
    }

    fun toggleCollapse(term: String) {
        val current = _collapsedTerms.value
        _collapsedTerms.value = if (term in current) current - term else current + term
    }

    fun isCollapsed(term: String): Boolean = term in _collapsedTerms.value

    /**
     * The class-table course [course] is, from the cached roster of its own
     * term, or null when the app has none: the score page names a course only
     * by number and term, and NTUST reissues numbers across terms when a
     * student retakes, so another term's roster is never consulted. Read on
     * demand, not with the report: a transcript spans years of terms, and
     * only the tapped course's roster is wanted.
     */
    suspend fun rosterCourse(course: CourseGrade): Course? {
        // The cache is keyed by the class table's semester code, which the
        // score page's term may not spell the same way; see semesterCode.
        val semester = ScoreCourseRoster.semesterCode(course.term) ?: return null
        return ScoreCourseRoster.match(rosterCourses(semester), course)
    }

    private suspend fun refresh(force: Boolean) {
        if (_isRefreshing.value) return
        val studentId = storedStudentId()
        val password = storedPassword()
        if (studentId == null || password == null) {
            _errorMessage.value = strings(R.string.common_not_signed_in)
            return
        }
        // Another student's page from here on; load() empties it first.
        if (studentId != reportStudentId) return
        if (!isOnline()) {
            _noNetworkEvent.tryEmit(Unit)
            return
        }
        // The demo account has no scores and no server to ask for them: the
        // empty state, not the refusal reported as a load failure.
        if (isDemo()) return

        _isRefreshing.value = true
        _errorMessage.value = null
        try {
            val fresh = syncedAt.afterRefresh(studentId) { fetchReport(studentId, password, force) }
            // The student signed out while the school answered; cancelled with
            // the account's work, unless the fetch could not be stopped.
            if (studentId != reportStudentId) return
            _report.value = fresh
            applyDefaultCollapseRule()
        } catch (e: CancellationException) {
            throw e
        } catch (e: NtustScoreError) {
            if (studentId != reportStudentId) return
            _errorMessage.value = when (e) {
                is NtustScoreError.NotAuthenticated -> strings(R.string.common_not_signed_in)
                is NtustScoreError.RedirectedToSSO -> strings(R.string.score_error_sign_in_expired)
                is NtustScoreError.InvalidResponse -> strings(R.string.score_error_invalid_response)
                is NtustScoreError.ParseFailed -> strings(R.string.score_error_parse_failed)
            }
        } catch (e: Exception) {
            if (studentId != reportStudentId) return
            _errorMessage.value = e.message ?: strings(R.string.score_error_load_failed)
        } finally {
            if (studentId == reportStudentId) _isRefreshing.value = false
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
