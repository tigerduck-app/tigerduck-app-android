package org.ntust.app.tigerduck.ui.screen.home

import android.os.SystemClock
import android.util.Log
import org.ntust.app.tigerduck.data.CourseRosterMerge
import org.ntust.app.tigerduck.data.TranscriptTerms
import org.ntust.app.tigerduck.BuildConfig
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
import org.ntust.app.tigerduck.academic.QuietDays
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.ui.component.ServerFailureSimulator
import org.ntust.app.tigerduck.ui.component.ServerKind
import org.ntust.app.tigerduck.ui.component.ServerStatus
import org.ntust.app.tigerduck.ui.component.ServerStatusTracker
import org.ntust.app.tigerduck.notification.SyncSource
import org.ntust.app.tigerduck.push.SyncApiClient
import org.ntust.app.tigerduck.data.CourseColorStore
import org.ntust.app.tigerduck.data.CourseIgnores
import org.ntust.app.tigerduck.data.CourseTombstoneKeys
import org.ntust.app.tigerduck.data.OutstandingAssignments
import org.ntust.app.tigerduck.data.RefreshPolicy
import org.ntust.app.tigerduck.data.RefreshTriggers
import org.ntust.app.tigerduck.data.UnsentMarks
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.AppFeature
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
import org.ntust.app.tigerduck.review.ReviewPrompt
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import org.ntust.app.tigerduck.util.SingleFlight
import org.ntust.app.tigerduck.util.logSummary
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes

/**
 * When Home fetches on its own — see [RefreshPolicy]. Everything is on,
 * because Home's assignment list is the one thing in the app that goes stale
 * in minutes: something submitted on Moodle should show here without a pull.
 */
internal val HomeRefreshPolicy = RefreshPolicy(
    onLaunch = true,
    onForeground = true,
    onRevisit = true,
    background = 15.minutes,
    minInterval = 1.minutes,
)

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
    private val academicCalendar: org.ntust.app.tigerduck.academic.AcademicCalendarStore,
    private val widgetUpdater: org.ntust.app.tigerduck.widget.WidgetUpdater,
    private val pushApiClient: PushApiClient,
    private val syncApiClient: SyncApiClient,
    private val authTokenManager: AuthTokenManager,
    private val backendSync: HomeBackendSync,
    private val unsentMarks: UnsentMarks,
    private val localEdits: LocalEdits,
    private val reenableCheck: org.ntust.app.tigerduck.ui.screen.settings.ReenableCheck,
    private val reviewPrompt: ReviewPrompt,
) : ViewModel() {


    /**
     * Whether classes are in session, from the school's published calendar.
     *
     * Exposed here rather than read in the Composable so Home does not have
     * to hold a store of its own, and so the answer moves with the same
     * clock version the rest of the screen already recomposes on.
     */
    fun isTermInSession(): Boolean =
        academicCalendar.current().isInSession(
            org.ntust.app.tigerduck.shared.clock.AppClock.localDateTime().toLocalDate()
        )

    /** The days the time slider leaves off; see [QuietDays]. */
    fun quietDays(): QuietDays = academicCalendar.quietDays()

    /** Whether Home is being arranged; held here so leaving the page can end it (see [HomeEditing]). */
    val editing = HomeEditing()

    private val _sections = MutableStateFlow(prefs.homeSections)
    val sections: StateFlow<List<HomeSection>> = _sections

    private val _allCourses = MutableStateFlow<List<Course>>(emptyList())
    val allCourses: StateFlow<List<Course>> = _allCourses

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

    // Courses ignored from the class table; see CourseIgnores. The class
    // table writes them, so they are read back from the cache here.
    private val _ignoredCourseKeys = MutableStateFlow<Set<String>>(emptySet())

    /** Assignments ignored along with their whole course. */
    val courseIgnoredIds: StateFlow<Set<String>> = combine(
        _allAssignments,
        _ignoredCourseKeys,
    ) { all, keys ->
        CourseIgnores.assignmentIds(all, keys, courseService.currentSemesterCode())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

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
        ignoredCourseKeys = _ignoredCourseKeys,
        allAssignments = _allAssignments,
        allCourses = _allCourses,
        conflicts = MutableStateFlow(emptyList()),
        pendingOverrides = pendingOverrides,
    )

    val syncConflicts: StateFlow<List<AssignmentSyncConflict>> = syncState.conflicts

    // Full syncs under way. A poll that finds the server ahead leaves the
    // change to them rather than fetch it a second time — as on a return,
    // when the poll and Home's own sync start together.
    private val backendPullsInFlight = AtomicInteger(0)

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
        combine(_ignoredAssignmentIds, _ignoredCourseKeys, ::Pair),
        _markedCompletedIds,
        _assignmentFilter,
        appClockChanges,
    ) { all, (ignored, courseKeys), marked, filter, _ ->
        HomeAssignmentFilters.visible(
            all, ignoredIncludingCourses(all, ignored, courseKeys), marked, filter,
            Date(AppClock.nowMillis()),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Whether the ignored tab has anything to show; see [HomeAssignmentFilters.hasIgnored]. */
    val hasIgnoredAssignments: StateFlow<Boolean> = combine(
        _allAssignments,
        _ignoredAssignmentIds,
        _ignoredCourseKeys,
    ) { all, ignored, courseKeys ->
        HomeAssignmentFilters.hasIgnored(all, ignoredIncludingCourses(all, ignored, courseKeys))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val saveIgnoredChannel = Channel<Set<String>>(Channel.CONFLATED)
    private val saveMarkedCompletedChannel = Channel<Set<String>>(Channel.CONFLATED)

    // Swipes on Home, counted on the main thread; see reloadAdoptedMarks.
    private var markTaps = 0

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    // Every remote fetch goes through this, so launch, a sign-in and a pull
    // that land together run the pipeline once. Above `init`, whose
    // collectors run during construction.
    private val fetchFlight = SingleFlight(viewModelScope)

    private val refreshTriggers = RefreshTriggers(HomeRefreshPolicy, SystemClock::elapsedRealtime)

    // Flips true after the first cache read returns (even if the cache is
    // empty). UI keeps empty-state placeholders hidden until this is set so
    // the first frame never flashes "no data" before cached data appears.
    private val _initialLoadComplete = MutableStateFlow(false)
    val initialLoadComplete: StateFlow<Boolean> = _initialLoadComplete

    val isLoggedIn: StateFlow<Boolean> = authService.authState

    /** For the sync dot's "Last synced" row; see [AppPreferences.schoolDataSyncedAtMs]. */
    val schoolDataSyncedAtMs: StateFlow<Long> = prefs.schoolDataSyncedAtMs

    private val _noNetworkEvent = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val noNetworkEvent: SharedFlow<Unit> = _noNetworkEvent.asSharedFlow()


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
    // not deleted, so marks made before v2.0.1 come back the day the feature
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
            //
            // A sign-in, not the value the StateFlow replays to a new
            // collector: a user already signed in at launch is fetched for by
            // load(), which MainNavigation calls straight after construction.
            // Fetching here as well ran the whole school pipeline twice on
            // every cold start.
            var wasAuthed: Boolean? = null
            authService.authState.collect { isAuthed ->
                val signedIn = isAuthed && wasAuthed == false
                wasAuthed = isAuthed
                if (!isAuthed) {
                    // Whatever is still in flight belongs to the account
                    // that just left, a load still reading its cache too.
                    fetchFlight.cancel()
                    loadJob?.cancel()
                    _allCourses.value = emptyList()
                    _allAssignments.value = emptyList()
                    // _skippedDates.value = emptyMap()
                    _ignoredAssignmentIds.value = emptySet()
                    _markedCompletedIds.value = emptySet()
                    _ignoredCourseKeys.value = emptySet()
                    // The watermark is the departing account's: the next
                    // account's feed read after it would skip that account's
                    // own earlier changes. From 0 the poll runs a full sync
                    // until one lands for the account that signs in.
                    syncState.lastKnownRevision = 0
                    hasLoaded = false
                    _initialLoadComplete.value = true
                } else if (signedIn) {
                    // As a launch, so the page counts as loaded and a return
                    // to the app fetches again. Launched, so a sign-out
                    // mid-fetch reaches the branch above without waiting for
                    // the fetch to finish.
                    loadNow(fetch = true)
                }
            }
        }
        viewModelScope.launch {
            // Language change → re-fetch so today's courses and assignment
            // names render in the new locale. A rerun: a fetch already in
            // flight asked for the old language's names.
            prefs.appLanguageChanged.collect {
                courseService.clearInMemoryLookupCache()
                if (authService.authState.value) requestRefresh(rerun = true)
            }
        }
        viewModelScope.launch {
            dataCache.backgroundSyncVersion.drop(1).collect {
                if (!authService.authState.value) return@collect
                val account = authService.storedStudentId
                val courses = dataCache.loadCourses()
                val assignments = dataCache.loadAssignments()
                val ignored = dataCache.loadIgnoredAssignments()
                val marked = dataCache.loadMarkedCompletedAssignments()
                val courseKeys = dataCache.loadIgnoredCourses()
                // Signed out, or in as someone else, while the files were
                // read: what they held was the departing account's.
                if (!authService.isStillSignedInAs(account)) return@collect
                _ignoredAssignmentIds.value = ignored
                _markedCompletedIds.value = marked
                _ignoredCourseKeys.value = courseKeys
                TigerDuckTheme.buildCourseColorMap(courses)
                updateCoursesAndAssignments(courses, assignments)
            }
        }
        viewModelScope.launch {
            // Marks a re-enable answer took (Use Server): read now, or the
            // next swipe writes the ones held here back over them.
            dataCache.marksAdoptedVersion.drop(1).collect {
                if (!authService.authState.value) return@collect
                val account = authService.storedStudentId
                reloadAdoptedMarks(
                    read = { dataCache.loadIgnoredAssignments() to dataCache.loadMarkedCompletedAssignments() },
                    tapsMade = { markTaps },
                    stillSignedIn = { authService.isStillSignedInAs(account) },
                ) { ignored, completed ->
                    _ignoredAssignmentIds.value = ignored
                    _markedCompletedIds.value = completed
                }
            }
        }
        viewModelScope.launch {
            // Only the ignored courses: Home writes the other two sets itself,
            // and reading them back here could undo a newer swipe.
            dataCache.assignmentMarksVersion.drop(1).collect {
                if (!authService.authState.value) return@collect
                val account = authService.storedStudentId
                val courseKeys = dataCache.loadIgnoredCourses()
                if (authService.isStillSignedInAs(account)) _ignoredCourseKeys.value = courseKeys
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

    // A `migrateColorHashIfNeeded()` used to run here, clearing every cached
    // customColorHex so the v2 hash palette could take over — safe, it
    // assumed, because cloudSyncEnabled meant the user's real picks would come
    // back down on the next sync. That flag defaults to true, so it is also
    // true for a v1.4.4 user who has never synced anything, and v1.4.4 had no
    // course-override upload at all: the wipe took every hand-picked colour
    // and there was nothing on the server to restore it from.
    //
    // Removed rather than disarmed, because it had nothing to fix either.
    // customColorHex only ever holds an explicit choice (the colour picker, or
    // Settings > randomise all); the courses the hash decides are the ones
    // with no pin, and those recompute on their own in
    // buildCourseColorAssignments.

    private var hasLoaded = false
    private var loadJob: Job? = null

    fun load() {
        if (hasLoaded) return
        loadNow(fetch = refreshTriggers.onLaunch())
    }

    /**
     * The cache onto the screen, then the school servers if [fetch]: at
     * launch as HomeRefreshPolicy says, and always on a sign-in, which is
     * this page's launch for the account signing in.
     */
    private fun loadNow(fetch: Boolean) {
        hasLoaded = true
        // It pulls from the backend below, whichever way it goes.
        backendPullStarting()

        loadJob = viewModelScope.launch {
            // _skippedDates.value = dataCache.loadSkippedDates()
            _ignoredAssignmentIds.value = dataCache.loadIgnoredAssignments()
            _markedCompletedIds.value = dataCache.loadMarkedCompletedAssignments()
            _ignoredCourseKeys.value = dataCache.loadIgnoredCourses()

            // Load cached data immediately
            val cachedCourses = dataCache.loadCourses()
            val cachedAssignments = dataCache.loadAssignments()
            if (cachedCourses.isNotEmpty() || cachedAssignments.isNotEmpty()) {
                TigerDuckTheme.buildCourseColorMap(cachedCourses)
                updateCoursesAndAssignments(cachedCourses, cachedAssignments)
            }
            _initialLoadComplete.value = true

            // The school servers as HomeRefreshPolicy says; the backend sync
            // that carries other devices' marks runs either way, as it
            // always has.
            if (fetch) fetchRemote() else runCatching { syncAndRepublish() }
        }
    }

    /**
     * The app came back to the foreground. Used to sync only with the
     * backend, so a morning in the background left the morning's
     * assignments on screen until the user pulled.
     */
    fun onAppForeground() {
        if (!hasLoaded || !authService.authState.value) return
        if (refreshTriggers.onForeground()) fetchOnReturn()
    }

    /** Home was shown: on launch, or on coming back to it from another page. */
    fun onPageShown() {
        if (!authService.authState.value) return
        if (refreshTriggers.onShown()) fetchOnReturn()
    }

    /** The page left the screen for another page; see PageLeftEffect. An ask it offered goes with it. */
    fun onPageLeft() {
        refreshTriggers.onLeft()
        reviewPrompt.withdraw()
    }

    /**
     * The page paused with the activity — a background trip, a system prompt
     * over it: the Play rating's moment it offered is withdrawn, so the ask
     * cannot outlive what was on screen. Its next resume may offer one again.
     */
    fun onPagePaused() = reviewPrompt.withdraw()

    /**
     * Home is on screen and the activity resumed: on coming to the page, and
     * on each return from the background with it showing — the page's own
     * ON_RESUME, so a return with another page up is not one. The Play
     * rating's moment (issue #332), if ReviewPrompt agrees. A course opened
     * from Home is the navigation's course detail sheet, a window of its own,
     * so the launch's window-focus check keeps the ask off it. On the
     * launch's first resume, before any load, ReviewPrompt declines.
     * [pageResumed] is the page's own answer, asked again right before the
     * launch, to whether it is still on screen and resumed.
     */
    fun onPageResumed(pageResumed: () -> Boolean) {
        reviewPrompt.calmMoment(pageResumed)
    }

    private fun fetchOnReturn() {
        // Quietly: nobody asked, so no snackbar, and a fetch bound to fail
        // would only mark Moodle as failing on every return.
        if (!networkChecker.isAvailable()) return
        // Ahead of the ON_RESUME that follows, so its syncOnForeground leaves
        // the backend to the pull this fetch starts with.
        backendPullStarting()
        viewModelScope.launch { fetchRemote() }
    }

    /** A pull to refresh: what the schools have now, not an answer kept for sharing. */
    fun refresh() {
        moodleService.expireSharedResults()
        courseService.expireSharedResults()
        requestRefresh(rerun = false)
    }

    private fun requestRefresh(rerun: Boolean) {
        viewModelScope.launch {
            if (!networkChecker.isAvailable()) {
                _noNetworkEvent.tryEmit(Unit)
                // The user just saw an error, not a moment to ask for a rating.
                reviewPrompt.loadFailed()
                return@launch
            }
            if (rerun) {
                fetchFlight.rerun { fetchData(forceRemote = true) }
            } else {
                // A pull: what was just submitted, confirmed ones included.
                moodleService.recheckingSubmissions { fetchRemote() }
            }
        }
    }

    /** The full fetch — backend, NTUST and Moodle — or the one already running. */
    private suspend fun fetchRemote() = fetchFlight.join { fetchData(forceRemote = true) }

    // When a pull from the backend last started, whatever started it. A full
    // fetch begins with one, so a foreground sync on its heels would only
    // repeat it.
    private var lastBackendPullMs = 0L

    private fun backendPullStarting() {
        lastBackendPullMs = System.currentTimeMillis()
    }

    fun syncOnForeground() {
        val now = System.currentTimeMillis()
        if (now - lastBackendPullMs < 30_000) return
        lastBackendPullMs = now
        viewModelScope.launch {
            if (!networkChecker.isAvailable()) return@launch
            runCatching {
                syncAndRepublish()
            }
        }
    }

    // --- Foreground revision polling ---

    private val revisionPoller = RevisionPoller(
        scope = viewModelScope,
        poll = ::pollRevision,
        relogin = authService::attemptRelogin,
        now = SystemClock::elapsedRealtime,
    )

    // The first start is the launch, whose own sync has just pulled.
    private var revisionPollingStarted = false

    init {
        // After the poller above, which the edits reach.
        viewModelScope.launch {
            localEdits.edits.collect { revisionPoller.noteActivity() }
        }
    }

    /** The app is in the foreground again; see [RevisionPoller.start]. */
    fun startRevisionPolling() {
        revisionPoller.start(pollNow = revisionPollingStarted)
        revisionPollingStarted = true
    }

    fun stopRevisionPolling() = revisionPoller.stop()

    // cloudSyncEnabled already reads false on fdroid at its source
    // (AppPreferences.cloudSyncEnabled), so no separate flavor check is
    // needed here.
    private suspend fun pollRevision(): RevisionPollOutcome = pollRevisionOnce(
        syncEnabled = { prefs.cloudSyncEnabled },
        online = networkChecker::isAvailable,
        signIn = authTokenManager::signInIfSignedOut,
        fetchRevision = syncApiClient::fetchRevision,
        fetchChanges = syncApiClient::fetchChanges,
        lastKnownRevision = { syncState.lastKnownRevision },
        setLastKnownRevision = { syncState.lastKnownRevision = it },
        signInGeneration = { authTokenManager.signInGeneration },
        fullSyncsInFlight = backendPullsInFlight::get,
        retryUnsent = backendSync::sendWhatWaits,
        fullSync = ::syncAndRepublish,
    ).also { outcome ->
        // Reached the server with nothing new: a re-enable check that failed
        // runs again here, as after a full sync (which a change starts).
        if (outcome == RevisionPollOutcome.UNCHANGED) reenableCheck.runIfOwed()
    }

    /**
     * Pull from the backend, then re-publish whatever landed in the cache.
     *
     * The re-read is not redundant: the sync writes merged courses and
     * deletions straight to disk, so the in-memory lists are stale by the
     * time it returns. This is the "catch up quietly" path — no Moodle
     * refresh, no spinner — used by the foreground sync and the poller.
     */
    /**
     * Course numbers hidden in the term Home is showing.
     *
     * The deleted-course store is keyed by semester now, so a bare
     * `courseNo in store` check would miss every scoped entry and quietly
     * un-hide courses the user deleted. Home is always the current term.
     */
    private suspend fun hiddenNow(): Set<String> = CourseTombstoneKeys.hiddenIn(
        courseService.currentSemesterCode(),
        dataCache.loadDeletedCourseNos(),
    )

    private suspend fun pullFromBackend() {
        backendPullsInFlight.incrementAndGet()
        try {
            backendSync.pull(syncState)
        } finally {
            backendPullsInFlight.decrementAndGet()
        }
    }

    private suspend fun syncAndRepublish() {
        pullFromBackend()
        val courses = dataCache.loadCourses().filterNot { it.courseNo in hiddenNow() }
        val assignments = dataCache.loadAssignments()
        TigerDuckTheme.buildCourseColorMap(courses)
        updateCoursesAndAssignments(courses, assignments)
        dataCache.notifyBackgroundSyncComplete()
    }

    private suspend fun fetchData(forceRemote: Boolean) {
        // Whatever asked for it, a pull included — see RefreshPolicy.minInterval.
        refreshTriggers.fetchStarted()
        if (forceRemote) backendPullStarting()
        _isLoading.value = true
        try {
            var courses = dataCache.loadCourses()
            var assignments = dataCache.loadAssignments()

            if (forceRemote) {
                // Moodle-direct for the assignment/course list (proven,
                // correct semester filtering). Backend handles override
                // sync only (done/ignored marks across devices).
                pullFromBackend()
                // Re-read after backend sync so server-merged courses and
                // deletions are reflected even if the Moodle fetch below fails.
                courses = dataCache.loadCourses().filterNot { it.courseNo in hiddenNow() }

                val studentId = authService.storedStudentId
                val password = authService.storedPassword
                if (!studentId.isNullOrBlank() && !password.isNullOrBlank()) {
                    val (remote, remoteAssignments) =
                        fetchCoursesAndAssignments(studentId, password)

                    if (remote != null && remote.courses.isNotEmpty()) {
                        // Re-read cache so a concurrent color change isn't erased,
                        // and so manually-added courses survive the refresh.
                        courses = HomeCourseMerge.mergeRemote(
                            remote = remote.courses,
                            cached = dataCache.loadCourses(),
                            names = dataCache.loadCourseCustomNames(),
                            locale = AppLanguageManager.resolvedCourseApiLanguage(prefs.appLanguage),
                            deletedNos = hiddenNow(),
                            onlyGains = remote.onlyGains,
                        )
                        dataCache.saveCourses(courses)
                        // The class table loaded: a day of use towards the
                        // Play rating ask (issue #332). A source that failed
                        // is a status dot, which ReviewPrompt reads.
                        reviewPrompt.classTableLoaded()
                        widgetUpdater.requestUpdate()
                    }

                    // Null when Moodle failed or was not asked; an empty list
                    // is its answer and replaces the cache like any other.
                    // See CourseRosterMerge.assignmentsToStore. What is shown
                    // and uploaded is the list as written, which the write
                    // reconciles against newer answers; nothing when the write
                    // was refused.
                    val written = remoteAssignments?.let {
                        moodleService.storeAssignments(it.assignments, it.answer)
                    }
                    if (written != null) {
                        assignments = written
                        try {
                            pushApiClient.uploadAssignments(written)
                        } catch (e: CancellationException) {
                            // A sign-out cancels this fetch: taken for a failed upload, the
                            // fetch would go on to show the departing account's lists again.
                            throw e
                        } catch (e: Exception) {
                            prefs.setLastSyncSource(SyncSource.LOCAL)
                            Log.w("HomeViewModel", "uploadAssignments failed (non-fatal): ${e.logSummary()}")
                        }
                    }
                }
            }

            TigerDuckTheme.buildCourseColorMap(courses)
            updateCoursesAndAssignments(courses, assignments)
            if (forceRemote) {
                dataCache.notifyBackgroundSyncComplete()
                if (authService.authState.value) {
                    _syncCompleteEvent.tryEmit(Unit)
                }
            }
        } finally {
            _isLoading.value = false
        }
    }

    /** A Moodle round's assignments as the cache should hold them, with the round that gave them; see [MoodleService.storeAssignments]. */
    private class AssignmentsToStore(val assignments: List<Assignment>, val answer: MoodleService.AssignmentsAnswer)

    /** A fetch's courses of the term, and whether the term only gains them; see [HomeCourseMerge.mergeRemote]. */
    private class RemoteCourses(val courses: List<Course>, val onlyGains: Boolean)

    /**
     * One network round of the two enrolment sources (NTUST course-selection
     * portal + Moodle enrolled-courses) run in parallel, then per-course
     * detail lookups run in parallel while assignments are fetched alongside.
     * The term's courses are course selection's, else the transcript's on
     * disk added to the cached ones (see [TranscriptTerms.termFrom]); Moodle
     * gives their names and links, and the assignments' courses.
     * Returns (null, null) for any piece that failed so the caller can fall
     * back to cached data cleanly, null courses when no source named any, and
     * null assignments when there was no course to ask Moodle about.
     */
    private suspend fun fetchCoursesAndAssignments(
        studentId: String,
        password: String,
    ): Pair<RemoteCourses?, AssignmentsToStore?> = coroutineScope {
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
        // Before the fetch: a sign-out while it runs drops its roster.
        val generation = dataCache.writeGeneration()

        // Moodle webservice calls auth with a long-lived wstoken, so they
        // don't need the NTUST SSO cookies that ensureAuthenticated refreshes.
        // Let them run concurrently with the SSO + course-selection scrape.
        val moodleEnrolledDef = async {
            try {
                if (BuildConfig.DEBUG) ServerFailureSimulator.check(ServerKind.MOODLE)
                moodleService.fetchEnrolledCourses()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ServerStatusTracker.moodleFailed(e)
                Log.e("HomeViewModel", "Failed to fetch Moodle enrolled courses: ${e.logSummary()}")
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ServerStatusTracker.set(ServerStatus.FAILED, ServerKind.COURSE_SELECTION)
                Log.e("HomeViewModel", "Failed to fetch enrolled course numbers: ${e.logSummary()}")
                null
            }
        }

        val courseNos = courseNosDef.await()
        // Before anything overwrites the cache: a course that was in the
        // portal roster and is not in this answer was dropped in 加退選, and
        // the backend will keep serving it until someone deletes it by hand.
        courseNos?.let { dataCache.recordSelectionRoster(semester, it, generation) }
        val moodleEnrolled = moodleEnrolledDef.await()
        val moodleForSemester = CourseRosterMerge.moodleCoursesFor(semester, moodleEnrolled)
        val moodleByNo = moodleForSemester.associateBy { it.courseNo }
        // Moodle adds no course to the term, but its courses are still whose
        // assignments are asked for when course selection gave nothing.
        val rosterCourseNos = CourseRosterMerge.assignmentCourseNos(courseNos, moodleForSemester).toSet()
        val cached = dataCache.loadCourses(semester)
        // Course selection's answer, else the transcript's classes added to
        // the term, else the term stays as it is (tigerduck-app#890). The
        // transcript on disk: Home never signs in to the score system.
        val term = TranscriptTerms.termFrom(courseNos, dataCache.loadScoreReport(studentId)?.report, semester, cached)

        val coursesDef = async {
            term ?: return@async null

            // A course whose query fails, or brings no rows, keeps its row in
            // the term, where Moodle's stand-in would take its times and room
            // off this phone and, uploaded, off every other device (#417).
            val cachedByNo = cached.associateBy { it.courseNo }
            val looked = term.courseNos.map { courseNo ->
                async {
                    courseService.lookupOrFallback(semester, courseNo, moodleByNo[courseNo], kept = cachedByNo[courseNo])
                }
            }.awaitAll().mapNotNull { it.course }
            // A class the transcript adds joins with class times; course selection's all do.
            RemoteCourses(
                TranscriptTerms.drawnCourses(looked, fromSelection = term.roster != null, cached = cachedByNo.keys),
                onlyGains = term.onlyGains,
            )
        }

        val assignmentsDef = async {
            if (moodleEnrolled == null) return@async null
            try {
                val answer = moodleService.fetchAssignments(
                    enrolledCourses = moodleEnrolled,
                    rosterCourseNos = rosterCourseNos
                )
                val cached = dataCache.loadAssignments()
                ServerStatusTracker.set(ServerStatus.OK, ServerKind.MOODLE)
                answer?.let { AssignmentsToStore(CourseRosterMerge.assignmentsToStore(it.assignments, cached).orEmpty(), it) }
            } catch (e: CancellationException) {
                // A sign-out cancels the fetch; marking the servers failed
                // here would land after the tracker was reset for the next
                // account.
                throw e
            } catch (e: Exception) {
                ServerStatusTracker.moodleFailed(e)
                Log.e("HomeViewModel", "Failed to fetch assignments: ${e.logSummary()}")
                null
            }
        }

        coursesDef.await() to assignmentsDef.await()
    }

    private fun updateCoursesAndAssignments(courses: List<Course>, assignments: List<Assignment>) {
        _allCourses.value = courses
        _allAssignments.value = assignments.sortedBy { it.studentDueDate }

        if (prefs.notifyAssignments) {
            rescheduleAssignmentNotifications(assignments)
        }

        // Refresh the Live Update (Android analogue of the iOS dynamic island)
        liveActivityManager.refresh()
    }

    /** Re-arm every assignment alarm against the current ignored and marked-done sets. */
    private fun rescheduleAssignmentNotifications(assignments: List<Assignment>) {
        notificationScheduler.scheduleAll(
            OutstandingAssignments.of(
                assignments,
                ignoredIds = ignoredIncludingCourses(assignments),
                markedCompletedIds = _markedCompletedIds.value,
            ),
            prefs.notifyAssignmentOffsets,
        )
    }

    /** The ignored ids of [all], counting those ignored with their course. */
    private fun ignoredIncludingCourses(
        all: List<Assignment>,
        ignored: Set<String> = _ignoredAssignmentIds.value,
        courseKeys: Set<String> = _ignoredCourseKeys.value,
    ): Set<String> =
        ignored + CourseIgnores.assignmentIds(all, courseKeys, courseService.currentSemesterCode())

    fun hasUnfinishedAssignment(courseNo: String): Boolean =
        HomeAssignmentFilters.anyUnfinishedFor(
            all = _allAssignments.value,
            courseNo = courseNo,
            ignoredIds = ignoredIncludingCourses(_allAssignments.value),
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
            !HomeAssignmentFilters.hasIgnored(
                _allAssignments.value, ignoredIncludingCourses(_allAssignments.value),
            )
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
     * this device's cache, [UnsentMarks] keeps it, and the next sync keeps
     * this device's value for it and sends it again, which is a better
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
        markTaps++
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
        revisionPoller.noteActivity()
        viewModelScope.launch {
            try {
                // Kept on disk until it lands; a failure is logged there.
                unsentMarks.send(id, status)
            } finally {
                // Always release the in-flight lock: from here on a PATCH
                // that failed is protected by UnsentMarks, which lets the id
                // go once the server has it, so it is not pinned for ever.
                pendingOverrides.remove(id)
            }
        }
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

    /**
     * A shortcut card for [feature], in the first section that holds them,
     * or in a new Quick widgets section titled [quickWidgetsTitle] when Home
     * has none. The title is passed in because the view model has no
     * resources; HomeScreen resolves it as it does for [addSection].
     */
    fun addQuickFeature(feature: AppFeature, quickWidgetsTitle: String) {
        val added = HomeSectionLayout.addQuickFeature(
            sections = _sections.value,
            feature = feature,
            sectionId = java.util.UUID.randomUUID().toString(),
            widgetId = java.util.UUID.randomUUID().toString(),
            quickWidgetsTitle = quickWidgetsTitle,
        )
        if (added !== _sections.value) persistSections(added)
    }

    fun removeWidget(sectionId: String, widgetId: String) {
        val removed = HomeSectionLayout.removeWidget(_sections.value, sectionId, widgetId)
        if (removed !== _sections.value) persistSections(removed)
    }

    /** One step of a shortcut drag; see [moveSections] for why a no-op must not write. */
    fun moveWidget(sectionId: String, widgetId: String, toIndex: Int) {
        val moved = HomeSectionLayout.moveWidget(_sections.value, sectionId, widgetId, toIndex)
        if (moved !== _sections.value) persistSections(moved)
    }

    private fun persistSections(sections: List<HomeSection>) {
        _sections.value = sections
        prefs.homeSections = sections
    }
}

/**
 * Home's take-up of marks a re-enable answer wrote (`DataCache.adoptAssignmentMarks`):
 * [read] the files and [apply] them, unless a swipe was made meanwhile
 * ([tapsMade] moved), which the read may predate and must not undo, or the
 * account signed out, whose marks they were.
 */
internal suspend fun reloadAdoptedMarks(
    read: suspend () -> Pair<Set<String>, Set<String>>,
    tapsMade: () -> Int,
    stillSignedIn: () -> Boolean,
    apply: (ignored: Set<String>, completed: Set<String>) -> Unit,
) {
    val tapsBefore = tapsMade()
    val (ignored, completed) = read()
    if (tapsMade() != tapsBefore || !stillSignedIn()) return
    apply(ignored, completed)
}

