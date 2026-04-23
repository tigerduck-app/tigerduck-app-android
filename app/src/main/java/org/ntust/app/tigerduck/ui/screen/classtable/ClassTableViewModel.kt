package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.CourseColorStore
import org.ntust.app.tigerduck.data.cache.DataCache
import android.util.Log
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.model.Course
import org.ntust.app.tigerduck.data.model.TimetablePeriod
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.network.MoodleService
import org.ntust.app.tigerduck.network.NetworkChecker
import org.ntust.app.tigerduck.network.model.MoodleEnrolledCourse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    private val appPreferences: AppPreferences
) : ViewModel() {

    private val _courses = MutableStateFlow<List<Course>>(emptyList())
    val courses: StateFlow<List<Course>> = _courses

    private val _assignments = MutableStateFlow<List<Assignment>>(emptyList())
    val assignments: StateFlow<List<Assignment>> = _assignments

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    val isLoggedIn: StateFlow<Boolean> = authService.authState

    private val _selectedCourse = MutableStateFlow<Course?>(null)
    val selectedCourse: StateFlow<Course?> = _selectedCourse

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
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, currentDayTime().minuteOfDay)

    private var hasLoaded = false

    init {
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000)
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
                val fresh = dataCache.loadCourses(_currentSemester.value)
                if (fresh.isNotEmpty()) {
                    _courses.value = fresh
                    TigerDuckTheme.buildCourseColorMap(fresh)
                }
            }
        }
        viewModelScope.launch {
            // Clear on logout, refresh on login.
            authService.authState.collect { isAuthed ->
                if (!isAuthed) {
                    _courses.value = emptyList()
                    _assignments.value = emptyList()
                    _selectedCourse.value = null
                    hasLoaded = false
                    TigerDuckTheme.buildCourseColorMap(emptyList())
                } else {
                    fetchData()
                }
            }
        }
    }

    private fun currentDayTime(): DayTime {
        val c = Calendar.getInstance(org.ntust.app.tigerduck.AppConstants.TAIPEI_TZ)
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
                if (s < 1) { s = 2; y-- }
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
            val cached = dataCache.loadCourses(code)
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

    data class OngoingCourseInfo(
        val course: Course,
        val weekday: Int,
        val firstPeriodId: String,
        val startMinute: Int,
        val endMinute: Int
    )

    /**
     * The course whose contiguous block of periods contains the current
     * Taipei minute. Breaks between adjacent periods of the same course (e.g.
     * 3+4 or 6+7) count as "ongoing"; non-adjacent periods are treated as
     * separate blocks.
     */
    val ongoingCourse: OngoingCourseInfo?
        get() {
            val dayTime = _currentDayTime.value
            val now = dayTime.minuteOfDay
            val order = AppConstants.Periods.chronologicalOrder
            for (course in _courses.value) {
                val periods = course.schedule[dayTime.weekday]
                    ?.sortedBy { order.indexOf(it) } ?: continue
                if (periods.isEmpty()) continue
                var blockStart = 0
                while (blockStart < periods.size) {
                    var blockEnd = blockStart
                    while (blockEnd + 1 < periods.size &&
                        order.indexOf(periods[blockEnd + 1]) == order.indexOf(periods[blockEnd]) + 1
                    ) blockEnd++
                    val firstId = periods[blockStart]
                    val lastId = periods[blockEnd]
                    val startStr = AppConstants.PeriodTimes.mapping[firstId]?.first
                    val endStr = AppConstants.PeriodTimes.mapping[lastId]?.second
                    val startMin = startStr?.let(::parseHm)
                    val endMin = endStr?.let(::parseHm)
                    if (startMin != null && endMin != null && now in startMin..endMin) {
                        return OngoingCourseInfo(
                            course = course,
                            weekday = dayTime.weekday,
                            firstPeriodId = firstId,
                            startMinute = startMin,
                            endMinute = endMin
                        )
                    }
                    blockStart = blockEnd + 1
                }
            }
            return null
        }

    private fun parseHm(hhmm: String): Int? {
        val parts = hhmm.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val m = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return h * 60 + m
    }

    fun courseAt(weekday: Int, period: String): Course? =
        _courses.value.firstOrNull { it.schedule[weekday]?.contains(period) == true }

    fun hasAssignment(courseNo: String): Boolean =
        _assignments.value.any { it.courseNo == courseNo && !it.isCompleted }

    fun assignmentsFor(courseNo: String): List<Assignment> =
        _assignments.value.filter { it.courseNo == courseNo && !it.isCompleted }

    fun selectCourse(course: Course, weekday: Int, periodId: String) {
        _selectedWeekday.value = weekday
        _selectedPeriodId.value = periodId
        _selectedCourse.value = course
    }

    fun clearSelection() {
        _selectedCourse.value = null
    }

    val existingCourseNos: Set<String>
        get() = _courses.value.map { it.courseNo }.toSet()

    fun addCourse(course: Course) {
        val updated = _courses.value + course
        _courses.value = updated
        viewModelScope.launch { dataCache.saveCourses(updated, _currentSemester.value) }
        TigerDuckTheme.buildCourseColorMap(updated)
    }

    fun renameCourse(courseNo: String, newName: String) {
        val updated = _courses.value.map {
            if (it.courseNo == courseNo) it.copy(courseName = newName) else it
        }
        _courses.value = updated
        viewModelScope.launch { dataCache.saveCourses(updated, _currentSemester.value) }
    }

    fun deleteCourse(courseNo: String) {
        val updated = _courses.value.filter { it.courseNo != courseNo }
        _courses.value = updated
        viewModelScope.launch { dataCache.saveCourses(updated, _currentSemester.value) }
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
        viewModelScope.launch { dataCache.saveCourses(updated, _currentSemester.value) }
    }

    sealed class CellRole {
        object Empty : CellRole()
        data class BlockStart(val course: Course, val spanCount: Int) : CellRole()
        object BlockContinuation : CellRole()
    }

    fun cellRole(weekday: Int, periodIndex: Int): CellRole {
        val periods = activePeriods
        if (periodIndex < 0 || periodIndex >= periods.size) return CellRole.Empty
        val period = periods[periodIndex]
        val course = courseAt(weekday, period.id) ?: return CellRole.Empty

        if (periodIndex > 0) {
            val prev = periods[periodIndex - 1]
            val prevCourse = courseAt(weekday, prev.id)
            if (prevCourse?.courseNo == course.courseNo) return CellRole.BlockContinuation
        }

        var span = 1
        var next = periodIndex + 1
        while (next < periods.size) {
            val nextCourse = courseAt(weekday, periods[next].id)
            if (nextCourse?.courseNo == course.courseNo) { span++; next++ } else break
        }
        return CellRole.BlockStart(course, span)
    }

    fun load() {
        if (hasLoaded) return
        hasLoaded = true
        viewModelScope.launch {
            val cached = dataCache.loadCourses(_currentSemester.value)
            val cachedA = dataCache.loadAssignments()
            if (cached.isNotEmpty()) {
                _courses.value = cached
                _assignments.value = cachedA
                TigerDuckTheme.buildCourseColorMap(cached)
            }
            fetchData()
        }
    }

    private val _noNetworkEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val noNetworkEvent: SharedFlow<Unit> = _noNetworkEvent.asSharedFlow()

    private val _syncCompleteEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val syncCompleteEvent: SharedFlow<Unit> = _syncCompleteEvent.asSharedFlow()

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
        if (!networkChecker.isAvailable()) { _isLoading.value = false; return }
        _isLoading.value = true
        try {
            val semester = _currentSemester.value
            val isCurrentSemester = semester == courseService.currentSemesterCode()
            Log.i("ClassTableVM", "fetchData start: semester=$semester isCurrent=$isCurrentSemester")

            // Source 1: course-selection enrolment list. Only current-term
            // enrolments are exposed by that portal, so skip it for past
            // semesters and lean on Moodle's historical record instead.
            val selectionNos: List<String> = if (isCurrentSemester) {
                try {
                    courseService.fetchEnrolledCourseNos(studentId, password)
                } catch (e: Exception) {
                    Log.e("ClassTableVM", "Failed to fetch course list", e)
                    emptyList()
                }
            } else emptyList()
            Log.i("ClassTableVM", "selectionNos=${selectionNos.size} -> $selectionNos")

            // Source 2: Moodle enrolled courses (all semesters). Works for
            // past terms too because Moodle keeps every course the user has
            // ever been enrolled in.
            val moodleAll: List<MoodleEnrolledCourse> = try {
                moodleService.fetchEnrolledCourses(studentId, password)
            } catch (e: Exception) {
                Log.e("ClassTableVM", "Failed to fetch Moodle enrolled courses", e)
                emptyList()
            }
            Log.i(
                "ClassTableVM",
                "moodleAll=${moodleAll.size} sampleIdnums=${moodleAll.take(5).map { it.idnumber }} semesters=${moodleAll.map { it.semesterCode }.distinct()}"
            )
            val moodleForSem = moodleAll.filter { it.semesterCode == semester && it.courseNo.isNotEmpty() }
            val moodleByNo = moodleForSem.associateBy { it.courseNo }
            Log.i("ClassTableVM", "moodleForSem[$semester]=${moodleForSem.size} -> ${moodleForSem.map { it.courseNo }}")

            // Dedup while preserving order: selection first, then whatever
            // Moodle adds.
            val seen = LinkedHashSet<String>()
            selectionNos.forEach { seen.add(it) }
            moodleForSem.forEach { seen.add(it.courseNo) }
            val orderedCourseNos = seen.toList()
            Log.i("ClassTableVM", "orderedCourseNos=${orderedCourseNos.size} -> $orderedCourseNos")

            if (orderedCourseNos.isNotEmpty()) {
                val courses = coroutineScope {
                    orderedCourseNos.map { courseNo ->
                        async {
                            try {
                                val results = courseService.lookupCourse(semester, courseNo)
                                if (results.isNotEmpty()) {
                                    val r = results.first()
                                    val schedule = courseService.mergeSchedules(
                                        *results.map { it.node }.toTypedArray()
                                    )
                                    Course.fromSchedule(
                                        courseNo = r.courseNo,
                                        courseName = r.courseName,
                                        instructor = r.courseTeacher,
                                        credits = r.creditPoint.toIntOrNull() ?: 0,
                                        classroom = r.classRoomNo ?: "",
                                        enrolledCount = r.chooseStudent ?: 0,
                                        maxCount = r.maxEnrollment,
                                        schedule = schedule,
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
                                            courseName = m.fullname ?: courseNo,
                                            moodleIdNumber = m.idnumber
                                        )
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("ClassTableVM", "Failed to lookup course $courseNo", e)
                                moodleByNo[courseNo]?.let { m ->
                                    Course.fromSchedule(
                                        courseNo = courseNo,
                                        courseName = m.fullname ?: courseNo,
                                        moodleIdNumber = m.idnumber
                                    )
                                }
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
                if (courses.isNotEmpty()) {
                    // Preserve user-picked tile colors across network refresh.
                    val existingColors = dataCache.loadCourses(semester)
                        .associate { it.courseNo to it.customColorHex }
                    val merged = courses.map { c ->
                        c.copy(customColorHex = existingColors[c.courseNo])
                    }
                    // Only apply if the user hasn't flipped to a different
                    // semester mid-flight.
                    if (_currentSemester.value == semester) {
                        _courses.value = merged
                        TigerDuckTheme.buildCourseColorMap(merged)
                    }
                    dataCache.saveCourses(merged, semester)
                }
            }

            // Assignments are always "upcoming from now", so they belong to
            // the active enrolment — fetch only when viewing the current
            // semester.
            if (isCurrentSemester) {
                try {
                    val remoteAssignments = moodleService.fetchAssignments(studentId, password)
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
            _syncCompleteEvent.tryEmit(Unit)
        } finally {
            _isLoading.value = false
        }
    }
}
