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
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.network.MoodleService
import org.ntust.app.tigerduck.network.NetworkChecker
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
    private val courseColorStore: CourseColorStore
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

    private val _currentSemester = MutableStateFlow(courseService.currentSemesterCode())
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
            courseColorStore.changeEvent.collect {
                val fresh = dataCache.loadCourses()
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

    val availableSemesters: List<String>
        get() {
            val code = _currentSemester.value
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
        viewModelScope.launch { dataCache.saveCourses(updated) }
        TigerDuckTheme.buildCourseColorMap(updated)
    }

    fun renameCourse(courseNo: String, newName: String) {
        val updated = _courses.value.map {
            if (it.courseNo == courseNo) it.copy(courseName = newName) else it
        }
        _courses.value = updated
        viewModelScope.launch { dataCache.saveCourses(updated) }
    }

    fun deleteCourse(courseNo: String) {
        val updated = _courses.value.filter { it.courseNo != courseNo }
        _courses.value = updated
        viewModelScope.launch { dataCache.saveCourses(updated) }
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
        viewModelScope.launch { dataCache.saveCourses(updated) }
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
            val cached = dataCache.loadCourses()
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

            // Fetch enrolled course numbers, then look up details in parallel
            val courseNos = try {
                courseService.fetchEnrolledCourseNos(studentId, password)
            } catch (e: Exception) {
                Log.e("ClassTableVM", "Failed to fetch course list", e)
                null
            }

            if (courseNos != null && courseNos.isNotEmpty()) {
                val courses = coroutineScope {
                    courseNos.map { courseNo ->
                        async {
                            try {
                                val results = courseService.lookupCourse(semester, courseNo)
                                results.firstOrNull()?.let { r ->
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
                                        maxCount = r.restrict1?.toIntOrNull() ?: 0,
                                        schedule = schedule,
                                        moodleIdNumber = "${r.semester}${r.courseNo}"
                                    )
                                }
                            } catch (e: Exception) {
                                Log.e("ClassTableVM", "Failed to lookup course $courseNo", e)
                                null
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
                if (courses.isNotEmpty()) {
                    // Preserve user-picked tile colors across network refresh.
                    val existingColors = dataCache.loadCourses().associate { it.courseNo to it.customColorHex }
                    val merged = courses.map { c ->
                        c.copy(customColorHex = existingColors[c.courseNo])
                    }
                    _courses.value = merged
                    dataCache.saveCourses(merged)
                    TigerDuckTheme.buildCourseColorMap(merged)
                }
            }

            // Fetch assignments from Moodle, preserving isCompleted state
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
            _syncCompleteEvent.tryEmit(Unit)
        } finally {
            _isLoading.value = false
        }
    }
}
