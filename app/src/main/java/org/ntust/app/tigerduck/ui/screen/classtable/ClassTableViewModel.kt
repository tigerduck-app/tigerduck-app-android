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
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.BuildConfig
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
import org.ntust.app.tigerduck.network.decodeHtmlEntities
import org.ntust.app.tigerduck.network.model.MoodleEnrolledCourse
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import java.util.Calendar
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
    private var courseCustomNames: MutableMap<String, MutableMap<String, String>> = mutableMapOf()

    /** Current course-API locale ("zh" or "en"), derived from the user's app language. */
    private val currentCourseLocale: String
        get() = AppLanguageManager.resolvedCourseApiLanguage(appPreferences.appLanguage)

    /**
     * Stamp [Course.customCourseName] from [courseCustomNames] for the current
     * locale. Courses without a per-locale entry keep their existing override
     * (may be null).
     */
    private fun resolveCustomNames(courses: List<Course>): List<Course> {
        if (courseCustomNames.isEmpty()) return courses
        val locale = currentCourseLocale
        return courses.map { course ->
            val name = courseCustomNames[course.courseNo]?.get(locale)
            if (name != null) course.copy(customCourseName = name) else course
        }
    }

    private val _selectedWeekday = MutableStateFlow<Int?>(null)
    private val _selectedPeriodId = MutableStateFlow<String?>(null)

    private val _currentSemester = MutableStateFlow(
        appPreferences.classTableSelectedSemester ?: courseService.currentSemesterCode()
    )
    val currentSemester: StateFlow<String> = _currentSemester

    data class DayTime(val weekday: Int, val minuteOfDay: Int)

    private val _currentDayTime = MutableStateFlow(currentDayTime())
    val currentMinute: StateFlow<Int> = _currentDayTime
        .map { it.minuteOfDay }
        .stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            currentDayTime().minuteOfDay
        )

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
                    courseCustomNames.clear()
                    hasLoaded = false
                    TigerDuckTheme.buildCourseColorMap(emptyList())
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

    private fun currentDayTime(): DayTime {
        val c = AppClock.calendar()
        val wd = when (c.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 1; Calendar.TUESDAY -> 2; Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4; Calendar.FRIDAY -> 5; Calendar.SATURDAY -> 6
            else -> 7
        }
        return DayTime(wd, c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE))
    }

    /** The actual live semester code (not whatever the user picked). */
    val liveSemesterCode: String
        get() = courseService.currentSemesterCode()

    /**
     * The four most recent semesters, anchored on the *actual* current
     * semester — not whatever the user last switched to. Matches iOS so
     * the picker always offers the same range regardless of selection.
     */
    val availableSemesters: List<String>
        get() {
            val code = courseService.currentSemesterCode()
            val year = code.dropLast(1).toIntOrNull() ?: return listOf(code)
            val sem = code.last().digitToIntOrNull() ?: 1
            val result = mutableListOf<String>()
            var y = year
            var s = sem
            repeat(4) {
                result.add("$y$s")
                s--
                if (s < 1) {
                    s = 2; y--
                }
            }
            return result
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
            val today = _currentDayTime.value.weekday
            return _courses.value
                .filter { it.schedule.containsKey(today) }
                .sortedBy { course ->
                    val firstPeriod = course.schedule[today]
                        ?.minByOrNull { AppConstants.Periods.chronologicalOrder.indexOf(it) }
                    firstPeriod?.let { AppConstants.Periods.chronologicalOrder.indexOf(it) }
                        ?: Int.MAX_VALUE
                }
        }

    val activeWeekdays: List<Int>
        get() {
            val days = _courses.value.flatMap { it.schedule.keys }.toMutableSet()
            val result = (1..5).toMutableList()
            if (6 in days) result.add(6)
            if (7 in days) result.add(7)
            return result
        }

    val activePeriods: List<TimetablePeriod>
        get() {
            val periodIds = AppConstants.Periods.defaultVisible.toMutableSet()
            _courses.value.forEach { course ->
                course.schedule.values.forEach { periods -> periodIds.addAll(periods) }
            }
            val order = AppConstants.Periods.chronologicalOrder
            return order.filter { it in periodIds }.mapNotNull { TimetablePeriod.byId[it] }
        }

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
            val periods = course.schedule[weekday]?.sortedBy {
                AppConstants.Periods.chronologicalOrder.indexOf(it)
            } ?: return null
            if (periods.isEmpty()) return null
            val first = AppConstants.PeriodTimes.mapping[periods.first()] ?: return null
            val last = AppConstants.PeriodTimes.mapping[periods.last()] ?: return null
            return "${first.first} - ${last.second}"
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
            val weekday = _selectedWeekday.value ?: return Course.dedupRooms(course.classroom)
            val periodId = _selectedPeriodId.value ?: return course.classroom(weekday)
            return course.classroom(weekday, periodId)
        }

    fun isCourseFinishedToday(course: Course): Boolean {
        val dayTime = _currentDayTime.value
        val periods = course.schedule[dayTime.weekday]
            ?.sortedBy { AppConstants.Periods.chronologicalOrder.indexOf(it) }
        val lastPeriodId = periods?.lastOrNull() ?: return false
        val endTimeStr = AppConstants.PeriodTimes.mapping[lastPeriodId]?.second ?: return false
        val parts = endTimeStr.split(":")
        val endMinutes = (parts.getOrNull(0)?.toIntOrNull() ?: return false) * 60 +
                (parts.getOrNull(1)?.toIntOrNull() ?: return false)
        return dayTime.minuteOfDay > endMinutes
    }

    val ongoingCourses: List<OngoingCourseInfo>
        get() {
            val dayTime = _currentDayTime.value
            return computeOngoingCourses(_courses.value, dayTime.weekday, dayTime.minuteOfDay)
        }

    fun coursesAt(weekday: Int, period: String): List<Course> =
        _courses.value.filter { it.schedule[weekday]?.contains(period) == true }

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
                if (appPreferences.cloudSyncEnabled && !BuildConfig.FLAVOR.equals("fdroid", ignoreCase = true)) {
                    val moodleId = resolveMoodleNumericId(course)
                    if (moodleId != null) {
                        runCatching { pushApiClient.patchCourseOverride(moodleId, isHidden = false) }
                    }
                }
            }
            dataCache.saveCourses(updated, _currentSemester.value)
            if (appPreferences.cloudSyncEnabled && !BuildConfig.FLAVOR.equals("fdroid", ignoreCase = true)) {
                runCatching { pushApiClient.uploadCourses(updated, _currentSemester.value) }
            }
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
            // Update per-locale map
            if (override != null) {
                courseCustomNames.getOrPut(courseNo) { mutableMapOf() }[locale] = override
            } else {
                courseCustomNames[courseNo]?.remove(locale)
                if (courseCustomNames[courseNo].isNullOrEmpty()) courseCustomNames.remove(courseNo)
            }
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
        courseCustomNames[courseNo]?.remove(locale)
        if (courseCustomNames[courseNo].isNullOrEmpty()) courseCustomNames.remove(courseNo)
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
            if (appPreferences.cloudSyncEnabled && !BuildConfig.FLAVOR.equals("fdroid", ignoreCase = true)) {
                runCatching { pushApiClient.uploadCourses(updated, semester) }
                    .onFailure { e -> Log.w("ClassTable", "uploadCourses after delete failed", e) }
            }
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
        isHidden: Boolean? = null,
        colorHex: String? = null,
        customName: String? = null,
        locale: String? = null,
    ) {
        if (!appPreferences.cloudSyncEnabled || BuildConfig.FLAVOR.equals("fdroid", ignoreCase = true)) return
        val course = _courses.value.find { it.courseNo == courseNo } ?: return
        val moodleId = resolveMoodleNumericId(course) ?: return
        viewModelScope.launch {
            try {
                pushApiClient.patchCourseOverride(
                    moodleId,
                    isHidden = isHidden,
                    colorHex = colorHex,
                    customName = customName,
                    locale = locale,
                )
            } catch (e: Exception) {
                Log.w("ClassTableVM", "course override FAILED: $courseNo", e)
            }
        }
    }

    sealed class CellRole {
        object Empty : CellRole()
        data class SoloStart(val course: Course, val spanCount: Int) : CellRole()

        /**
         * Two overlapping courses occupying (possibly partially) this cluster.
         * [combinedSpan] is the total row count of the union. [offsetA]/[offsetB]
         * are 0-indexed row positions within the cluster where each course's
         * block begins. [spanA]/[spanB] are each course's own contiguous block
         * length. The L-split is drawn only on rows where both appear.
         */
        data class ConflictStart(
            val courseA: Course, val spanA: Int, val offsetA: Int,
            val courseB: Course, val spanB: Int, val offsetB: Int,
            val combinedSpan: Int
        ) : CellRole()

        /**
         * 3+ courses transitively connected by overlap — e.g. A on periods 6-7,
         * B on 6-8, C on 8-9: A and C don't share a period but B bridges them.
         * The L-split tile of [ConflictStart] only fits 2 courses, so callers
         * render this variant as vertical lanes (greedy interval-graph coloring
         * by [Member.lane] within [laneCount], same approach as the home
         * slider's 衝堂 stacking).
         */
        data class MultiConflictStart(
            val members: List<Member>,
            val combinedSpan: Int,
            val laneCount: Int,
        ) : CellRole() {
            data class Member(
                val course: Course,
                val span: Int,
                val offset: Int,
                val lane: Int,
                /** First period this course occupies — needed so the detail
                 *  popup resolves the correct per-(weekday, period) room. */
                val firstPeriodId: String,
            )
        }

        object Skip : CellRole()
    }

    /**
     * Contiguous block within [weekday] that contains [startIndex], for [course].
     * Returns (firstIndex, span). Adjacent periods in
     * [AppConstants.Periods.chronologicalOrder] count as contiguous.
     */
    private fun blockFor(weekday: Int, startIndex: Int, course: Course): Pair<Int, Int> {
        val periods = activePeriods
        val courseNo = course.courseNo
        // Walk backward to find the block start
        var first = startIndex
        while (first - 1 >= 0) {
            val prev = periods[first - 1]
            val prevPresent = _courses.value.any {
                it.courseNo == courseNo && it.schedule[weekday]?.contains(prev.id) == true
            }
            if (prevPresent) first-- else break
        }
        // Walk forward to find the block end
        var last = startIndex
        while (last + 1 < periods.size) {
            val next = periods[last + 1]
            val nextPresent = _courses.value.any {
                it.courseNo == courseNo && it.schedule[weekday]?.contains(next.id) == true
            }
            if (nextPresent) last++ else break
        }
        return first to (last - first + 1)
    }

    fun cellRole(weekday: Int, periodIndex: Int): CellRole {
        val periods = activePeriods
        if (periodIndex < 0 || periodIndex >= periods.size) return CellRole.Empty
        val period = periods[periodIndex]
        val coursesHere = coursesAt(weekday, period.id)
        if (coursesHere.isEmpty()) return CellRole.Empty

        // Build transitive closure of courses whose blocks overlap with any
        // course already in the cluster, rooted at the courses present in this
        // cell. This guarantees we emit a ConflictStart at the earliest row
        // of the union and Skip thereafter.
        val closure =
            LinkedHashMap<String, Triple<Course, Int, Int>>() // courseNo -> (course, firstIndex, span)

        fun addCourse(c: Course, seedIndex: Int) {
            if (closure.containsKey(c.courseNo)) return
            val (first, span) = blockFor(weekday, seedIndex, c)
            closure[c.courseNo] = Triple(c, first, span)
            // Expand: any other course touching any row in [first, first+span)
            for (i in first until first + span) {
                val pid = periods.getOrNull(i)?.id ?: continue
                for (other in coursesAt(weekday, pid)) {
                    if (!closure.containsKey(other.courseNo)) addCourse(other, i)
                }
            }
        }
        coursesHere.forEach { addCourse(it, periodIndex) }

        val clusterStart = closure.values.minOf { it.second }
        if (clusterStart < periodIndex) return CellRole.Skip

        if (closure.size == 1) {
            val (course, _, span) = closure.values.first()
            return CellRole.SoloStart(course, span)
        }

        val entries = closure.values.toList()
        val clusterEnd = entries.maxOf { it.second + it.third }
        val combined = clusterEnd - clusterStart

        if (entries.size == 2) {
            val (courseA, firstA, spanA) = entries[0]
            val (courseB, firstB, spanB) = entries[1]
            return CellRole.ConflictStart(
                courseA = courseA, spanA = spanA, offsetA = firstA - clusterStart,
                courseB = courseB, spanB = spanB, offsetB = firstB - clusterStart,
                combinedSpan = combined,
            )
        }

        // 3+ courses: lay out as vertical lanes via greedy interval-graph
        // coloring (each course takes the lowest-indexed lane whose previous
        // occupant has ended). Mirrors TimeSliderViewModel.computeSlotLayouts.
        val sortedByStart = entries.sortedBy { it.second }
        val laneEnds = mutableListOf<Int>()
        val laneAssignments = IntArray(sortedByStart.size)
        for ((i, e) in sortedByStart.withIndex()) {
            val (_, first, span) = e
            val end = first + span
            var lane = -1
            for (j in laneEnds.indices) {
                if (laneEnds[j] <= first) {
                    lane = j; break
                }
            }
            if (lane < 0) {
                laneEnds.add(end)
                lane = laneEnds.size - 1
            } else {
                laneEnds[lane] = end
            }
            laneAssignments[i] = lane
        }
        val members = sortedByStart.mapIndexed { i, e ->
            val (course, first, span) = e
            val firstPeriodId = periods.getOrNull(first)?.id ?: period.id
            CellRole.MultiConflictStart.Member(
                course = course,
                span = span,
                offset = first - clusterStart,
                lane = laneAssignments[i],
                firstPeriodId = firstPeriodId,
            )
        }
        return CellRole.MultiConflictStart(
            members = members,
            combinedSpan = combined,
            laneCount = laneEnds.size,
        )
    }

    data class TripleConflictError(
        val weekday: Int,
        val periodId: String,
        val newCourseName: String,
        val existingA: Course,
        val existingB: Course,
    )

    /**
     * Scans every (weekday, period) the candidate course would occupy and
     * returns the first slot that already has two courses — i.e. adding the
     * candidate would push that slot to three. Null if the add is safe.
     */
    fun wouldCauseTripleConflict(candidate: Course): TripleConflictError? {
        for ((weekday, periodIds) in candidate.schedule) {
            for (pid in periodIds) {
                val existing = coursesAt(weekday, pid)
                if (existing.size >= 2) {
                    return TripleConflictError(
                        weekday = weekday,
                        periodId = pid,
                        newCourseName = candidate.courseName,
                        existingA = existing[0],
                        existingB = existing[1],
                    )
                }
            }
        }
        return null
    }

    private val _tripleConflictEvent = MutableSharedFlow<TripleConflictError>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val tripleConflictEvent: SharedFlow<TripleConflictError> = _tripleConflictEvent.asSharedFlow()

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

    fun resetCourses() {
        viewModelScope.launch {
            val deletedNos = dataCache.loadDeletedCourseNos()
            if (deletedNos.isNotEmpty()) {
                if (appPreferences.cloudSyncEnabled && !BuildConfig.FLAVOR.equals("fdroid", ignoreCase = true)) {
                    val semester = courseService.currentSemesterCode()
                    val moodleIdMap = dataCache.loadMoodleCourseIds()
                    for (courseNo in deletedNos) {
                        val idnumber = "$semester$courseNo"
                        val numericId = moodleIdMap[idnumber] ?: continue
                        runCatching {
                            pushApiClient.patchCourseOverride(numericId, isHidden = false)
                        }
                    }
                }
            }
            dataCache.saveDeletedCourseNos(emptySet())
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
            val semester = _currentSemester.value
            val isCurrentSemester = semester == courseService.currentSemesterCode()
            Log.i(
                "ClassTableVM",
                "fetchData start: semester=$semester isCurrent=$isCurrentSemester"
            )

            // Kick off the two enrolment sources concurrently — they hit
            // different hosts (courseselection.ntust.edu.tw vs.
            // moodle2.ntust.edu.tw) and neither depends on the other.
            //   Source 1: course-selection portal, current term only.
            //   Source 2: Moodle enrolment list, all semesters (needed for
            //             historical terms and for fanning out assignments).
            val (selectionNos, moodleAll) = coroutineScope {
                val selectionDef = async {
                    if (isCurrentSemester) {
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

            val moodleForSem =
                moodleAll.filter { it.semesterCode == semester && it.courseNo.isNotEmpty() }
            val moodleByNo = moodleForSem.associateBy { it.courseNo }
            Log.i(
                "ClassTableVM",
                "moodleForSem[$semester]=${moodleForSem.size} -> ${moodleForSem.map { it.courseNo }}"
            )

            // Dedup while preserving order: selection first, then whatever
            // Moodle adds.
            val seen = LinkedHashSet<String>()
            selectionNos.forEach { seen.add(it) }
            moodleForSem.forEach { seen.add(it.courseNo) }
            val orderedCourseNos = seen.toList()
            Log.i("ClassTableVM", "orderedCourseNos=${orderedCourseNos.size} -> $orderedCourseNos")

            // Course detail lookups and assignment fetching are fully
            // independent — run them concurrently and apply each result as
            // soon as it lands.
            coroutineScope {
                val coursesJob = if (orderedCourseNos.isNotEmpty()) {
                    launch {
                        val courses = orderedCourseNos.map { courseNo ->
                            async {
                                try {
                                    val results = courseService.lookupCourse(semester, courseNo)
                                    if (results.isNotEmpty()) {
                                        val r = results.first()
                                        val schedule = courseService.mergeSchedules(
                                            *results.map { it.node }.toTypedArray()
                                        )
                                        val classroomMap = courseService.buildClassroomMap(results)
                                        val allRooms = LinkedHashSet<String>().apply {
                                            for (row in results) {
                                                Course.splitRooms(row.classRoomNo ?: "")
                                                    .forEach { add(it) }
                                            }
                                        }
                                        Course.fromSchedule(
                                            courseNo = r.courseNo,
                                            courseName = r.courseName,
                                            instructor = r.courseTeacher,
                                            credits = r.creditPoint.toIntOrNull() ?: 0,
                                            classroom = allRooms.joinToString(", "),
                                            enrolledCount = r.chooseStudent ?: 0,
                                            maxCount = r.maxEnrollment,
                                            schedule = schedule,
                                            classroomMap = classroomMap,
                                            moodleIdNumber = "${r.semester}${r.courseNo}"
                                        )
                                    } else {
                                        // QueryCourse only indexes the latest
                                        // term or two; fall back to Moodle
                                        // metadata so historical courses still
                                        // render (no schedule, but at least the
                                        // name and credits).
                                        moodleByNo[courseNo]?.let { m ->
                                            Course.fromSchedule(
                                                courseNo = courseNo,
                                                courseName = (m.fullname
                                                    ?: courseNo).decodeHtmlEntities(),
                                                moodleIdNumber = m.idnumber,
                                                moodleNumericCourseId = m.id,
                                            )
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e("ClassTableVM", "Failed to lookup course $courseNo", e)
                                    moodleByNo[courseNo]?.let { m ->
                                        Course.fromSchedule(
                                            courseNo = courseNo,
                                            courseName = (m.fullname
                                                ?: courseNo).decodeHtmlEntities(),
                                            moodleIdNumber = m.idnumber,
                                            moodleNumericCourseId = m.id,
                                        )
                                    }
                                }
                            }
                        }.awaitAll().filterNotNull()

                        if (courses.isNotEmpty()) {
                            val cached = dataCache.loadCourses(semester)
                            val deletedNos = dataCache.loadDeletedCourseNos()
                            val cachedByNo = cached.associateBy { it.courseNo }
                            val locale = currentCourseLocale
                            // Carry forward the user's color pick AND the
                            // `isManual` flag. Resolve customCourseName from
                            // the per-locale map so a language switch picks up
                            // the right override.
                            val fetched = courses.map { c ->
                                val prior = cachedByNo[c.courseNo]
                                c.copy(
                                    customColorHex = prior?.customColorHex,
                                    isManual = prior?.isManual == true,
                                    customCourseName = courseCustomNames[c.courseNo]?.get(locale),
                                )
                            }
                            val fetchedNos = fetched.map { it.courseNo }.toSet()
                            val manualLeftovers =
                                cached.filter { it.isManual && it.courseNo !in fetchedNos }
                            val merged = resolveCustomNames(fetched + manualLeftovers)
                                .filter { it.courseNo !in deletedNos }
                            // Only apply if the user hasn't flipped to a
                            // different semester mid-flight.
                            if (_currentSemester.value == semester) {
                                _courses.value = merged
                                TigerDuckTheme.buildCourseColorMap(merged)
                            }
                            dataCache.saveCourses(merged, semester)
                            if (appPreferences.cloudSyncEnabled && !BuildConfig.FLAVOR.equals("fdroid", ignoreCase = true)) {
                                runCatching { pushApiClient.uploadCourses(merged, semester) }
                                    .onFailure { Log.w("ClassTableVM", "uploadCourses failed (non-fatal)", it) }
                            }
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
                            val existingCompleted = _assignments.value
                                .filter { it.isCompleted }
                                .map { it.assignmentId }
                                .toSet()
                            val merged = remoteAssignments.map { assignment ->
                                if (assignment.assignmentId in existingCompleted) {
                                    assignment.copy(isCompleted = true)
                                } else assignment
                            }
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
