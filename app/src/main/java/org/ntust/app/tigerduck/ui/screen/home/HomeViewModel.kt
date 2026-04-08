package org.ntust.app.tigerduck.ui.screen.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.*
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.network.MoodleService
import org.ntust.app.tigerduck.notification.AssignmentNotificationScheduler
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import org.ntust.app.tigerduck.network.NetworkChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val networkChecker: NetworkChecker,
    private val authService: AuthService,
    private val dataCache: DataCache,
    private val courseService: CourseService,
    private val moodleService: MoodleService,
    private val notificationScheduler: AssignmentNotificationScheduler,
    private val prefs: AppPreferences
) : ViewModel() {

    private val _sections = MutableStateFlow(HomeSection.defaults())
    val sections: StateFlow<List<HomeSection>> = _sections

    private val _allCourses = MutableStateFlow<List<Course>>(emptyList())
    val allCourses: StateFlow<List<Course>> = _allCourses

    private val _todayCourses = MutableStateFlow<List<Course>>(emptyList())
    val todayCourses: StateFlow<List<Course>> = _todayCourses

    private val _upcomingAssignments = MutableStateFlow<List<Assignment>>(emptyList())
    val upcomingAssignments: StateFlow<List<Assignment>> = _upcomingAssignments

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _noNetworkEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    val noNetworkEvent: SharedFlow<Unit> = _noNetworkEvent.asSharedFlow()

    private val _selectedCourse = MutableStateFlow<Course?>(null)
    val selectedCourse: StateFlow<Course?> = _selectedCourse

    private val _syncCompleteEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    val syncCompleteEvent: SharedFlow<Unit> = _syncCompleteEvent.asSharedFlow()

    private val _skippedDates = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val skippedDates: StateFlow<Map<String, List<String>>> = _skippedDates

    private val saveSkipChannel = Channel<Map<String, List<String>>>(Channel.CONFLATED)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            for (data in saveSkipChannel) {
                dataCache.saveSkippedDates(data)
            }
        }
    }

    private var hasLoaded = false

    fun load() {
        if (hasLoaded) return
        hasLoaded = true

        viewModelScope.launch {
            _skippedDates.value = dataCache.loadSkippedDates()

            // Load cached data immediately
            val cachedCourses = dataCache.loadCourses()
            val cachedAssignments = dataCache.loadAssignments()
            if (cachedCourses.isNotEmpty() || cachedAssignments.isNotEmpty()) {
                TigerDuckTheme.buildCourseColorMap(cachedCourses.map { it.courseNo })
                updateCoursesAndAssignments(cachedCourses, cachedAssignments)
            }

            fetchData(forceRemote = true)
        }
    }

    fun refresh() {
        viewModelScope.launch {
            if (!networkChecker.isAvailable()) {
                _noNetworkEvent.tryEmit(Unit)
                kotlinx.coroutines.yield()
                return@launch
            }
            fetchData(forceRemote = true)
        }
    }

    private suspend fun fetchData(forceRemote: Boolean) {
        _isLoading.value = true
        try {
            var courses = dataCache.loadCourses()
            var assignments = dataCache.loadAssignments()

            if (forceRemote) {
                val studentId = authService.storedStudentId
                val password = authService.storedPassword
                if (!studentId.isNullOrBlank() && !password.isNullOrBlank()) {
                    val authenticated = runCatching { authService.ensureAuthenticated() }.getOrDefault(false)
                    if (authenticated) {
                        val remoteCourses = fetchCourses(studentId, password)
                        if (!remoteCourses.isNullOrEmpty()) {
                            courses = remoteCourses
                            dataCache.saveCourses(remoteCourses)
                        }

                        val remoteAssignments = fetchAssignments(studentId, password)
                        if (remoteAssignments != null) {
                            assignments = remoteAssignments
                            dataCache.saveAssignments(remoteAssignments)
                        }
                    }
                }
            }

            TigerDuckTheme.buildCourseColorMap(courses.map { it.courseNo })
            updateCoursesAndAssignments(courses, assignments)
            if (forceRemote && authService.isNtustAuthenticated) {
                _syncCompleteEvent.tryEmit(Unit)
            }
        } finally {
            _isLoading.value = false
        }
    }

    private suspend fun fetchCourses(studentId: String, password: String): List<Course>? {
        val semester = courseService.currentSemesterCode()
        val courseNos = try {
            courseService.fetchEnrolledCourseNos(studentId, password)
        } catch (e: Exception) {
            Log.e("HomeViewModel", "Failed to fetch enrolled course numbers", e)
            return null
        }

        if (courseNos.isEmpty()) return emptyList()

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
                Log.e("HomeViewModel", "Failed to lookup course $courseNo", e)
                null
            }
        }

        return courses
    }

    private suspend fun fetchAssignments(studentId: String, password: String): List<Assignment>? {
        return try {
            val remote = moodleService.fetchAssignments(studentId, password)
            val existingCompleted = _upcomingAssignments.value
                .filter { it.isCompleted }
                .map { it.assignmentId }
                .toSet()
            remote.map { assignment ->
                if (assignment.assignmentId in existingCompleted) {
                    assignment.copy(isCompleted = true)
                } else assignment
            }
        } catch (e: Exception) {
            Log.e("HomeViewModel", "Failed to fetch assignments", e)
            null
        }
    }

    private fun updateCoursesAndAssignments(courses: List<Course>, assignments: List<Assignment>) {
        _allCourses.value = courses
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

        // Schedule notifications for upcoming assignments
        if (prefs.notifyAssignments) {
            notificationScheduler.scheduleAll(assignments.filter { !it.isCompleted })
        }
    }

    fun cancelAllAssignmentNotifications() {
        notificationScheduler.cancelAllTracked()
    }

    fun hasUnfinishedAssignment(courseNo: String): Boolean =
        _upcomingAssignments.value.any { it.courseNo == courseNo && !it.isCompleted }

    fun assignmentsFor(courseNo: String): List<Assignment> =
        _upcomingAssignments.value.filter { it.courseNo == courseNo && !it.isCompleted }

    fun selectCourse(course: Course?) {
        _selectedCourse.value = course
    }

    fun toggleSkip(course: Course, date: Date) {
        val key = skipDateFmt.format(date)
        _skippedDates.update { current ->
            val map = current.toMutableMap()
            val dates = (map[course.courseNo] ?: emptyList()).toMutableList()
            if (key in dates) dates.remove(key) else dates.add(key)
            map[course.courseNo] = dates
            map
        }
        saveSkipChannel.trySend(_skippedDates.value)
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

    companion object {
        private val skipDateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
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
