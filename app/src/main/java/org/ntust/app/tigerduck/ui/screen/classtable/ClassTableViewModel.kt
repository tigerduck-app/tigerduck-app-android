package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.cache.DataCache
import android.util.Log
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.model.Course
import org.ntust.app.tigerduck.data.model.TimetablePeriod
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.network.MoodleService
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

@HiltViewModel
class ClassTableViewModel @Inject constructor(
    private val authService: AuthService,
    val courseService: CourseService,
    private val moodleService: MoodleService,
    private val dataCache: DataCache
) : ViewModel() {

    private val _courses = MutableStateFlow<List<Course>>(emptyList())
    val courses: StateFlow<List<Course>> = _courses

    private val _assignments = MutableStateFlow<List<Assignment>>(emptyList())
    val assignments: StateFlow<List<Assignment>> = _assignments

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _selectedCourse = MutableStateFlow<Course?>(null)
    val selectedCourse: StateFlow<Course?> = _selectedCourse

    private val _selectedWeekday = MutableStateFlow<Int?>(null)
    private val _selectedPeriodId = MutableStateFlow<String?>(null)

    private val _currentSemester = MutableStateFlow(courseService.currentSemesterCode())
    val currentSemester: StateFlow<String> = _currentSemester

    private var hasLoaded = false

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

    val totalCredits: Int get() = _courses.value.sumOf { it.credits }

    val todayCourses: List<Course>
        get() {
            val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK).let {
                when (it) {
                    Calendar.MONDAY -> 1; Calendar.TUESDAY -> 2; Calendar.WEDNESDAY -> 3
                    Calendar.THURSDAY -> 4; Calendar.FRIDAY -> 5; Calendar.SATURDAY -> 6
                    Calendar.SUNDAY -> 7; else -> 1
                }
            }
            return _courses.value.filter { it.schedule.containsKey(today) }
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
        dataCache.saveCourses(updated)
        TigerDuckTheme.buildCourseColorMap(updated.map { it.courseNo })
    }

    fun renameCourse(courseNo: String, newName: String) {
        val updated = _courses.value.map {
            if (it.courseNo == courseNo) it.copy(courseName = newName) else it
        }
        _courses.value = updated
        dataCache.saveCourses(updated)
    }

    fun deleteCourse(courseNo: String) {
        val updated = _courses.value.filter { it.courseNo != courseNo }
        _courses.value = updated
        dataCache.saveCourses(updated)
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
        val cached = dataCache.loadCourses()
        val cachedA = dataCache.loadAssignments()
        if (cached.isNotEmpty()) {
            _courses.value = cached
            _assignments.value = cachedA
            TigerDuckTheme.buildCourseColorMap(cached.map { it.courseNo })
        }
        viewModelScope.launch { fetchData() }
    }

    fun refresh() {
        viewModelScope.launch { fetchData() }
    }

    private suspend fun fetchData() {
        _isLoading.value = true
        try {
            val studentId = authService.storedStudentId ?: return
            val password = authService.storedPassword ?: return
            val semester = _currentSemester.value

            // Fetch enrolled course numbers, then look up details
            val courseNos = try {
                courseService.fetchEnrolledCourseNos(studentId, password)
            } catch (e: Exception) {
                Log.e("ClassTableVM", "Failed to fetch course list", e)
                null
            }

            if (courseNos != null && courseNos.isNotEmpty()) {
                val courses = courseNos.mapNotNull { courseNo ->
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
                if (courses.isNotEmpty()) {
                    _courses.value = courses
                    dataCache.saveCourses(courses)
                    TigerDuckTheme.buildCourseColorMap(courses.map { it.courseNo })
                }
            }

            // Fetch assignments from Moodle
            try {
                val assignments = moodleService.fetchAssignments(studentId, password)
                _assignments.value = assignments
                dataCache.saveAssignments(assignments)
            } catch (e: Exception) {
                Log.e("ClassTableVM", "Failed to fetch assignments", e)
            }
        } finally {
            _isLoading.value = false
        }
    }
}
