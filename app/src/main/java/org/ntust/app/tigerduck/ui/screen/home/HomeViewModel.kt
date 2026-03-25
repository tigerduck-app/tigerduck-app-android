package org.ntust.app.tigerduck.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.*
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val authService: AuthService,
    private val dataCache: DataCache
) : ViewModel() {

    private val _sections = MutableStateFlow(HomeSection.defaults())
    val sections: StateFlow<List<HomeSection>> = _sections

    private val _todayCourses = MutableStateFlow<List<Course>>(emptyList())
    val todayCourses: StateFlow<List<Course>> = _todayCourses

    private val _upcomingAssignments = MutableStateFlow<List<Assignment>>(emptyList())
    val upcomingAssignments: StateFlow<List<Assignment>> = _upcomingAssignments

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _selectedCourse = MutableStateFlow<Course?>(null)
    val selectedCourse: StateFlow<Course?> = _selectedCourse

    private var hasLoaded = false

    fun load() {
        if (hasLoaded) return
        hasLoaded = true

        // Load cached data immediately
        val cachedCourses = dataCache.loadCourses()
        val cachedAssignments = dataCache.loadAssignments()
        if (cachedCourses.isNotEmpty() || cachedAssignments.isNotEmpty()) {
            TigerDuckTheme.buildCourseColorMap(cachedCourses.map { it.courseNo })
            updateCoursesAndAssignments(cachedCourses, cachedAssignments)
        }

        viewModelScope.launch { fetchData() }
    }

    fun refresh() {
        viewModelScope.launch { fetchData() }
    }

    private suspend fun fetchData() {
        _isLoading.value = true
        try {
            // HomeViewModel only reads from cache — ClassTableViewModel handles the actual fetching
            val courses = dataCache.loadCourses()
            val assignments = dataCache.loadAssignments()
            TigerDuckTheme.buildCourseColorMap(courses.map { it.courseNo })
            updateCoursesAndAssignments(courses, assignments)
        } finally {
            _isLoading.value = false
        }
    }

    private fun updateCoursesAndAssignments(courses: List<Course>, assignments: List<Assignment>) {
        val todayIndex = Calendar.getInstance().get(Calendar.DAY_OF_WEEK).let {
            // Android: Sun=1, Mon=2..Sat=7. We need Mon=1..Sun=7
            when (it) {
                Calendar.MONDAY -> 1
                Calendar.TUESDAY -> 2
                Calendar.WEDNESDAY -> 3
                Calendar.THURSDAY -> 4
                Calendar.FRIDAY -> 5
                Calendar.SATURDAY -> 6
                Calendar.SUNDAY -> 7
                else -> 1
            }
        }
        _todayCourses.value = courses.filter { it.schedule.containsKey(todayIndex) }
        _upcomingAssignments.value = assignments
            .filter { !it.isCompleted }
            .sortedBy { it.dueDate }
    }

    fun hasUnfinishedAssignment(courseNo: String): Boolean =
        _upcomingAssignments.value.any { it.courseNo == courseNo && !it.isCompleted }

    fun assignmentsFor(courseNo: String): List<Assignment> =
        _upcomingAssignments.value.filter { it.courseNo == courseNo && !it.isCompleted }

    fun selectCourse(course: Course?) {
        _selectedCourse.value = course
    }

    fun removeSection(sectionId: String) {
        _sections.value = _sections.value.filter { it.id != sectionId }
            .mapIndexed { i, s -> s.copy(sortOrder = i) }
    }

    fun moveSections(from: Int, to: Int) {
        val list = _sections.value.toMutableList()
        val item = list.removeAt(from)
        list.add(to, item)
        _sections.value = list.mapIndexed { i, s -> s.copy(sortOrder = i) }
    }

    fun addSection(type: HomeSection.HomeSectionType, title: String) {
        val newSection = HomeSection(
            id = java.util.UUID.randomUUID().toString(),
            type = type,
            title = title,
            sortOrder = _sections.value.size,
            isVisible = true
        )
        _sections.value = _sections.value + newSection
    }
}
