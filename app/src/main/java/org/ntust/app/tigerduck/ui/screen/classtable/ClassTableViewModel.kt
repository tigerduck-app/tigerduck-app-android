package org.ntust.app.tigerduck.ui.screen.classtable

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.data.CourseRosterMerge
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.CourseColorStore
import org.ntust.app.tigerduck.shared.OngoingCourseInfo
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.shared.computeOngoingCourses
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.data.model.TimetablePeriod
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.notification.SyncSource
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.network.MoodleService
import org.ntust.app.tigerduck.network.NetworkChecker
import org.ntust.app.tigerduck.network.SemesterCatalog
import org.ntust.app.tigerduck.network.model.MoodleEnrolledCourse
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import javax.inject.Inject

@HiltViewModel
class ClassTableViewModel @Inject constructor(
    private val networkChecker: NetworkChecker,
    private val authService: AuthService,
    internal val courseService: CourseService,
    private val moodleService: MoodleService,
    private val dataCache: DataCache,
    private val courseColorStore: CourseColorStore,
    private val appPreferences: AppPreferences,
    private val semesterCatalog: SemesterCatalog,
    private val widgetUpdater: org.ntust.app.tigerduck.widget.WidgetUpdater,
    private val pushApiClient: org.ntust.app.tigerduck.push.PushApiClient,
) : ViewModel() {

    val isSyncLocalOnly = appPreferences.lastSyncSource
        .map { appPreferences.cloudSyncEnabled && it == SyncSource.LOCAL }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _courses = MutableStateFlow<List<Course>>(emptyList())
    val courses: StateFlow<List<Course>> = _courses

    private val _assignments = MutableStateFlow<List<Assignment>>(emptyList())
    val assignments: StateFlow<List<Assignment>> = _assignments

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    val isLoggedIn: StateFlow<Boolean> = authService.authState

    private val _selectedCourse = MutableStateFlow<Course?>(null)
    val selectedCourse: StateFlow<Course?> = _selectedCourse

    // Cached idnumber → numeric Moodle course id, harvested off the
    // `fetchEnrolledCourses` response. The course-detail popup uses this
    // to build the `/course/view.php?id=<N>` deep link — Moodle's web
    // endpoint requires the numeric id, not the idnumber. Persisted via
    // [DataCache.saveMoodleCourseIds] so a transient Moodle failure
    // doesn't make the button vanish; [DataCache.clearAllUserData] wipes
    // it on logout so a stale map can't survive an account switch.
    private val _moodleCourseIdByIdnumber = MutableStateFlow<Map<String, Int>>(emptyMap())

    // Per-locale custom course names: courseNo → locale ("zh"/"en") → display name.
    // Loaded from DataCache.courseCustomNames on init; written on every rename.
    // The resolved value for the current locale is stamped onto
    // Course.customCourseName when building the in-memory course list.
    private var courseCustomNames: CustomNameMap = emptyMap()

    /** Current course-API locale ("zh" or "en"), derived from the user's app language. */
    private val currentCourseLocale: String
        get() = AppLanguageManager.resolvedCourseApiLanguage(appPreferences.appLanguage)

    private fun resolveCustomNames(courses: List<Course>): List<Course> =
        CourseNameOverrides.resolve(courses, courseCustomNames, currentCourseLocale)

    private val _selectedWeekday = MutableStateFlow<Int?>(null)
    private val _selectedPeriodId = MutableStateFlow<String?>(null)

    // Stored pick, else the newest published term, else the pinned term.
    // The catalogue can land after construction on a cold launch, so
    // followNewestSemesterIfUnpicked re-applies the rule once it does.
    private val _currentSemester = MutableStateFlow(
        semesterCatalog.selectedSemester(appPreferences.classTableSelectedSemester)
    )
    val currentSemester: StateFlow<String> = _currentSemester

    /**
     * Terms the picker offers, newest first, as published by NTUST — see
     * [SemesterCatalog]. A `StateFlow` rather than a computed getter so a term
     * the school publishes ahead of the month heuristic (115-1 opened weeks
     * before the heuristic rolled off 114-2) becomes selectable in the same
     * session the catalogue lands.
     */
    private val _availableSemesters = MutableStateFlow(semesterOptions(_currentSemester.value))
    val availableSemesters: StateFlow<List<String>> = _availableSemesters

    private val _currentDayTime = MutableStateFlow(currentDayTime())
    val currentMinute: StateFlow<Int> = _currentDayTime
        .map { it.minuteOfDay }
        .stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            currentDayTime().minuteOfDay
        )

    // One-shot UI events. These live above `init` with the rest of the
    // state for the same reason every other field does: init's collectors
    // run synchronously during construction under Main.immediate, so a
    // field declared below it is still null when they reach it.
    private val _tripleConflictEvent = MutableSharedFlow<TripleConflictError>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val tripleConflictEvent: SharedFlow<TripleConflictError> = _tripleConflictEvent.asSharedFlow()

    private val _noNetworkEvent = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val noNetworkEvent: SharedFlow<Unit> = _noNetworkEvent.asSharedFlow()

    private val _syncCompleteEvent = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val syncCompleteEvent: SharedFlow<Unit> = _syncCompleteEvent.asSharedFlow()

    private var hasLoaded = false

    init {
        viewModelScope.launch {
            // Tick at 5s so transitions land within at most a few seconds of
            // the wall-clock minute boundary (e.g., the "現在課程" card appears
            // ~5s after a class starts, not up to a minute later). The
            // _currentDayTime MutableStateFlow dedupes by structural equality,
            // so emissions only propagate when minuteOfDay actually changes —
            // downstream collectors aren't woken on every poll.
            while (true) {
                kotlinx.coroutines.delay(5_000)
                _currentDayTime.value = currentDayTime()
            }
        }
        viewModelScope.launch {
            // Reload course state whenever Settings resets tile colors so
            // our in-memory _courses doesn't fight the freshly-written cache.
            // Re-read the currently-viewed semester — not the live one —
            // so picking a past term doesn't get snapped back to the
            // current semester on a color reset.
            courseColorStore.changeEvent.collect {
                val fresh = resolveCustomNames(dataCache.loadCourses(_currentSemester.value))
                if (fresh.isNotEmpty()) {
                    _courses.value = fresh
                    TigerDuckTheme.buildCourseColorMap(fresh)
                    // Widget refresh is driven by CourseColorStore itself, so
                    // subscribers don't need to re-trigger it.
                }
            }
        }
        viewModelScope.launch {
            dataCache.backgroundSyncVersion.drop(1).collect {
                val semester = _currentSemester.value
                val fresh = resolveCustomNames(dataCache.loadCourses(semester))
                if (fresh.isNotEmpty()) {
                    _courses.value = fresh
                    TigerDuckTheme.buildCourseColorMap(fresh)
                }
                _assignments.value = dataCache.loadAssignments()
            }
        }
        viewModelScope.launch {
            // Clear on logout, refresh on login.
            authService.authState.collect { isAuthed ->
                if (!isAuthed) {
                    _courses.value = emptyList()
                    _assignments.value = emptyList()
                    _selectedCourse.value = null
                    _moodleCourseIdByIdnumber.value = emptyMap()
                    courseCustomNames = emptyMap()
                    hasLoaded = false
                    TigerDuckTheme.clearCourseColorMap()
                } else {
                    fetchData()
                }
            }
        }
        viewModelScope.launch {
            // Language change → re-fetch from the network so course names
            // come back in the new locale.
            appPreferences.appLanguageChanged.collect {
                courseService.clearInMemoryLookupCache()
                if (authService.authState.value) refresh()
            }
        }
        viewModelScope.launch {
            // Abbreviation toggle is a pure display transform — re-derive
            // names from the lookup cache without a network call. Course toggle,
            // classroom toggle, and the Mandarin classroom display picker all
            // feed the same relabel pass.
            merge(
                appPreferences.useEnglishCourseAbbreviationChanged,
                appPreferences.useEnglishClassroomAbbreviationChanged,
                appPreferences.classroomMandarinDisplayChanged,
            ).collect {
                val semester = _currentSemester.value
                val relabeled = courseService.relabelCoursesForCurrentAbbrSetting(
                    semester, _courses.value
                )
                if (relabeled != _courses.value) {
                    _courses.value = relabeled
                    dataCache.saveCourses(relabeled, semester)
                    TigerDuckTheme.buildCourseColorMap(relabeled)
                }
            }
        }
    }

    private fun currentDayTime(): ClassTableSelection.DayTime =
        ClassTableSelection.dayTimeFrom(AppClock.calendar())

    /** The actual live semester code (not whatever the user picked). */
    val liveSemesterCode: String
        get() = courseService.currentSemesterCode()

    /**
     * Keeps the persisted selection selectable even after it ages out of the
     * catalogue window — a picker whose selected value matches no option
     * renders blank.
     */
    private fun semesterOptions(selected: String): List<String> {
        val options = semesterCatalog.availableSemesters()
        return if (options.contains(selected)) options else options + selected
    }

    /**
     * The catalogue can land after construction on a cold launch, so re-apply
     * the "never picked → newest term" rule once it does.
     *
     * Deliberately does not persist the move: an untouched picker should keep
     * tracking the newest term rather than freezing on whichever one happened
     * to be newest at first launch.
     */
    private suspend fun followNewestSemesterIfUnpicked() {
        if (appPreferences.classTableSelectedSemester != null) return
        val newest = semesterCatalog.availableSemesters().firstOrNull() ?: return
        if (newest == _currentSemester.value) return
        _currentSemester.value = newest
        // The grid is still showing the term we just moved off. Swap in the
        // new term's cache immediately rather than leaving the wrong timetable
        // up for the length of the network round-trip.
        val cached = dataCache.loadCourses(newest)
        _courses.value = cached
        TigerDuckTheme.buildCourseColorMap(cached)
    }

    /** Format semester code for display, e.g. "1142" → "114-2". */
    fun displayLabel(code: String): String {
        if (code.length < 2) return code
        return code.dropLast(1) + "-" + code.last()
    }

    fun setSemester(code: String) {
        if (code == _currentSemester.value) return
        appPreferences.classTableSelectedSemester = code
        _currentSemester.value = code
        viewModelScope.launch {
            val cached = resolveCustomNames(dataCache.loadCourses(code))
            _courses.value = cached
            TigerDuckTheme.buildCourseColorMap(cached)
            fetchData()
        }
    }

    val totalCredits: Int get() = _courses.value.sumOf { it.credits }

    val todayCourses: List<Course>
        get() {
            // Outside the term there is no "today" worth showing — the
            // carousel would either be empty or surface a stale day. Empty
            // here also hides the section, which keys off `isNotEmpty()`.
            if (!AppConstants.CurrentTerm.isInSession()) return emptyList()
            return ClassTableSelection.coursesOn(_courses.value, _currentDayTime.value.weekday)
        }

    val activeWeekdays: List<Int>
        get() = ClassTableCellLayout.activeWeekdays(_courses.value)

    val activePeriods: List<TimetablePeriod>
        get() = ClassTableCellLayout.activePeriods(_courses.value)

    /**
     * Title for the course-detail popup. A user-supplied [Course.customCourseName]
     * wins; otherwise falls back to the cached full (un-abbreviated) name from
     * the course-detail lookup, then to the stored [Course.courseName] when no
     * cache entry exists (manual entries, Moodle-only fallbacks).
     */
    val selectedCourseFullName: String?
        get() {
            val course = _selectedCourse.value ?: return null
            course.customCourseName?.let { return it }
            return courseService.cachedFullCourseName(_currentSemester.value, course.courseNo)
        }

    /**
     * The default (non-overridden) name for [course] — full name from the
     * lookup cache when available, else the derived [Course.courseName].
     * Matches the source the popup title uses, so the rename dialog stays
     * consistent whether opened from the popup's edit pencil or a cell
     * long-press.
     */
    fun defaultNameFor(course: Course): String =
        courseService.cachedFullCourseName(_currentSemester.value, course.courseNo)
            ?: course.courseName

    val selectedCourseTimeRange: String?
        get() {
            val course = _selectedCourse.value ?: return null
            val weekday = _selectedWeekday.value ?: return null
            return ClassTableSelection.timeRange(course, weekday)
        }

    /**
     * Classroom string scoped to the selected weekday — so tapping a Wed cell
     * shows only the Wed room even when the course meets in different rooms
     * on different days. Falls back to the deduped flat classroom string when
     * no weekday is selected (shouldn't happen via the grid, but covers
     * external selection paths).
     */
    val selectedCourseClassroom: String
        get() {
            val course = _selectedCourse.value ?: return ""
            return ClassTableSelection.classroom(
                course, _selectedWeekday.value, _selectedPeriodId.value,
            )
        }

    fun isCourseFinishedToday(course: Course): Boolean =
        ClassTableSelection.isFinishedAt(course, _currentDayTime.value)

    val ongoingCourses: List<OngoingCourseInfo>
        get() {
            val dayTime = _currentDayTime.value
            return computeOngoingCourses(_courses.value, dayTime.weekday, dayTime.minuteOfDay)
        }

    fun coursesAt(weekday: Int, period: String): List<Course> =
        ClassTableCellLayout.coursesAt(_courses.value, weekday, period)

    /** Grid geometry lives in [ClassTableCellLayout]; these bind it to state. */
    fun cellRole(weekday: Int, periodIndex: Int): CellRole =
        ClassTableCellLayout.roleAt(_courses.value, activePeriods, weekday, periodIndex)

    /**
     * [cellRole] for callers that already hold the period list.
     *
     * The no-[periods] overload reads the `activePeriods` getter, which walks
     * every course's schedule, builds a period-id set and filters the
     * chronological order — per call. The grid asks for a role once per cell,
     * so roughly seven weekdays x fourteen periods rebuild the same list a
     * hundred times per recomposition. TimetableGrid is already handed the
     * memoized list as `periods`; passing it back through skips all of that.
     */
    fun cellRole(
        periods: List<TimetablePeriod>,
        weekday: Int,
        periodIndex: Int,
    ): CellRole = ClassTableCellLayout.roleAt(_courses.value, periods, weekday, periodIndex)

    fun wouldCauseTripleConflict(candidate: Course): TripleConflictError? =
        ClassTableCellLayout.findTripleConflict(_courses.value, candidate)

    // hasAssignment / assignmentsFor were removed: they read _assignments.value
    // outside the snapshot system, so composables calling them never recomposed
    // when the assignments fetch landed. The screen now collects [assignments]
    // and derives badge state itself.

    fun selectCourse(course: Course, weekday: Int, periodId: String) {
        _selectedWeekday.value = weekday
        _selectedPeriodId.value = periodId
        _selectedCourse.value = course
    }

    /**
     * Numeric Moodle course id for [course], or null when we have no entry
     * for it in the idnumber map yet (e.g. manual courses without a Moodle
     * counterpart, or a cold start before the first sync). The detail
     * popup uses this to decide whether to render the "open in Moodle"
     * affordance.
     */
    fun moodleCourseIdFor(course: Course): Int? {
        val idnumber = course.moodleIdNumber?.takeIf { it.isNotEmpty() } ?: return null
        return _moodleCourseIdByIdnumber.value[idnumber]
    }

    fun clearSelection() {
        _selectedCourse.value = null
    }

    val existingCourseNos: Set<String>
        get() = _courses.value.map { it.courseNo }.toSet()

    fun addCourse(course: Course): Boolean {
        wouldCauseTripleConflict(course)?.let {
            _tripleConflictEvent.tryEmit(it)
            return false
        }
        val flagged = course.copy(isManual = true)
        val updated = _courses.value + flagged
        _courses.value = updated
        viewModelScope.launch {
            val deleted = dataCache.loadDeletedCourseNos()
            if (course.courseNo in deleted) {
                dataCache.saveDeletedCourseNos(deleted - course.courseNo)
            }
            dataCache.saveCourses(updated, _currentSemester.value)
            val forceKey = "client:${_currentSemester.value}:${course.courseNo}"
            runCatching { pushApiClient.uploadCourses(updated, _currentSemester.value, forceKeys = listOf(forceKey)) }
            widgetUpdater.requestUpdate()
        }
        TigerDuckTheme.buildCourseColorMap(updated)
        return true
    }

    /**
     * Sets the user-supplied display override for [courseNo]. A blank input or
     * one that matches the current default — either the abbreviation-aware
     * [Course.courseName] or the full cached name the rename dialog prefilled
     * — clears the override so the course re-follows the abbreviation toggle.
     */
    fun setCustomCourseName(courseNo: String, newName: String) {
        val trimmed = newName.trim()
        val locale = currentCourseLocale
        val updated = _courses.value.map { course ->
            if (course.courseNo != courseNo) return@map course
            val defaultName = defaultNameFor(course)
            val override = trimmed.takeIf {
                it.isNotEmpty() && it != course.courseName && it != defaultName
            }
            courseCustomNames =
                CourseNameOverrides.withOverride(courseCustomNames, courseNo, locale, override)
            course.copy(customCourseName = override)
        }
        _courses.value = updated
        viewModelScope.launch {
            dataCache.saveCourseCustomNames(courseCustomNames)
            dataCache.saveCourses(updated, _currentSemester.value)
            widgetUpdater.requestUpdate()
        }
        syncCourseOverride(courseNo, customName = trimmed.ifEmpty { "" }, locale = locale)
    }

    /** Clears any user override for [courseNo], restoring the default name. */
    fun revertCourseName(courseNo: String) {
        val locale = currentCourseLocale
        courseCustomNames =
            CourseNameOverrides.withOverride(courseCustomNames, courseNo, locale, null)
        val updated = _courses.value.map { course ->
            if (course.courseNo == courseNo) course.copy(customCourseName = null) else course
        }
        _courses.value = updated
        viewModelScope.launch {
            dataCache.saveCourseCustomNames(courseCustomNames)
            dataCache.saveCourses(updated, _currentSemester.value)
            widgetUpdater.requestUpdate()
        }
        syncCourseOverride(courseNo, customName = "", locale = locale)
    }

    private fun resolveMoodleNumericId(course: Course): Int? {
        course.moodleNumericCourseId?.let { return it }
        val idnumber = course.moodleIdNumber?.takeIf { it.isNotEmpty() } ?: return null
        return _moodleCourseIdByIdnumber.value[idnumber]
    }

    fun deleteCourse(courseNo: String) {
        val updated = _courses.value.filter { it.courseNo != courseNo }
        _courses.value = updated
        val semester = _currentSemester.value
        viewModelScope.launch {
            val deleted = dataCache.loadDeletedCourseNos() + courseNo
            dataCache.saveDeletedCourseNos(deleted)
            dataCache.saveCourses(updated, semester)
            widgetUpdater.requestUpdate()
            val courseKey = "client:$semester:$courseNo"
            runCatching { pushApiClient.deleteCourse(courseKey) }
                .onFailure { e -> Log.w("ClassTable", "deleteCourse backend failed", e) }
            runCatching { pushApiClient.uploadCourses(updated, semester) }
                .onFailure { e -> Log.w("ClassTable", "uploadCourses after delete failed", e) }
        }
        TigerDuckTheme.buildCourseColorMap(updated)
    }

    /**
     * Assigns [newHex] to [courseNo]. Pass null to clear the user-picked color
     * and fall back to hash-based assignment. Any other course whose custom
     * color matches [newHex] is automatically cleared so it gets reassigned
     * through the palette probe logic.
     */
    fun updateCourseColor(courseNo: String, newHex: String?) {
        val normalized = newHex?.uppercase()
        val updated = _courses.value.map { course ->
            when {
                course.courseNo == courseNo -> course.copy(customColorHex = normalized)
                normalized != null && course.customColorHex?.uppercase() == normalized ->
                    course.copy(customColorHex = null)

                else -> course
            }
        }
        _courses.value = updated
        TigerDuckTheme.buildCourseColorMap(updated)
        viewModelScope.launch {
            dataCache.saveCourses(updated, _currentSemester.value)
            widgetUpdater.requestUpdate()
        }
        syncCourseOverride(courseNo, colorHex = normalized)
    }

    private fun syncCourseOverride(
        courseNo: String,
        colorHex: String? = null,
        customName: String? = null,
        locale: String? = null,
    ) {
        val course = _courses.value.find { it.courseNo == courseNo } ?: return
        val moodleId = course.moodleIdNumber ?: return
        viewModelScope.launch {
            try {
                pushApiClient.patchCourseOverride(
                    moodleId,
                    colorHex = colorHex,
                    customName = customName,
                    locale = locale,
                )
            } catch (e: Exception) {
                Log.w("ClassTableVM", "course override FAILED: $courseNo", e)
            }
        }
    }

    fun load() {
        if (hasLoaded) return
        hasLoaded = true
        viewModelScope.launch {
            val cached = dataCache.loadCourses(_currentSemester.value)
            val cachedA = dataCache.loadAssignments()
            val cachedMoodleIds = dataCache.loadMoodleCourseIds()
            courseCustomNames = dataCache.loadCourseCustomNames()
                .mapValues { it.value.toMutableMap() }
                .toMutableMap()
            if (cached.isNotEmpty()) {
                _courses.value = resolveCustomNames(cached)
                _assignments.value = cachedA
                TigerDuckTheme.buildCourseColorMap(_courses.value)
            }
            if (cachedMoodleIds.isNotEmpty()) {
                _moodleCourseIdByIdnumber.value = cachedMoodleIds
            }
            fetchData()
        }
    }

    fun resetCourses() {
        viewModelScope.launch {
            dataCache.saveDeletedCourseNos(emptySet())
            runCatching { pushApiClient.deleteAllCourses() }
                .onFailure { Log.w("ClassTableVM", "deleteAllCourses failed (non-fatal)", it) }
            fetchData()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            if (!networkChecker.isAvailable()) {
                _noNetworkEvent.tryEmit(Unit)
                kotlinx.coroutines.yield()
                _isLoading.value = false
                return@launch
            }
            fetchData()
        }
    }

    private suspend fun fetchData() {
        val studentId = authService.storedStudentId ?: run { _isLoading.value = false; return }
        val password = authService.storedPassword ?: run { _isLoading.value = false; return }
        if (!networkChecker.isAvailable()) {
            _isLoading.value = false; return
        }
        _isLoading.value = true
        try {
            // Before anything else, so a newly-published term is offered by the
            // picker and 選課 enrolments are attributed to the term the portal
            // is actually serving. TTL-throttled, so this costs one request an
            // hour no matter how often the class table refreshes.
            semesterCatalog.refreshIfStale()
            followNewestSemesterIfUnpicked()
            val semester = _currentSemester.value
            _availableSemesters.value = semesterOptions(semester)
            // Assignments are "upcoming from now", so they belong to the term
            // in session.
            val isCurrentSemester = semester == courseService.currentSemesterCode()
            // 選課 serves exactly one term and its 選課清單 page has no term
            // marker, so its course numbers belong to whichever term the
            // catalogue reports as open — not to whatever term is in session.
            // Keying this off currentSemesterCode() mis-filed 115-1 enrolments
            // into 114-2 for the weeks between 選課 opening and the month
            // heuristic rolling over.
            val servesSelectionSemester = semester == semesterCatalog.selectionSemesterCode()
            Log.i(
                "ClassTableVM",
                "fetchData start: semester=$semester isCurrent=$isCurrentSemester " +
                    "servesSelection=$servesSelectionSemester"
            )

            // Kick off the two enrolment sources concurrently — they hit
            // different hosts (courseselection.ntust.edu.tw vs.
            // moodle2.ntust.edu.tw) and neither depends on the other.
            //   Source 1: course-selection portal, open term only.
            //   Source 2: Moodle enrolment list, all semesters (needed for
            //             historical terms and for fanning out assignments).
            val (selectionNos, moodleAll) = coroutineScope {
                val selectionDef = async {
                    if (servesSelectionSemester) {
                        try {
                            courseService.fetchEnrolledCourseNos(studentId, password)
                        } catch (e: Exception) {
                            Log.e("ClassTableVM", "Failed to fetch course list", e)
                            emptyList()
                        }
                    } else emptyList()
                }
                val moodleDef = async {
                    try {
                        moodleService.fetchEnrolledCourses()
                    } catch (e: Exception) {
                        Log.e("ClassTableVM", "Failed to fetch Moodle enrolled courses", e)
                        emptyList<MoodleEnrolledCourse>()
                    }
                }
                selectionDef.await() to moodleDef.await()
            }
            Log.i("ClassTableVM", "selectionNos=${selectionNos.size} -> $selectionNos")
            Log.i(
                "ClassTableVM",
                "moodleAll=${moodleAll.size} sampleIdnums=${
                    moodleAll.take(5).map { it.idnumber }
                } semesters=${moodleAll.map { it.semesterCode }.distinct()}"
            )
            // Build the idnumber → numeric-id map across ALL semesters
            // (not just the one currently displayed) so the detail popup's
            // Moodle button works for historical semesters too. Skip entries
            // missing either field — both are required to build a usable
            // deep link. Only overwrite when Moodle returned something so a
            // transient failure keeps the previously cached mapping live;
            // account switches still reset it because logout wipes the
            // cache file via DataCache.clearAllUserData.
            if (moodleAll.isNotEmpty()) {
                val fresh = moodleAll
                    .mapNotNull { c -> c.idnumber?.takeIf { it.isNotEmpty() }?.let { it to c.id } }
                    .toMap()
                _moodleCourseIdByIdnumber.value = fresh
                dataCache.saveMoodleCourseIds(fresh)
            }

            val moodleForSem = CourseRosterMerge.moodleCoursesFor(semester, moodleAll)
            val moodleByNo = moodleForSem.associateBy { it.courseNo }
            Log.i(
                "ClassTableVM",
                "moodleForSem[$semester]=${moodleForSem.size} -> ${moodleForSem.map { it.courseNo }}"
            )

            val orderedCourseNos = CourseRosterMerge.rosterOrder(selectionNos, moodleForSem)
            Log.i("ClassTableVM", "orderedCourseNos=${orderedCourseNos.size} -> $orderedCourseNos")

            // Course detail lookups and assignment fetching are fully
            // independent — run them concurrently and apply each result as
            // soon as it lands.
            coroutineScope {
                val coursesJob = if (orderedCourseNos.isNotEmpty()) {
                    launch {
                        // QueryCourse only indexes the latest term or two;
                        // lookupOrFallback drops to Moodle metadata for
                        // historical courses so they still render, with the
                        // name and credits but no schedule.
                        val courses = orderedCourseNos.map { courseNo ->
                            async {
                                courseService.lookupOrFallback(
                                    semester, courseNo, moodleByNo[courseNo],
                                )
                            }
                        }.awaitAll().filterNotNull()

                        if (courses.isNotEmpty()) {
                            val merged = ClassTableCourseMerge.mergeFetched(
                                fetched = courses,
                                cached = dataCache.loadCourses(semester),
                                names = courseCustomNames,
                                locale = currentCourseLocale,
                                deletedNos = dataCache.loadDeletedCourseNos(),
                            )
                            // Only apply if the user hasn't flipped to a
                            // different semester mid-flight.
                            if (_currentSemester.value == semester) {
                                _courses.value = merged
                                TigerDuckTheme.buildCourseColorMap(merged)
                            }
                            dataCache.saveCourses(merged, semester)
                            runCatching { pushApiClient.uploadCourses(merged, semester) }
                                .onFailure { Log.w("ClassTableVM", "uploadCourses failed (non-fatal)", it) }
                            widgetUpdater.requestUpdate()
                        }
                    }
                } else null

                // Assignments are always "upcoming from now", so they belong
                // to the active enrolment — fetch only when viewing the
                // current semester. Uses the Moodle enrolment list we already
                // fetched above.
                val assignmentsJob = if (isCurrentSemester) {
                    launch {
                        try {
                            val remoteAssignments = moodleService.fetchAssignments(
                                enrolledCourses = moodleAll,
                                rosterCourseNos = orderedCourseNos.toSet()
                            )
                            val merged = CourseRosterMerge.preserveConfirmedSubmissions(
                                remote = remoteAssignments,
                                previouslyCompleted =
                                    CourseRosterMerge.completedIds(_assignments.value),
                            )
                            _assignments.value = merged
                            dataCache.saveAssignments(merged)
                        } catch (e: Exception) {
                            Log.e("ClassTableVM", "Failed to fetch assignments", e)
                        }
                    }
                } else null

                coursesJob?.join()
                assignmentsJob?.join()
            }
            _syncCompleteEvent.tryEmit(Unit)
        } finally {
            _isLoading.value = false
        }
    }
}
