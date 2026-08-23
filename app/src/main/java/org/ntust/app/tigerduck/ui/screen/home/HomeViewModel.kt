package org.ntust.app.tigerduck.ui.screen.home

import org.ntust.app.tigerduck.AppConstants
import android.util.Log
import org.ntust.app.tigerduck.data.CourseRosterMerge
import org.ntust.app.tigerduck.BuildConfig
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.ui.component.ServerFailureSimulator
import org.ntust.app.tigerduck.ui.component.ServerKind
import org.ntust.app.tigerduck.ui.component.ServerStatus
import org.ntust.app.tigerduck.ui.component.ServerStatusTracker
import org.ntust.app.tigerduck.notification.SyncSource
import org.ntust.app.tigerduck.push.SyncApiClient
import org.ntust.app.tigerduck.data.CourseColorStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.model.AssignmentFilter
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.data.model.HomeSection
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.liveactivity.LiveActivityManager
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.network.MoodleService
import org.ntust.app.tigerduck.network.NetworkChecker
import org.ntust.app.tigerduck.network.SemesterCatalog
import org.ntust.app.tigerduck.notification.AssignmentNotificationScheduler
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
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
    private val semesterCatalog: SemesterCatalog,
    private val liveActivityManager: LiveActivityManager,
    private val widgetUpdater: org.ntust.app.tigerduck.widget.WidgetUpdater,
    private val pushApiClient: PushApiClient,
    private val syncApiClient: SyncApiClient,
    private val authTokenManager: AuthTokenManager,
    private val backendSync: HomeBackendSync,
) : ViewModel() {

    val isSyncLocalOnly = prefs.lastSyncSource
        .map { prefs.cloudSyncEnabled && it == SyncSource.LOCAL }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _sections = MutableStateFlow(prefs.homeSections)
    val sections: StateFlow<List<HomeSection>> = _sections

    private val _allCourses = MutableStateFlow<List<Course>>(emptyList())
    val allCourses: StateFlow<List<Course>> = _allCourses

    private val _todayCourses = MutableStateFlow<List<Course>>(emptyList())
    val todayCourses: StateFlow<List<Course>> = _todayCourses

    // Full assignment list from the most recent load/fetch. The UI-visible
    // list is derived from this + the ignore set + the current tab filter.
    private val _allAssignments = MutableStateFlow<List<Assignment>>(emptyList())

    private val pendingOverrides = mutableSetOf<String>()

    private val _ignoredAssignmentIds = MutableStateFlow<Set<String>>(emptySet())
    val ignoredAssignmentIds: StateFlow<Set<String>> = _ignoredAssignmentIds

    // Manually flagged "done" via the right-swipe gesture. Distinct from the
    // Moodle `isCompleted` field so the UI can render a "標示為完成" badge
    // instead of "已繳交"/"已遲交", and so the flag survives even if Moodle
    // never records a submission for it.
    private val _markedCompletedIds = MutableStateFlow<Set<String>>(emptySet())
    val markedCompletedIds: StateFlow<Set<String>> = _markedCompletedIds

    // Everything the backend sync touches, bundled so HomeBackendSync can
    // read and write it directly. Declared here rather than at the top of the
    // class because Kotlin runs property initializers in declaration order —
    // built any earlier, it would capture nulls for the flows above.
    //
    // Handed over on every call instead of snapshotting values, so a sync in
    // flight sees pendingOverrides as the toggle handlers leave it rather
    // than as it was when the network call started.
    private val syncState = HomeSyncState(
        ignoredAssignmentIds = _ignoredAssignmentIds,
        markedCompletedIds = _markedCompletedIds,
        allAssignments = _allAssignments,
        allCourses = _allCourses,
        conflicts = MutableStateFlow(emptyList()),
        pendingOverrides = pendingOverrides,
    )

    val syncConflicts: StateFlow<List<AssignmentSyncConflict>> = syncState.conflicts

    /**
     * Answer the sync-conflict dialog.
     *
     * The dialog is dismissed synchronously — clearing [syncConflicts] here
     * rather than inside the coroutine — so it cannot be answered twice while
     * the resolution's network calls are still going out. The captured
     * result is what the user was actually shown.
     */
    fun resolveSyncConflicts(keepLocal: Boolean) {
        val result = syncState.pendingResult ?: return
        val conflicts = syncState.conflicts.value.toList()
        syncState.pendingResult = null
        syncState.conflicts.value = emptyList()
        viewModelScope.launch {
            backendSync.applyResolution(syncState, result, conflicts, keepLocal)
        }
    }

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

    // Re-emits whenever AppClock.setOverride is called AND once per minute as
    // wall-clock time advances, so the ALL-tab partitioning below
    // (overdue-pinned-first vs. future vs. past) re-runs both when the debug
    // clock toggles and as items naturally cross their dueDate. Without the
    // periodic pulse, an assignment that came due while the app stayed open
    // would never move into the overdue bucket.
    private val appClockChanges: Flow<Long> = merge(
        callbackFlow {
            val listener: (Long) -> Unit = { trySend(it) }
            AppClock.addOverrideListener(listener)
            trySend(AppClock.version())
            awaitClose { AppClock.removeOverrideListener(listener) }
        },
        kotlinx.coroutines.flow.flow {
            while (true) {
                kotlinx.coroutines.delay(60_000)
                emit(AppClock.nowMillis())
            }
        },
    )

    val upcomingAssignments: StateFlow<List<Assignment>> = combine(
        _allAssignments,
        _ignoredAssignmentIds,
        _markedCompletedIds,
        _assignmentFilter,
        appClockChanges,
    ) { all, ignored, marked, filter, _ ->
        HomeAssignmentFilters.visible(all, ignored, marked, filter, Date(AppClock.nowMillis()))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val saveIgnoredChannel = Channel<Set<String>>(Channel.CONFLATED)
    private val saveMarkedCompletedChannel = Channel<Set<String>>(Channel.CONFLATED)

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    // Flips true after the first cache read returns (even if the cache is
    // empty). UI keeps empty-state placeholders hidden until this is set so
    // the first frame never flashes "no data" before cached data appears.
    private val _initialLoadComplete = MutableStateFlow(false)
    val initialLoadComplete: StateFlow<Boolean> = _initialLoadComplete

    val isLoggedIn: StateFlow<Boolean> = authService.authState

    private val _noNetworkEvent = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val noNetworkEvent: SharedFlow<Unit> = _noNetworkEvent.asSharedFlow()

    private val _selectedCourse = MutableStateFlow<SelectedCourseInfo?>(null)
    val selectedCourse: StateFlow<SelectedCourseInfo?> = _selectedCourse

    private val _syncCompleteEvent = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val syncCompleteEvent: SharedFlow<Unit> = _syncCompleteEvent.asSharedFlow()

    // 翹課 — parked, not abandoned. Scheduled to land after the add-friend
    // feature, so the whole ViewModel side stays commented out rather than
    // sitting here as live code nothing reaches. 374122a9 is the last commit
    // where it was wired up end to end.
    //
    // The read side is parked too, as of f2c075ff: LiveActivityManager,
    // BootReceiver and DebugClockController each pass emptyMap() with the
    // DataCache read commented out beside it, so nothing an older build wrote
    // suppresses anything today. That is deliberate — a live read with a dead
    // toggle would make classes vanish with no way to get them back, because
    // the left-swipe that undoes a skip is commented out as well.
    //
    // Keep DataCache.saveSkippedDates / loadSkippedDates anyway. The file is
    // not deleted, so marks made before v2.0.0 come back the day the feature
    // is switched on. Turn the UI on first, then the readers — not the other
    // way round.
    //
    // private val _skippedDates = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    // val skippedDates: StateFlow<Map<String, List<String>>> = _skippedDates
    //
    // private val saveSkipChannel = Channel<Map<String, List<String>>>(Channel.CONFLATED)

    init {
        // viewModelScope.launch {
        //     for (data in saveSkipChannel) {
        //         dataCache.saveSkippedDates(data)
        //     }
        // }
        viewModelScope.launch {
            for (ids in saveIgnoredChannel) {
                dataCache.saveIgnoredAssignments(ids)
            }
        }
        viewModelScope.launch {
            for (ids in saveMarkedCompletedChannel) {
                dataCache.saveMarkedCompletedAssignments(ids)
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
                    // _skippedDates.value = emptyMap()
                    _ignoredAssignmentIds.value = emptySet()
                    _markedCompletedIds.value = emptySet()
                    hasLoaded = false
                    _initialLoadComplete.value = true
                } else {
                    fetchData(forceRemote = true)
                }
            }
        }
        viewModelScope.launch {
            // Language change → re-fetch so today's courses and assignment
            // names render in the new locale.
            prefs.appLanguageChanged.collect {
                courseService.clearInMemoryLookupCache()
                if (authService.authState.value) refresh()
            }
        }
        viewModelScope.launch {
            dataCache.backgroundSyncVersion.drop(1).collect {
                val courses = dataCache.loadCourses()
                val assignments = dataCache.loadAssignments()
                _ignoredAssignmentIds.value = dataCache.loadIgnoredAssignments()
                _markedCompletedIds.value = dataCache.loadMarkedCompletedAssignments()
                TigerDuckTheme.buildCourseColorMap(courses)
                updateCoursesAndAssignments(courses, assignments)
            }
        }
        viewModelScope.launch {
            // Abbreviation toggles + Mandarin classroom display picker — pure
            // display transforms. Re-derive names from the lookup cache so the
            // time-slider card and today list update without a network call.
            merge(
                prefs.useEnglishCourseAbbreviationChanged,
                prefs.useEnglishClassroomAbbreviationChanged,
                prefs.classroomMandarinDisplayChanged,
            ).collect {
                val semester = courseService.currentSemesterCode()
                val relabeled = courseService.relabelCoursesForCurrentAbbrSetting(
                    semester, _allCourses.value
                )
                if (relabeled != _allCourses.value) {
                    updateCoursesAndAssignments(relabeled, _allAssignments.value)
                    dataCache.saveCourses(relabeled)
                }
            }
        }
    }

    private suspend fun migrateColorHashIfNeeded() {
        if (prefs.colorHashV2Migrated) return
        if (!prefs.cloudSyncEnabled) {
            prefs.colorHashV2Migrated = true
            return
        }
        val courses = dataCache.loadCourses()
        if (courses.isNotEmpty()) {
            val cleared = courses.map { it.copy(customColorHex = null) }
            dataCache.saveCourses(cleared)
        }
        prefs.colorHashV2Migrated = true
    }

    private var hasLoaded = false

    fun load() {
        if (hasLoaded) return
        hasLoaded = true

        viewModelScope.launch {
            migrateColorHashIfNeeded()
            // _skippedDates.value = dataCache.loadSkippedDates()
            _ignoredAssignmentIds.value = dataCache.loadIgnoredAssignments()
            _markedCompletedIds.value = dataCache.loadMarkedCompletedAssignments()

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

    private var lastForegroundSyncMs = 0L

    fun syncOnForeground() {
        val now = System.currentTimeMillis()
        if (now - lastForegroundSyncMs < 30_000) return
        lastForegroundSyncMs = now
        viewModelScope.launch {
            if (!networkChecker.isAvailable()) return@launch
            runCatching {
                syncAndRepublish()
            }
        }
    }

    // --- Foreground revision polling ---

    private var revisionPollingJob: Job? = null

    fun startRevisionPolling() {
        if (revisionPollingJob?.isActive == true) {
            Log.d("RevisionPoll", "[poll] startRevisionPolling skipped — job already active")
            return
        }
        Log.d("RevisionPoll", "[poll] startRevisionPolling — launching 10s loop")
        revisionPollingJob = viewModelScope.launch {
            while (true) {
                delay(10_000)
                if (!prefs.cloudSyncEnabled || BuildConfig.FLAVOR.equals("fdroid", ignoreCase = true)) {
                    Log.d("RevisionPoll", "[poll] tick skipped — cloudSyncEnabled=false or fdroid")
                    continue
                }
                if (!authTokenManager.isLoggedIn) {
                    Log.d("RevisionPoll", "[poll] tick skipped — not logged in")
                    continue
                }
                if (!networkChecker.isAvailable()) {
                    Log.d("RevisionPoll", "[poll] tick skipped — no network")
                    continue
                }
                Log.d("RevisionPoll", "[poll] tick — fetching revision (lastKnown=${syncState.lastKnownRevision})")
                try {
                    val revision = syncApiClient.fetchRevision()
                    Log.d("RevisionPoll", "[poll] server revision=$revision lastKnown=${syncState.lastKnownRevision}")
                    if (revision > syncState.lastKnownRevision) {
                        Log.d("RevisionPoll", "[poll] revision changed — triggering full sync")
                        syncAndRepublish()
                    }
                } catch (e: Exception) {
                    Log.w("RevisionPoll", "[poll] tick failed", e)
                }
            }
        }
    }

    fun stopRevisionPolling() {
        revisionPollingJob?.cancel()
        revisionPollingJob = null
    }

    /**
     * Pull from the backend, then re-publish whatever landed in the cache.
     *
     * The re-read is not redundant: the sync writes merged courses and
     * deletions straight to disk, so the in-memory lists are stale by the
     * time it returns. This is the "catch up quietly" path — no Moodle
     * refresh, no spinner — used by the foreground sync and the poller.
     */
    private suspend fun syncAndRepublish() {
        backendSync.pull(syncState)
        val courses = dataCache.loadCourses()
            .filter { it.courseNo !in dataCache.loadDeletedCourseNos() }
        val assignments = dataCache.loadAssignments()
        TigerDuckTheme.buildCourseColorMap(courses)
        updateCoursesAndAssignments(courses, assignments)
        dataCache.notifyBackgroundSyncComplete()
    }

    private suspend fun fetchData(forceRemote: Boolean) {
        _isLoading.value = true
        try {
            var courses = dataCache.loadCourses()
            var assignments = dataCache.loadAssignments()

            if (forceRemote) {
                // Moodle-direct for the assignment/course list (proven,
                // correct semester filtering). Backend handles override
                // sync only (done/ignored marks across devices).
                backendSync.pull(syncState)
                // Re-read after backend sync so server-merged courses and
                // deletions are reflected even if the Moodle fetch below fails.
                courses = dataCache.loadCourses()
                    .filter { it.courseNo !in dataCache.loadDeletedCourseNos() }

                val studentId = authService.storedStudentId
                val password = authService.storedPassword
                if (!studentId.isNullOrBlank() && !password.isNullOrBlank()) {
                    val (remoteCourses, remoteAssignments) =
                        fetchCoursesAndAssignments(studentId, password)

                    if (!remoteCourses.isNullOrEmpty()) {
                        // Re-read cache so a concurrent color change isn't erased,
                        // and so manually-added courses survive the refresh.
                        courses = HomeCourseMerge.mergeRemote(
                            remote = remoteCourses,
                            cached = dataCache.loadCourses(),
                            deletedNos = dataCache.loadDeletedCourseNos(),
                        )
                        dataCache.saveCourses(courses)
                        widgetUpdater.requestUpdate()
                    }

                    // Symmetric with the courses branch above: treat an empty
                    // remote result as "upstream returned nothing useful"
                    // (NetScaler challenge, token-rotation race, semester-code
                    // skew in MoodleService.fetchAssignments) rather than
                    // clobbering a valid cache with an empty list.
                    if (!remoteAssignments.isNullOrEmpty()) {
                        assignments = remoteAssignments
                        dataCache.saveAssignments(remoteAssignments)
                        runCatching { pushApiClient.uploadAssignments(remoteAssignments) }
                            .onFailure {
                                prefs.setLastSyncSource(SyncSource.LOCAL)
                                Log.w("HomeViewModel", "uploadAssignments failed (non-fatal)", it)
                            }
                    }
                }
            }

            TigerDuckTheme.buildCourseColorMap(courses)
            updateCoursesAndAssignments(courses, assignments)
            if (forceRemote) {
                dataCache.notifyBackgroundSyncComplete()
                if (authService.isNtustAuthenticated) {
                    _syncCompleteEvent.tryEmit(Unit)
                }
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
        // TTL-throttled, so this costs one request an hour however often Home
        // refreshes. Resolved before the gate below, which depends on it.
        semesterCatalog.refreshIfStale()
        val semester = courseService.currentSemesterCode()
        // 選課 serves exactly one term and its 選課清單 page carries no term
        // marker, so its course numbers belong to whichever term the catalogue
        // reports as open. While that runs ahead of the term in session —
        // 選課 for the next term opens weeks before it starts — importing them
        // here would file the *next* term's enrolments into this term's cache.
        val servesSelectionSemester = semester == semesterCatalog.selectionSemesterCode()

        // Moodle webservice calls auth with a long-lived wstoken, so they
        // don't need the NTUST SSO cookies that ensureAuthenticated refreshes.
        // Let them run concurrently with the SSO + course-selection scrape.
        val moodleEnrolledDef = async {
            try {
                if (BuildConfig.DEBUG) ServerFailureSimulator.check(ServerKind.MOODLE)
                moodleService.fetchEnrolledCourses()
            } catch (e: Exception) {
                ServerStatusTracker.set(ServerStatus.FAILED, ServerKind.MOODLE)
                Log.e("HomeViewModel", "Failed to fetch Moodle enrolled courses", e)
                null
            }
        }
        val courseNosDef = async {
            if (!servesSelectionSemester) return@async emptyList()
            val authed = runCatching { authService.ensureAuthenticated() }.getOrDefault(false)
            if (!authed) return@async null
            try {
                if (BuildConfig.DEBUG) ServerFailureSimulator.check(ServerKind.COURSE_SELECTION)
                val nos = courseService.fetchEnrolledCourseNos(studentId, password)
                ServerStatusTracker.set(ServerStatus.OK, ServerKind.COURSE_SELECTION)
                nos
            } catch (e: Exception) {
                ServerStatusTracker.set(ServerStatus.FAILED, ServerKind.COURSE_SELECTION)
                Log.e("HomeViewModel", "Failed to fetch enrolled course numbers", e)
                null
            }
        }

        val courseNos = courseNosDef.await()
        val moodleEnrolled = moodleEnrolledDef.await()
        val moodleForSemester = CourseRosterMerge.moodleCoursesFor(semester, moodleEnrolled)
        val moodleByNo = moodleForSemester.associateBy { it.courseNo }
        val orderedCourseNos = CourseRosterMerge.rosterOrder(courseNos, moodleForSemester)
        val rosterCourseNos = orderedCourseNos.toSet()

        val coursesDef = async {
            if (courseNos == null && moodleEnrolled == null) return@async null

            if (orderedCourseNos.isEmpty()) return@async emptyList()

            orderedCourseNos.map { courseNo ->
                async { courseService.lookupOrFallback(semester, courseNo, moodleByNo[courseNo]) }
            }.awaitAll().filterNotNull()
        }

        val assignmentsDef = async {
            if (moodleEnrolled == null) return@async null
            try {
                val remote = moodleService.fetchAssignments(
                    enrolledCourses = moodleEnrolled,
                    rosterCourseNos = rosterCourseNos
                )
                val existingCompleted =
                    CourseRosterMerge.completedIds(dataCache.loadAssignments())
                ServerStatusTracker.set(ServerStatus.OK, ServerKind.MOODLE)
                CourseRosterMerge.preserveConfirmedSubmissions(remote, existingCompleted)
            } catch (e: Exception) {
                ServerStatusTracker.set(ServerStatus.FAILED, ServerKind.MOODLE)
                Log.e("HomeViewModel", "Failed to fetch assignments", e)
                null
            }
        }

        coursesDef.await() to assignmentsDef.await()
    }

    private fun updateCoursesAndAssignments(courses: List<Course>, assignments: List<Assignment>) {
        _allCourses.value = courses
        val todayIndex = AppConstants.weekdayIndex(
            AppClock.calendar().get(Calendar.DAY_OF_WEEK)
        )
        _todayCourses.value = courses.filter { it.schedule.containsKey(todayIndex) }
        _allAssignments.value = assignments.sortedBy { it.dueDate }

        // Schedule notifications for upcoming assignments. Items the user has
        // ignored or marked-done still get scheduled, but as safety-net
        // reminders — they fire at the same lead time as regular alerts but
        // call out that the homework was dismissed without being submitted.
        if (prefs.notifyAssignments) {
            rescheduleAssignmentNotifications(assignments)
        }

        // Refresh the Live Update (Android analogue of the iOS dynamic island)
        liveActivityManager.refresh()
    }

    /**
     * Re-arm every assignment alarm against the current ignored/marked sets.
     * The scheduler decides REGULAR vs SAFETY_NET per id; we just hand it the
     * full list of non-completed assignments and the union of dismissed ids.
     */
    private fun rescheduleAssignmentNotifications(assignments: List<Assignment>) {
        val safetyNetIds = _ignoredAssignmentIds.value + _markedCompletedIds.value
        notificationScheduler.scheduleAll(
            assignments.filter { !it.isCompleted },
            safetyNetIds,
            prefs.notifyAssignmentOffsets,
        )
    }

    fun cancelAllAssignmentNotifications() {
        notificationScheduler.cancelAllTracked()
    }

    fun hasUnfinishedAssignment(courseNo: String): Boolean =
        HomeAssignmentFilters.anyUnfinishedFor(
            all = _allAssignments.value,
            courseNo = courseNo,
            ignoredIds = _ignoredAssignmentIds.value,
            markedCompletedIds = _markedCompletedIds.value,
        )

    fun assignmentsFor(courseNo: String): List<Assignment> =
        HomeAssignmentFilters.unfinishedFor(
            all = _allAssignments.value,
            courseNo = courseNo,
            ignoredIds = _ignoredAssignmentIds.value,
            markedCompletedIds = _markedCompletedIds.value,
        )

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

    fun toggleIgnore(assignment: Assignment) = toggleOverride(
        assignment = assignment,
        primary = _ignoredAssignmentIds,
        primaryChannel = saveIgnoredChannel,
        secondary = _markedCompletedIds,
        secondaryChannel = saveMarkedCompletedChannel,
        statusWhenSet = AssignmentOverrideReconciler.STATUS_IGNORED,
    )

    fun toggleMarkCompleted(assignment: Assignment) = toggleOverride(
        assignment = assignment,
        primary = _markedCompletedIds,
        primaryChannel = saveMarkedCompletedChannel,
        secondary = _ignoredAssignmentIds,
        secondaryChannel = saveIgnoredChannel,
        statusWhenSet = AssignmentOverrideReconciler.STATUS_COMPLETED,
    )

    /**
     * Flip one override on or off, locally first and upstream after.
     *
     * 已忽略 and 標示為完成 are mutually exclusive, so setting either clears
     * the other — that is what [secondary] is for. The UI updates before the
     * PATCH goes out and is never rolled back if it fails: the id stays in
     * this device's cache and the next sync re-asserts it, which is a better
     * outcome than a row that silently springs back under the user's finger.
     */
    private fun toggleOverride(
        assignment: Assignment,
        primary: MutableStateFlow<Set<String>>,
        primaryChannel: Channel<Set<String>>,
        secondary: MutableStateFlow<Set<String>>,
        secondaryChannel: Channel<Set<String>>,
        statusWhenSet: String,
    ) {
        val id = assignment.assignmentId
        val wasSet = id in primary.value
        primary.update { if (wasSet) it - id else it + id }
        if (!wasSet) {
            secondary.update { it - id }
            secondaryChannel.trySend(secondary.value)
        }
        primaryChannel.trySend(primary.value)
        if (prefs.notifyAssignments) {
            rescheduleAssignmentNotifications(_allAssignments.value)
        }
        val status = if (wasSet) AssignmentOverrideReconciler.STATUS_NONE else statusWhenSet
        pendingOverrides.add(id)
        viewModelScope.launch {
            try {
                pushApiClient.patchAssignmentOverride(
                    id.toIntOrNull() ?: return@launch, status,
                )
            } catch (e: Exception) {
                Log.w("HomeViewModel", "override PATCH FAILED: $id → $status", e)
            } finally {
                // Always release the in-flight lock — a failed PATCH must not
                // pin this id in pendingOverrides forever, which would block it
                // from ever being reconciled from the server again.
                pendingOverrides.remove(id)
            }
        }
    }

    fun selectCourse(info: SelectedCourseInfo?) {
        _selectedCourse.value = info
    }

    // 翹課 — see the block above _skippedDates.
    // fun toggleSkip(course: Course, date: Date) {
    //     val key = date.toInstant().atZone(AppConstants.TAIPEI_ZONE).toLocalDate().format(SKIP_DATE_FMT)
    //     _skippedDates.update { current ->
    //         val map = current.toMutableMap()
    //         val dates = (map[course.courseNo] ?: emptyList()).toMutableList()
    //         if (key in dates) dates.remove(key) else dates.add(key)
    //         map[course.courseNo] = dates
    //         map
    //     }
    //     saveSkipChannel.trySend(_skippedDates.value)
    // }

    fun removeSection(sectionId: String) {
        persistSections(HomeSectionLayout.remove(_sections.value, sectionId))
    }

    fun moveSections(fromId: String, toId: String) {
        val moved = HomeSectionLayout.move(_sections.value, fromId, toId)
        // Identity, not equality: HomeSectionLayout.move hands back the very
        // list it was given when the drag was a no-op, and a no-op must not
        // write to preferences.
        if (moved !== _sections.value) persistSections(moved)
    }

    // companion object {
    //     private val SKIP_DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE
    // }

    fun addSection(type: HomeSection.HomeSectionType, title: String) {
        persistSections(
            HomeSectionLayout.add(
                sections = _sections.value,
                id = java.util.UUID.randomUUID().toString(),
                type = type,
                title = title,
            )
        )
    }

    private fun persistSections(sections: List<HomeSection>) {
        _sections.value = sections
        prefs.homeSections = sections
    }
}

/**
 * Selection context for the Home detail dialog. Carries the slot-resolved
 * classroom so the popup renders the room for the tapped period only, not
 * the union across the whole day's split rooms.
 */
data class SelectedCourseInfo(val course: Course, val classroom: String)
