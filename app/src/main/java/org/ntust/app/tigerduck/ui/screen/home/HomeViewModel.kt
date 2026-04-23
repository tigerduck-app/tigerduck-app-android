package org.ntust.app.tigerduck.ui.screen.home

import android.util.Log
import androidx.lifecycle.ViewModel
import org.ntust.app.tigerduck.AppConstants
import androidx.lifecycle.viewModelScope
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.CourseColorStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.*
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.liveactivity.LiveActivityManager
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.network.MoodleService
import org.ntust.app.tigerduck.notification.AssignmentNotificationScheduler
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import org.ntust.app.tigerduck.network.NetworkChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val networkChecker: NetworkChecker,
    private val authService: AuthService,
    private val dataCache: DataCache,
    private val courseService: CourseService,
    private val moodleService: MoodleService,
    private val notificationScheduler: AssignmentNotificationScheduler,
    private val prefs: AppPreferences,
    private val courseColorStore: CourseColorStore,
    private val liveActivityManager: LiveActivityManager,
) : ViewModel() {

    private val _sections = MutableStateFlow(prefs.homeSections)
    val sections: StateFlow<List<HomeSection>> = _sections

    private val _allCourses = MutableStateFlow<List<Course>>(emptyList())
    val allCourses: StateFlow<List<Course>> = _allCourses

    private val _todayCourses = MutableStateFlow<List<Course>>(emptyList())
    val todayCourses: StateFlow<List<Course>> = _todayCourses

    // Full assignment list from the most recent load/fetch. The UI-visible
    // list is derived from this + the ignore set + the current tab filter.
    private val _allAssignments = MutableStateFlow<List<Assignment>>(emptyList())

    private val _ignoredAssignmentIds = MutableStateFlow<Set<String>>(emptySet())
    val ignoredAssignmentIds: StateFlow<Set<String>> = _ignoredAssignmentIds

    private val _assignmentFilter = MutableStateFlow(prefs.homeAssignmentFilter)
    val assignmentFilter: StateFlow<AssignmentFilter> = _assignmentFilter

    // "Sticky" visibility for the 已忽略 tab during a single Home visit. Once
    // the user interacts with the ignored flow we keep the tab on screen even
    // if the list empties out — so unignoring the last item or switching to
    // 未完成 doesn't yank the control mid-interaction. Cleared on Home pause.
    private val _ignoredTabPinned = MutableStateFlow(
        prefs.homeAssignmentFilter == AssignmentFilter.IGNORED
    )
    val ignoredTabPinned: StateFlow<Boolean> = _ignoredTabPinned

    val upcomingAssignments: StateFlow<List<Assignment>> = combine(
        _allAssignments,
        _ignoredAssignmentIds,
        _assignmentFilter,
    ) { all, ignored, filter ->
        when (filter) {
            AssignmentFilter.INCOMPLETE ->
                all.filter { !it.isCompleted && it.assignmentId !in ignored }
                    .sortedBy { it.dueDate }
            AssignmentFilter.ALL -> {
                val visible = all.filter { it.assignmentId !in ignored }
                val incomplete = visible.filter { !it.isCompleted }.sortedBy { it.dueDate }
                val completed = visible.filter { it.isCompleted }.sortedByDescending { it.dueDate }
                incomplete + completed
            }
            AssignmentFilter.IGNORED ->
                all.filter { it.assignmentId in ignored }.sortedBy { it.dueDate }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val saveIgnoredChannel = Channel<Set<String>>(Channel.CONFLATED)

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    // Flips true after the first cache read returns (even if the cache is
    // empty). UI keeps empty-state placeholders hidden until this is set so
    // the first frame never flashes "no data" before cached data appears.
    private val _initialLoadComplete = MutableStateFlow(false)
    val initialLoadComplete: StateFlow<Boolean> = _initialLoadComplete

    val isLoggedIn: StateFlow<Boolean> = authService.authState

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
        viewModelScope.launch {
            for (data in saveSkipChannel) {
                dataCache.saveSkippedDates(data)
            }
        }
        viewModelScope.launch {
            for (ids in saveIgnoredChannel) {
                dataCache.saveIgnoredAssignments(ids)
            }
        }
        viewModelScope.launch {
            // Pick up color changes triggered from Settings (e.g. "重設課表顏色").
            courseColorStore.changeEvent.collect {
                val fresh = dataCache.loadCourses()
                if (fresh.isNotEmpty()) {
                    TigerDuckTheme.buildCourseColorMap(fresh)
                    updateCoursesAndAssignments(fresh, _allAssignments.value)
                }
            }
        }
        viewModelScope.launch {
            // React to login/logout: clear immediately on sign-out, kick off a
            // fresh data fetch on sign-in so the UI never lingers on a prior
            // user's cached courses.
            authService.authState.collect { isAuthed ->
                if (!isAuthed) {
                    _allCourses.value = emptyList()
                    _todayCourses.value = emptyList()
                    _allAssignments.value = emptyList()
                    _skippedDates.value = emptyMap()
                    _ignoredAssignmentIds.value = emptySet()
                    hasLoaded = false
                    _initialLoadComplete.value = true
                } else {
                    fetchData(forceRemote = true)
                }
            }
        }
    }

    private var hasLoaded = false

    fun load() {
        if (hasLoaded) return
        hasLoaded = true

        viewModelScope.launch {
            _skippedDates.value = dataCache.loadSkippedDates()
            _ignoredAssignmentIds.value = dataCache.loadIgnoredAssignments()

            // Load cached data immediately
            val cachedCourses = dataCache.loadCourses()
            val cachedAssignments = dataCache.loadAssignments()
            if (cachedCourses.isNotEmpty() || cachedAssignments.isNotEmpty()) {
                TigerDuckTheme.buildCourseColorMap(cachedCourses)
                updateCoursesAndAssignments(cachedCourses, cachedAssignments)
            }
            _initialLoadComplete.value = true

            fetchData(forceRemote = true)
        }
    }

    fun refresh() {
        viewModelScope.launch {
            if (!networkChecker.isAvailable()) {
                _noNetworkEvent.tryEmit(Unit)
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
                    // ensureAuthenticated runs alongside the Moodle fetches now
                    // (see fetchCoursesAndAssignments). Moodle uses a long-lived
                    // wstoken so it doesn't need the NTUST SSO cookies the auth
                    // check is renewing.
                    val (remoteCourses, remoteAssignments) =
                        fetchCoursesAndAssignments(studentId, password)

                    if (!remoteCourses.isNullOrEmpty()) {
                        // Re-read cache so a concurrent color change isn't erased,
                        // and so manually-added courses survive the refresh.
                        val cached = dataCache.loadCourses()
                        val latestColors = cached.associate { it.courseNo to it.customColorHex }
                        val fetched = remoteCourses.map { c ->
                            c.copy(customColorHex = latestColors[c.courseNo])
                        }
                        val fetchedNos = fetched.map { it.courseNo }.toSet()
                        val manualLeftovers = cached.filter { it.isManual && it.courseNo !in fetchedNos }
                        courses = fetched + manualLeftovers
                        dataCache.saveCourses(courses)
                    }

                    if (remoteAssignments != null) {
                        assignments = remoteAssignments
                        dataCache.saveAssignments(remoteAssignments)
                    }
                }
            }

            TigerDuckTheme.buildCourseColorMap(courses)
            updateCoursesAndAssignments(courses, assignments)
            if (forceRemote && authService.isNtustAuthenticated) {
                _syncCompleteEvent.tryEmit(Unit)
            }
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * One network round of the two enrolment sources (NTUST course-selection
     * portal + Moodle enrolled-courses) run in parallel, then per-course
     * detail lookups run in parallel while assignments are fetched alongside.
     * Returns (null, null) for any piece that failed so the caller can fall
     * back to cached data cleanly.
     */
    private suspend fun fetchCoursesAndAssignments(
        studentId: String,
        password: String,
    ): Pair<List<Course>?, List<Assignment>?> = coroutineScope {
        val semester = courseService.currentSemesterCode()

        // Moodle webservice calls auth with a long-lived wstoken, so they
        // don't need the NTUST SSO cookies that ensureAuthenticated refreshes.
        // Let them run concurrently with the SSO + course-selection scrape.
        val moodleEnrolledDef = async {
            try {
                moodleService.fetchEnrolledCourses(studentId, password)
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Failed to fetch Moodle enrolled courses", e)
                null
            }
        }
        val courseNosDef = async {
            val authed = runCatching { authService.ensureAuthenticated() }.getOrDefault(false)
            if (!authed) return@async null
            try {
                courseService.fetchEnrolledCourseNos(studentId, password)
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Failed to fetch enrolled course numbers", e)
                null
            }
        }

        val courseNos = courseNosDef.await()
        val moodleEnrolled = moodleEnrolledDef.await()

        val coursesDef = async {
            when {
                courseNos == null -> null
                courseNos.isEmpty() -> emptyList()
                else -> courseNos.map { courseNo ->
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
                                    maxCount = r.maxEnrollment,
                                    schedule = schedule,
                                    moodleIdNumber = "${r.semester}${r.courseNo}"
                                )
                            }
                        } catch (e: Exception) {
                            Log.e("HomeViewModel", "Failed to lookup course $courseNo", e)
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        }

        val assignmentsDef = async {
            if (moodleEnrolled == null) return@async null
            try {
                val remote = moodleService.fetchAssignments(moodleEnrolled)
                // Safety net: if a transient submission-status call failed and
                // the remote reports isCompleted=false for something we
                // previously confirmed as submitted, don't regress the user's
                // view. Remote wins when it explicitly says isCompleted=true.
                val existingCompleted = dataCache.loadAssignments()
                    .filter { it.isCompleted }
                    .map { it.assignmentId }
                    .toSet()
                remote.map { assignment ->
                    if (!assignment.isCompleted && assignment.assignmentId in existingCompleted) {
                        assignment.copy(isCompleted = true)
                    } else assignment
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Failed to fetch assignments", e)
                null
            }
        }

        coursesDef.await() to assignmentsDef.await()
    }

    private fun updateCoursesAndAssignments(courses: List<Course>, assignments: List<Assignment>) {
        _allCourses.value = courses
        val todayIndex = Calendar.getInstance(AppConstants.TAIPEI_TZ).get(Calendar.DAY_OF_WEEK).let {
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
        _allAssignments.value = assignments.sortedBy { it.dueDate }

        // Schedule notifications for upcoming assignments. Skip anything the
        // user has ignored — notifying on a manually-dismissed task would be
        // the opposite of what the ignore gesture is for.
        if (prefs.notifyAssignments) {
            val ignored = _ignoredAssignmentIds.value
            notificationScheduler.scheduleAll(
                assignments.filter { !it.isCompleted && it.assignmentId !in ignored }
            )
        }

        // Refresh the Live Update (Android analogue of the iOS dynamic island)
        liveActivityManager.refresh()
    }

    fun cancelAllAssignmentNotifications() {
        notificationScheduler.cancelAllTracked()
    }

    fun hasUnfinishedAssignment(courseNo: String): Boolean {
        val ignored = _ignoredAssignmentIds.value
        return _allAssignments.value.any {
            it.courseNo == courseNo && !it.isCompleted && it.assignmentId !in ignored
        }
    }

    fun assignmentsFor(courseNo: String): List<Assignment> {
        val ignored = _ignoredAssignmentIds.value
        return _allAssignments.value.filter {
            it.courseNo == courseNo && !it.isCompleted && it.assignmentId !in ignored
        }
    }

    fun setAssignmentFilter(filter: AssignmentFilter) {
        _assignmentFilter.value = filter
        prefs.homeAssignmentFilter = filter
        if (filter == AssignmentFilter.IGNORED) _ignoredTabPinned.value = true
    }

    /**
     * Called when the Home screen goes out of foreground. Clears the sticky
     * 已忽略 tab pin so the next visit only shows the tab if there's
     * actually ignored data. Also snaps the filter back to INCOMPLETE when
     * the user left while stuck on an empty 已忽略, so returning to Home
     * doesn't land on an empty (and invisible) tab.
     */
    fun onHomePaused() {
        _ignoredTabPinned.value = false
        if (_assignmentFilter.value == AssignmentFilter.IGNORED &&
            _ignoredAssignmentIds.value.isEmpty()
        ) {
            _assignmentFilter.value = AssignmentFilter.INCOMPLETE
            prefs.homeAssignmentFilter = AssignmentFilter.INCOMPLETE
        }
    }

    fun toggleIgnore(assignment: Assignment) {
        _ignoredAssignmentIds.update { current ->
            if (assignment.assignmentId in current) current - assignment.assignmentId
            else current + assignment.assignmentId
        }
        saveIgnoredChannel.trySend(_ignoredAssignmentIds.value)
    }

    fun selectCourse(course: Course?) {
        _selectedCourse.value = course
    }

    // 翹課 feature disabled — replaced by the "已忽略" homework flow. Kept as
    // a no-op so existing call sites still compile; re-enable by uncommenting
    // the body below if the feature is ever reinstated.
    fun toggleSkip(course: Course, date: Date) {
        // val key = date.toInstant().atZone(AppConstants.TAIPEI_ZONE).toLocalDate().format(SKIP_DATE_FMT)
        // _skippedDates.update { current ->
        //     val map = current.toMutableMap()
        //     val dates = (map[course.courseNo] ?: emptyList()).toMutableList()
        //     if (key in dates) dates.remove(key) else dates.add(key)
        //     map[course.courseNo] = dates
        //     map
        // }
        // saveSkipChannel.trySend(_skippedDates.value)
    }

    fun removeSection(sectionId: String) {
        _sections.value = _sections.value.filter { it.id != sectionId }
            .mapIndexed { i, s -> s.copy(sortOrder = i) }
        prefs.homeSections = _sections.value
    }

    fun moveSections(from: Int, to: Int) {
        val list = _sections.value.toMutableList()
        val item = list.removeAt(from)
        list.add(to, item)
        _sections.value = list.mapIndexed { i, s -> s.copy(sortOrder = i) }
        prefs.homeSections = _sections.value
    }

    companion object {
        private val SKIP_DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE
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
        prefs.homeSections = _sections.value
    }
}
