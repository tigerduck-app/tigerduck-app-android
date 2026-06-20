package org.ntust.app.tigerduck.ui.screen.home

import android.util.Log
import org.ntust.app.tigerduck.BuildConfig
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
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
import org.ntust.app.tigerduck.notification.SyncSource
import org.ntust.app.tigerduck.push.BackendSyncResult
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
import org.ntust.app.tigerduck.notification.AssignmentNotificationScheduler
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.push.CourseOverrideResult
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val networkChecker: NetworkChecker,
    private val authService: AuthService,
    private val dataCache: DataCache,
    private val courseService: CourseService,
    private val moodleService: MoodleService,
    private val notificationScheduler: AssignmentNotificationScheduler,
    private val prefs: AppPreferences,
    private val courseColorStore: CourseColorStore,
    private val liveActivityManager: LiveActivityManager,
    private val widgetUpdater: org.ntust.app.tigerduck.widget.WidgetUpdater,
    private val pushApiClient: PushApiClient,
    private val syncApiClient: SyncApiClient,
    private val authTokenManager: AuthTokenManager,
) : ViewModel() {

    data class SyncConflict(
        val id: String,
        val kind: String,
        val label: String,
        val localStatus: String,
        val serverStatus: String,
    )

    val isSyncLocalOnly = prefs.lastSyncSource
        .map { prefs.cloudSyncEnabled && it == SyncSource.LOCAL }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _syncConflicts = MutableStateFlow<List<SyncConflict>>(emptyList())
    val syncConflicts: StateFlow<List<SyncConflict>> = _syncConflicts


    private var _pendingSyncResult: BackendSyncResult? = null

    fun resolveSyncConflicts(keepLocal: Boolean) {
        val result = _pendingSyncResult ?: return
        val conflicts = _syncConflicts.value.toList()
        _pendingSyncResult = null
        _syncConflicts.value = emptyList()
        viewModelScope.launch {
            if (keepLocal) {
                for (c in conflicts) {
                    val mid = c.id.toIntOrNull() ?: continue
                    runCatching { pushApiClient.patchAssignmentOverride(mid, c.localStatus) }
                }
            } else {
                applyServerOverrides(result)
            }
        }
    }

    private suspend fun applyServerOverrides(result: BackendSyncResult) {
        val safeIgnored = result.ignoredIds +
            _ignoredAssignmentIds.value.filter { it in pendingOverrides }
        val safeCompleted = result.completedIds +
            _markedCompletedIds.value.filter { it in pendingOverrides }
        dataCache.replaceIgnoredAssignments(safeIgnored)
        dataCache.replaceMarkedCompletedAssignments(safeCompleted)
        _ignoredAssignmentIds.value = safeIgnored
        _markedCompletedIds.value = safeCompleted
        if (result.courseOverrides.isNotEmpty()) {
            applyCourseOverrides(result.courseOverrides)
        }
    }

    private suspend fun syncOverridesFromBackend(retried: Boolean = false) {
        if (!prefs.cloudSyncEnabled || BuildConfig.FLAVOR.equals("fdroid", ignoreCase = true)) {
            prefs.setLastSyncSource(SyncSource.NONE)
            return
        }
        if (!authTokenManager.isLoggedIn) {
            prefs.setLastSyncSource(SyncSource.NONE)
            return
        }
        try {
            val result = syncApiClient.fetchFullSync()
            val localIgnored = dataCache.loadIgnoredAssignments()
            val localMarked = dataCache.loadMarkedCompletedAssignments()

            val isFirstTimeMigration = result.ignoredIds.isEmpty() && result.completedIds.isEmpty()
                && (localIgnored.isNotEmpty() || localMarked.isNotEmpty())
            if (isFirstTimeMigration) {
                for (id in localIgnored) {
                    runCatching { pushApiClient.patchAssignmentOverride(id.toIntOrNull() ?: return@runCatching, "ignored") }
                }
                for (id in localMarked) {
                    runCatching { pushApiClient.patchAssignmentOverride(id.toIntOrNull() ?: return@runCatching, "locally_completed") }
                }
            } else {
                val conflicts = mutableListOf<SyncConflict>()
                val allIds = (result.ignoredIds + result.completedIds + localIgnored + localMarked).toSet()
                val assignmentsByMoodleId = _allAssignments.value.associateBy { it.assignmentId }
                for (id in allIds) {
                    if (id in pendingOverrides) continue
                    val serverStatus = when {
                        id in result.ignoredIds -> "ignored"
                        id in result.completedIds -> "locally_completed"
                        else -> "none"
                    }
                    val localStatus = when {
                        id in localIgnored -> "ignored"
                        id in localMarked -> "locally_completed"
                        else -> "none"
                    }
                    if (serverStatus != localStatus) {
                        val a = assignmentsByMoodleId[id]
                        conflicts.add(SyncConflict(
                            id = id,
                            kind = "作業",
                            label = a?.title ?: "ID $id",
                            localStatus = localStatus,
                            serverStatus = serverStatus,
                        ))
                    }
                }

                // Always apply non-conflicting items
                val conflictIds = conflicts.map { it.id }.toSet()
                val safeIgnored = result.ignoredIds.filter { it !in conflictIds } +
                    _ignoredAssignmentIds.value.filter { it in pendingOverrides }
                val safeCompleted = result.completedIds.filter { it !in conflictIds } +
                    _markedCompletedIds.value.filter { it in pendingOverrides }
                // Preserve local state for conflicting items until user resolves
                val finalIgnored = safeIgnored.toMutableSet()
                val finalCompleted = safeCompleted.toMutableSet()
                for (c in conflicts) {
                    when (c.localStatus) {
                        "ignored" -> finalIgnored.add(c.id)
                        "locally_completed" -> finalCompleted.add(c.id)
                    }
                }
                dataCache.replaceIgnoredAssignments(finalIgnored)
                dataCache.replaceMarkedCompletedAssignments(finalCompleted)
                _ignoredAssignmentIds.value = finalIgnored
                _markedCompletedIds.value = finalCompleted

                if (conflicts.isNotEmpty()) {
                    _pendingSyncResult = result
                    _syncConflicts.value = conflicts
                }
            }

            // Course overrides and deletion detection always run regardless
            // of whether this was a first-time migration.
            if (result.courseOverrides.isNotEmpty()) {
                applyCourseOverrides(result.courseOverrides)
            }
            // Conflict resolution: detect reset + process tombstones
            val lastCourseSyncAt = context.getSharedPreferences("tigerduck_sync", 0)
                .getLong("last_course_sync_at", 0L)
            val resetAt = result.coursesResetAt?.let { parseIsoTimestamp(it) } ?: 0L
            if (resetAt > lastCourseSyncAt && lastCourseSyncAt > 0L) {
                val allCourses = dataCache.loadCourses().toMutableList()
                allCourses.removeAll { it.isManual }
                dataCache.saveCourses(allCourses)
                dataCache.saveDeletedCourseNos(emptySet())
                Log.i("HomeViewModel", "[sync] courses reset detected, wiped manual courses")
            }
            val tombstonedNos = result.tombstones.mapNotNull { it.courseNo }.toSet()

            if (result.serverCourseNos.isNotEmpty()) {
                val localCourseNos = dataCache.loadCourses().map { it.courseNo }.toSet()
                val deleted = dataCache.loadDeletedCourseNos().toMutableSet()
                val sizeBefore = deleted.size
                Log.i("HomeViewModel", "[sync-debug] serverCourseNos=${result.serverCourseNos.sorted()} localCourseNos=${localCourseNos.sorted()} deletedNos=${deleted.sorted()}")
                // Any local course not on the server → treat as deleted
                for (no in localCourseNos) {
                    if (no !in result.serverCourseNos) {
                        Log.i("HomeViewModel", "[sync-debug] marking $no as deleted (local-only, not in server)")
                        deleted.add(no)
                    }
                }
                // Apply tombstones
                for (no in tombstonedNos) {
                    if (no !in result.serverCourseNos && no !in deleted) {
                        Log.i("HomeViewModel", "[sync-debug] marking $no as deleted (tombstone)")
                        deleted.add(no)
                    }
                }
                // Bug fix: also remove manual (server-merged) courses not on server
                val allCoursesForClean = dataCache.loadCourses().toMutableList()
                val manualRemoved = allCoursesForClean.removeAll { it.isManual && it.courseNo !in result.serverCourseNos }
                if (manualRemoved) {
                    dataCache.saveCourses(allCoursesForClean)
                    Log.i("HomeViewModel", "[sync-debug] removed manual courses not on server")
                }
                // Any previously-deleted course that reappeared on the server → un-delete
                val undeleted = deleted.filter { it in result.serverCourseNos }
                if (undeleted.isNotEmpty()) Log.i("HomeViewModel", "[sync-debug] un-deleting $undeleted")
                deleted.removeAll { it in result.serverCourseNos }
                if (deleted.size != sizeBefore || deleted != dataCache.loadDeletedCourseNos()) {
                    dataCache.saveDeletedCourseNos(deleted)
                }

                // Merge courses from server that don't exist locally
                val semester = courseService.currentSemesterCode()
                val missingLocally = result.serverCourseNos - localCourseNos - deleted
                Log.i("HomeViewModel", "[sync-debug] semester=$semester missingLocally=${missingLocally.sorted()} serverCourseSemesters=${result.serverCourses.map { it.semester }.toSet()}")
                if (missingLocally.isNotEmpty()) {
                    val current = dataCache.loadCourses().toMutableList()
                    val currentNos = current.map { it.courseNo }.toSet()
                    for (sc in result.serverCourses) {
                        if (sc.courseNo in missingLocally && sc.courseNo !in currentNos && sc.semester == semester) {
                            current.add(Course(
                                courseNo = sc.courseNo,
                                courseName = sc.courseName,
                                instructor = sc.instructors.joinToString(", "),
                                credits = sc.credits,
                                classroom = sc.classroom,
                                enrolledCount = sc.enrolledCount,
                                maxCount = sc.maxCount,
                                moodleIdNumber = sc.moodleId,
                                isManual = true,
                                scheduleJson = sc.scheduleJson,
                                classroomMapJson = sc.classroomMapJson,
                            ))
                            Log.i("HomeViewModel", "[Sync] merged course from server: ${sc.courseNo}")
                        }
                    }
                    if (current.size > dataCache.loadCourses().size) {
                        dataCache.saveCourses(current)
                    }
                }
            } else {
                val localCourses = dataCache.loadCourses()
                if (localCourses.isNotEmpty()) {
                    val semester = courseService.currentSemesterCode()
                    runCatching { pushApiClient.uploadCourses(localCourses, semester) }
                        .onFailure { e -> Log.w("HomeViewModel", "[Sync] auto-upload failed", e) }
                    Log.i("HomeViewModel", "[Sync] backend empty, auto-uploaded ${localCourses.size} courses")
                }
            }
            context.getSharedPreferences("tigerduck_sync", 0).edit()
                .putLong("last_course_sync_at", System.currentTimeMillis())
                .apply()
            prefs.setLastSyncSource(SyncSource.BACKEND)
        } catch (e: Exception) {
            prefs.setLastSyncSource(SyncSource.LOCAL)
            if (!retried && (e.message?.contains("401") == true || e.message?.contains("session_revoked") == true)) {
                val reloginOk = attemptBackendRelogin()
                if (reloginOk) {
                    syncOverridesFromBackend(retried = true)
                }
            }
            Log.w("HomeViewModel", "[Sync] override sync failed", e)
        }
    }

    private suspend fun attemptBackendRelogin(): Boolean {
        val studentId = authService.storedStudentId ?: return false
        val moodleToken = authService.storedMoodleToken
        if (moodleToken.isNullOrEmpty()) {
            return false
        }
        return try {
            authTokenManager.login(
                studentId = studentId,
                password = "",
                moodleToken = moodleToken,
                moodlePrivateToken = null,
            )
            true
        } catch (e: Exception) {
            Log.w("HomeViewModel", "[Sync] auto-relogin failed", e)
            false
        }
    }

    private suspend fun applyCourseOverrides(overrides: List<CourseOverrideResult>) {
        val courses = _allCourses.value.ifEmpty { return }
        var changed = false
        val updated = courses.map { course ->
            val override = overrides.find { it.courseNo == course.courseNo }
                ?: overrides.find { it.moodleCourseId == course.moodleNumericCourseId?.toString() }
                ?: return@map course
            val newHex = override.colorHex
            if (newHex != course.customColorHex) {
                changed = true
                course.copy(customColorHex = newHex)
            } else course
        }
        // Sync custom names from server
        var nameCount = 0
        val customNames = dataCache.loadCourseCustomNames().toMutableMap()
        for (o in overrides) {
            val no = o.courseNo ?: continue
            if (o.customNames.isNotEmpty()) {
                val existing = customNames[no]?.toMutableMap() ?: mutableMapOf()
                for ((locale, name) in o.customNames) {
                    if (name.isEmpty()) existing.remove(locale) else existing[locale] = name
                }
                if (existing.isEmpty()) customNames.remove(no) else customNames[no] = existing.toMap()
                nameCount++
            }
        }
        if (nameCount > 0) {
            dataCache.saveCourseCustomNames(customNames)
        }
        if (changed) {
            _allCourses.value = updated
            TigerDuckTheme.buildCourseColorMap(updated)
            dataCache.saveCourses(updated)
        } else {
        }
    }

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
        // "Effectively done" = Moodle says submitted OR the user manually
        // marked it from the swipe gesture. Both buckets get treated the
        // same for filter/sort purposes.
        fun done(a: Assignment) = a.isCompleted || a.assignmentId in marked
        when (filter) {
            AssignmentFilter.INCOMPLETE ->
                all.filter { !done(it) && it.assignmentId !in ignored }
                    .sortedBy { it.dueDate }

            AssignmentFilter.ALL -> {
                // 全部: show everything (including ignored and marked-done),
                // matching iOS allCandidates(). Future first (soonest on top),
                // then past (most recently due first).
                val now = Date(AppClock.nowMillis())
                val (future, past) = all.partition { !it.dueDate.before(now) }
                future.sortedBy { it.dueDate } +
                        past.sortedByDescending { it.dueDate }
            }

            AssignmentFilter.IGNORED ->
                all.filter { it.assignmentId in ignored }.sortedBy { it.dueDate }
        }
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
                    _skippedDates.value = emptyMap()
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
            _skippedDates.value = dataCache.loadSkippedDates()
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
                syncOverridesFromBackend()
                val courses = dataCache.loadCourses()
                    .filter { it.courseNo !in dataCache.loadDeletedCourseNos() }
                val assignments = dataCache.loadAssignments()
                TigerDuckTheme.buildCourseColorMap(courses)
                updateCoursesAndAssignments(courses, assignments)
                dataCache.notifyBackgroundSyncComplete()
            }
        }
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
                syncOverridesFromBackend()
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
                        val cached = dataCache.loadCourses()
                        val deletedNos = dataCache.loadDeletedCourseNos()
                        val latestColors = cached.associate { it.courseNo to it.customColorHex }
                        val fetched = remoteCourses.map { c ->
                            c.copy(customColorHex = latestColors[c.courseNo])
                        }
                        val fetchedNos = fetched.map { it.courseNo }.toSet()
                        val manualLeftovers =
                            cached.filter { it.isManual && it.courseNo !in fetchedNos }
                        courses = (fetched + manualLeftovers)
                            .filter { it.courseNo !in deletedNos }
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
                        if (prefs.cloudSyncEnabled && !BuildConfig.FLAVOR.equals("fdroid", ignoreCase = true)) {
                            runCatching { pushApiClient.uploadAssignments(remoteAssignments) }
                                .onFailure {
                                    prefs.setLastSyncSource(SyncSource.LOCAL)
                                    Log.w("HomeViewModel", "uploadAssignments failed (non-fatal)", it)
                                }
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
        val semester = courseService.currentSemesterCode()

        // Moodle webservice calls auth with a long-lived wstoken, so they
        // don't need the NTUST SSO cookies that ensureAuthenticated refreshes.
        // Let them run concurrently with the SSO + course-selection scrape.
        val moodleEnrolledDef = async {
            try {
                moodleService.fetchEnrolledCourses()
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
        val moodleForSemester = moodleEnrolled
            .orEmpty()
            .filter { it.semesterCode == semester && it.courseNo.isNotEmpty() }
        val moodleByNo = moodleForSemester.associateBy { it.courseNo }
        val orderedCourseNos = LinkedHashSet<String>().apply {
            courseNos?.forEach { add(it) }
            moodleForSemester.forEach { add(it.courseNo) }
        }.toList()
        val rosterCourseNos = orderedCourseNos.toSet()

        val coursesDef = async {
            if (courseNos == null && moodleEnrolled == null) return@async null

            if (orderedCourseNos.isEmpty()) return@async emptyList()

            orderedCourseNos.map { courseNo ->
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
                                moodleIdNumber = moodleByNo[courseNo]?.idnumber
                                    ?: "${r.semester}${r.courseNo}"
                            )
                        } else {
                            CourseService.fallbackCourseFromMoodle(courseNo, moodleByNo[courseNo])
                        }
                    } catch (e: Exception) {
                        Log.e("HomeViewModel", "Failed to lookup course $courseNo", e)
                        CourseService.fallbackCourseFromMoodle(courseNo, moodleByNo[courseNo])
                    }
                }
            }.awaitAll().filterNotNull()
        }

        val assignmentsDef = async {
            if (moodleEnrolled == null) return@async null
            try {
                val remote = moodleService.fetchAssignments(
                    enrolledCourses = moodleEnrolled,
                    rosterCourseNos = rosterCourseNos
                )
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
        val todayIndex =
            AppClock.calendar().get(Calendar.DAY_OF_WEEK).let {
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

    fun hasUnfinishedAssignment(courseNo: String): Boolean {
        val ignored = _ignoredAssignmentIds.value
        val marked = _markedCompletedIds.value
        return _allAssignments.value.any {
            it.courseNo == courseNo && !it.isCompleted &&
                    it.assignmentId !in ignored && it.assignmentId !in marked
        }
    }

    fun assignmentsFor(courseNo: String): List<Assignment> {
        val ignored = _ignoredAssignmentIds.value
        val marked = _markedCompletedIds.value
        return _allAssignments.value.filter {
            it.courseNo == courseNo && !it.isCompleted &&
                    it.assignmentId !in ignored && it.assignmentId !in marked
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
        val id = assignment.assignmentId
        val wasIgnored = id in _ignoredAssignmentIds.value
        _ignoredAssignmentIds.update { if (wasIgnored) it - id else it + id }
        if (!wasIgnored) {
            _markedCompletedIds.update { it - id }
            saveMarkedCompletedChannel.trySend(_markedCompletedIds.value)
        }
        saveIgnoredChannel.trySend(_ignoredAssignmentIds.value)
        if (prefs.notifyAssignments) {
            rescheduleAssignmentNotifications(_allAssignments.value)
        }
        val status = if (wasIgnored) "none" else "ignored"
        pendingOverrides.add(id)
        viewModelScope.launch {
            if (!prefs.cloudSyncEnabled || BuildConfig.FLAVOR.equals("fdroid", ignoreCase = true)) return@launch
            try {
                pushApiClient.patchAssignmentOverride(
                    id.toIntOrNull() ?: return@launch, status,
                )
                pendingOverrides.remove(id)
            } catch (e: Exception) {
                Log.w("HomeViewModel", "override PATCH FAILED: $id → $status", e)
            }
        }
    }

    fun toggleMarkCompleted(assignment: Assignment) {
        val id = assignment.assignmentId
        val wasCompleted = id in _markedCompletedIds.value
        _markedCompletedIds.update { if (wasCompleted) it - id else it + id }
        if (!wasCompleted) {
            _ignoredAssignmentIds.update { it - id }
            saveIgnoredChannel.trySend(_ignoredAssignmentIds.value)
        }
        saveMarkedCompletedChannel.trySend(_markedCompletedIds.value)
        if (prefs.notifyAssignments) {
            rescheduleAssignmentNotifications(_allAssignments.value)
        }
        val status = if (wasCompleted) "none" else "locally_completed"
        pendingOverrides.add(id)
        viewModelScope.launch {
            if (!prefs.cloudSyncEnabled || BuildConfig.FLAVOR.equals("fdroid", ignoreCase = true)) return@launch
            try {
                pushApiClient.patchAssignmentOverride(
                    id.toIntOrNull() ?: return@launch, status,
                )
                pendingOverrides.remove(id)
            } catch (e: Exception) {
                Log.w("HomeViewModel", "override PATCH FAILED: $id → $status", e)
            }
        }
    }

    fun selectCourse(info: SelectedCourseInfo?) {
        _selectedCourse.value = info
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

    private fun parseIsoTimestamp(s: String): Long {
        if (s.isBlank()) return 0L
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.parse(s.take(19))?.time ?: 0L
        } catch (_: Exception) { 0L }
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

/**
 * Selection context for the Home detail dialog. Carries the slot-resolved
 * classroom so the popup renders the room for the tapped period only, not
 * the union across the whole day's split rooms.
 */
data class SelectedCourseInfo(val course: Course, val classroom: String)
