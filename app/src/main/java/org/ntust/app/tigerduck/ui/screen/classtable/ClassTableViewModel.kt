package org.ntust.app.tigerduck.ui.screen.classtable

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.data.CourseRosterMerge
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.CourseColorStore
import org.ntust.app.tigerduck.data.CourseIgnoreSync
import org.ntust.app.tigerduck.data.CourseIgnores
import org.ntust.app.tigerduck.data.CourseTombstoneKeys
import org.ntust.app.tigerduck.push.CourseUpload
import org.ntust.app.tigerduck.data.OutstandingAssignments
import org.ntust.app.tigerduck.data.RecordedSelection
import org.ntust.app.tigerduck.data.RefreshPolicy
import org.ntust.app.tigerduck.data.RefreshTriggers
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.sharing.ClassTableShare
import org.ntust.app.tigerduck.data.sharing.ImportedClassTableChange
import org.ntust.app.tigerduck.data.sharing.ImportedClassTableStore
import org.ntust.app.tigerduck.data.sharing.ImportedClassTables
import org.ntust.app.tigerduck.data.sharing.ImportedCourseLookup
import org.ntust.app.tigerduck.debug.DebugFixtureStore
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.model.ScoreReport
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.data.model.TimetablePeriod
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.notification.SyncSource
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.network.MoodleCourseIds
import org.ntust.app.tigerduck.network.MoodleService
import org.ntust.app.tigerduck.network.NetworkChecker
import org.ntust.app.tigerduck.network.NtustScoreService
import org.ntust.app.tigerduck.network.SemesterCatalog
import org.ntust.app.tigerduck.network.model.MoodleEnrolledCourse
import org.ntust.app.tigerduck.review.ReviewPrompt
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.ui.component.ServerFailureSimulator
import org.ntust.app.tigerduck.ui.component.ServerKind
import org.ntust.app.tigerduck.ui.component.ServerStatusTracker
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import org.ntust.app.tigerduck.util.SingleFlight
import org.ntust.app.tigerduck.util.logSummary
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes

/**
 * When the class table fetches on its own — see [RefreshPolicy]. The default:
 * on launch only. A timetable changes a few times a term, during 加退選,
 * and a pull covers those.
 */
internal val ClassTableRefreshPolicy = RefreshPolicy()

/**
 * How often a visit to the page looks a term's courses without a room up
 * again (tigerduck-app-android#264). CourseService keeps a lookup this long,
 * so asking sooner would be answered from memory for a course it knows — and
 * one it does not know, it asks the school about each time.
 */
internal val MissingRoomLookupInterval = 30.minutes

@HiltViewModel
class ClassTableViewModel @Inject constructor(
    private val networkChecker: NetworkChecker,
    private val authService: AuthService,
    internal val courseService: CourseService,
    private val moodleService: MoodleService,
    private val scoreService: NtustScoreService,
    private val dataCache: DataCache,
    private val courseColorStore: CourseColorStore,
    private val appPreferences: AppPreferences,
    private val semesterCatalog: SemesterCatalog,
    private val widgetUpdater: org.ntust.app.tigerduck.widget.WidgetUpdater,
    private val pushApiClient: org.ntust.app.tigerduck.push.PushApiClient,
    private val academicCalendar: org.ntust.app.tigerduck.academic.AcademicCalendarStore,
    private val debugFixtures: DebugFixtureStore,
    private val notificationScheduler: org.ntust.app.tigerduck.notification.AssignmentNotificationScheduler,
    private val liveActivityManager: org.ntust.app.tigerduck.liveactivity.LiveActivityManager,
    private val courseIgnoreSync: CourseIgnoreSync,
    private val localEdits: org.ntust.app.tigerduck.ui.screen.home.LocalEdits,
    private val importedClassTableStore: ImportedClassTableStore,
    private val importedCourseLookup: ImportedCourseLookup,
    private val pendingCourseAdds: org.ntust.app.tigerduck.data.PendingCourseAdds,
    private val pendingCourseDeletes: org.ntust.app.tigerduck.data.PendingCourseDeletes,
    private val pendingCourseOverrides: org.ntust.app.tigerduck.data.PendingCourseOverrides,
    private val reviewPrompt: ReviewPrompt,
    private val courseChangeStore: org.ntust.app.tigerduck.coursechanges.CourseChangeStore,
    private val authTokenManager: AuthTokenManager,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {


    private val _courses = MutableStateFlow<List<Course>>(emptyList())
    val courses: StateFlow<List<Course>> = _courses

    /**
     * The term in session, cached separately, and only while the picker is
     * showing some *other* term.
     *
     * "What do I have today" does not change because the student opened last
     * year's timetable to look something up, so the carousel has to keep
     * answering it. Reading today off the selected term instead would surface
     * a class they took two years ago as though it were happening now,
     * progress bar and all.
     *
     * Empty while the picker is on the live term — [courses] already is that
     * list, and mirroring it would only go stale the moment a course is
     * renamed or deleted. [liveCourses] picks between the two.
     */
    private val _liveSemesterCourses = MutableStateFlow<List<Course>>(emptyList())
    val liveSemesterCourses: StateFlow<List<Course>> = _liveSemesterCourses

    // The term [_liveSemesterCourses] was loaded for. The live term is read
    // off the calendar each time, so it can roll over while that list still
    // holds the previous term's roster — see liveCoursesTerm.
    private var liveSemesterCoursesTerm: String? = null

    private val _assignments = MutableStateFlow<List<Assignment>>(emptyList())

    // Home owns these two sets and writes them; the class table reads them
    // back from the cache, so its badges leave out the same assignments.
    private val _ignoredAssignmentIds = MutableStateFlow<Set<String>>(emptySet())
    private val _markedCompletedIds = MutableStateFlow<Set<String>>(emptySet())

    // The ignored courses; see CourseIgnores. Edited here first, so a menu
    // opened again at once already reads the new value.
    private val ignoredCourses = ClassTableIgnoredCourses(dataCache.assignmentMarksVersion)

    // A term's reset as far as files and the backend go; see resetCourses.
    private val termReset = ClassTableTermReset(dataCache)

    // The other terms' caches, filled after the first fetch; see warmTerms.
    private val termWarm = ClassTableTermWarm(
        terms = { semesterCatalog.availableSemesters() },
        isCached = { dataCache.loadCourses(it).isNotEmpty() },
        mayFetch = { authService.authState.value && !debugFixtures.demoMode && networkChecker.isAvailable() },
        shownTerm = { _currentSemester.value },
    )
    private var warmJob: Job? = null

    // One term's lookups and their fold onto its list; see fetchTermCourses.
    private val termFetch = ClassTableTermFetch { semester, courseNo, moodle, kept, onFailed ->
        courseService.lookupOrFallback(semester, courseNo, moodle, kept, onFailed)
    }

    // This screen's tombstone edits whose write has not landed; see ClassTableTombstoneEdits.
    private val tombstoneEdits = ClassTableTombstoneEdits()

    // Per term, how many in-place edits of its list have been made — an add,
    // a delete, a rename, a colour, a room found — counted at the tap, ahead
    // of their writes; see ClassTableTermFetch.foldLatest.
    private val termEdits = HashMap<String, Int>()

    private fun noteTermEdit(semester: String) {
        termEdits[semester] = (termEdits[semester] ?: 0) + 1
    }

    // By tap, the term and the courses each in-place edit of the list on screen added, took off or
    // changed, until its save of the list lands: the term's cache lacks them meanwhile, and a fetch
    // takes those from the screen; see ClassTableTermFetch.foldLatest.
    private val unsavedEdits = HashMap<Long, Pair<String, Set<String>>>()
    private var nextUnsavedEdit = 0L

    private fun unsavedEditsIn(semester: String): Set<String> =
        unsavedEdits.values.filter { it.first == semester }.flatMapTo(mutableSetOf()) { it.second }

    /**
     * Puts [updated], an in-place edit of [semester]'s list, on screen: the edit counted, and the
     * courses it changed noted unsaved until [saveEdit] is handed the id returned.
     */
    private fun showEdit(updated: List<Course>, semester: String): Long {
        val edited = ClassTableCourseMerge.editedNos(_courses.value, updated)
        _courses.value = updated
        noteTermEdit(semester)
        val edit = nextUnsavedEdit++
        unsavedEdits[edit] = semester to edited
        return edit
    }

    /** Saves [updated], the list [edit] left, and lets the edit go once the save is done. */
    private suspend fun saveEdit(updated: List<Course>, semester: String, edit: Long) {
        try {
            dataCache.saveCourses(updated, semester)
        } finally {
            unsavedEdits.remove(edit)
        }
    }

    // When each term's courses without a room were last looked up again, on
    // the monotonic clock; see lookUpMissingRooms.
    private val roomLookupsAtMs = HashMap<String, Long>()

    /** What the course detail lists: see [OutstandingAssignments]. */
    val outstandingAssignments: StateFlow<List<Assignment>> = combine(
        _assignments,
        _ignoredAssignmentIds,
        _markedCompletedIds,
        ignoredCourses.keys,
    ) { all, ignored, marked, courseKeys ->
        OutstandingAssignments.of(all, ignoredIncludingCourses(all, ignored, courseKeys), marked)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The courses whose cells and cards carry the "has assignments" badge. */
    val courseNosWithAssignments: StateFlow<Set<String>> = combine(
        _assignments,
        _ignoredAssignmentIds,
        _markedCompletedIds,
        ignoredCourses.keys,
    ) { all, ignored, marked, courseKeys ->
        OutstandingAssignments.courseNos(all, ignoredIncludingCourses(all, ignored, courseKeys), marked)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    /**
     * Whether the first load of the account's class table is done: its cache
     * read and, when it fetches, that fetch. Until then an empty term is one
     * not read yet; see [ShownClassTable.showsNoCourses].
     */
    private val _hasSettled = MutableStateFlow(false)
    val hasSettled: StateFlow<Boolean> = _hasSettled

    val isLoggedIn: StateFlow<Boolean> = authService.authState

    /** For the sync dot's "Last synced" row; see [AppPreferences.schoolDataSyncedAtMs]. */
    val schoolDataSyncedAtMs: StateFlow<Long> = appPreferences.schoolDataSyncedAtMs

    /**
     * Drives the grid's row list; see [AppPreferences.alwaysShowAllPeriodsFlow].
     *
     * Declared above `init` with the other stored properties — see
     * ClassTableViewModelInitOrderTest for why that placement is load-bearing.
     */
    val alwaysShowAllPeriods: StateFlow<Boolean> = appPreferences.alwaysShowAllPeriodsFlow

    /** Drives the room hint in each cell's corner; see [CourseRoomHint]. */
    val showClassroomInClassTable: StateFlow<Boolean> =
        appPreferences.showClassroomInClassTableFlow

    /** Whether today's courses show above the grid; see [AppPreferences.pinTodayCoursesOnClassTable]. */
    val pinTodayCoursesOnClassTable: StateFlow<Boolean> =
        appPreferences.pinTodayCoursesOnClassTableFlow

    /** Whether a required course's cell is outlined; see [AppPreferences.outlineRequiredCourses]. */
    val outlineRequiredCourses: StateFlow<Boolean> =
        appPreferences.outlineRequiredCoursesFlow

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

    /**
     * The id map as it changes, so the course detail finds a course's Moodle id the moment a
     * sync brings it (see [moodleCourseIdFor]), as iOS retries after the first sync.
     */
    val moodleCourseIdMap: StateFlow<Map<String, Int>> = _moodleCourseIdByIdnumber

    // Per-locale custom course names: courseNo → locale ("zh"/"en") → display name.
    // Loaded from DataCache.courseCustomNames on init; written on every rename.
    // The resolved value for the current locale is stamped onto
    // Course.customCourseName when building the in-memory course list.
    private var courseCustomNames: CustomNameMap = emptyMap()

    /** Current course-API locale ("zh" or "en"), derived from the user's app language. */
    private val currentCourseLocale: String
        get() = AppLanguageManager.resolvedCourseApiLanguage(appPreferences.appLanguage)

    private fun resolveCustomNames(courses: List<Course>): List<Course> =
        CourseNameOverrides.resolve(courses, courseCustomNames, currentCourseLocale)

    private val _selectedWeekday = MutableStateFlow<Int?>(null)
    private val _selectedPeriodId = MutableStateFlow<String?>(null)

    // The block of periods the detail was opened on, for its time: a course
    // meeting twice a day shows the times of the one tapped, not the day's.
    private val _selectedPeriods = MutableStateFlow<List<String>?>(null)

    /** The day the detail was opened for, whose course changes it shows; null shows none. */
    private val _selectedDate = MutableStateFlow<java.time.LocalDate?>(null)

    /** The class the detail was opened for, as the Current class card names it; null merges its block. */
    private val _selectedSlot = MutableStateFlow<org.ntust.app.tigerduck.coursechanges.ClassSlot?>(null)

    // The term the selected course belongs to. Not always the picker's: the
    // today carousel shows the live term's courses whatever term the grid
    // is on, and a course added by hand is looked up in Moodle by
    // `<term><courseNo>` — see moodleCourseIdFor.
    private var selectedCourseSemester: String? = null

    // Whether the selected course is someone else's; see ClassTableSelection.isSomeoneElses. Set
    // with the selection, which a switch of class tables clears.
    private var selectedIsSomeoneElses = false

    /** Whether the selected course is of someone else's class table, which its detail shows read-only. */
    val selectedCourseIsSomeoneElses: Boolean get() = selectedIsSomeoneElses

    // Stored pick, else the newest published term, else the pinned term.
    // The catalogue can land after construction on a cold launch, so
    // followNewestSemesterIfUnpicked re-applies the rule once it does.
    private val _currentSemester = MutableStateFlow(
        semesterCatalog.selectedSemester(appPreferences.classTableSelectedSemester)
    )
    val currentSemester: StateFlow<String> = _currentSemester

    /**
     * The term [_courses] holds, set with the list by [showCourses] whenever
     * another term's replaces it. A reader cannot take it from the picker:
     * [setSemester] moves the picker first and swaps the list once the term's
     * cache is read, and a fetch of the newly picked term landing in between
     * would fold onto the term just left, and save it as the new one.
     */
    private var coursesSemester: String = _currentSemester.value

    /** Puts [courses], [semester]'s list, on screen: the tag and the list together. */
    private fun showCourses(courses: List<Course>, semester: String) {
        coursesSemester = semester
        _courses.value = courses
    }

    /**
     * Terms the picker offers, newest first, as published by NTUST — see
     * [SemesterCatalog]. A `StateFlow` rather than a computed getter so a term
     * the school publishes ahead of the month heuristic (115-1 opened weeks
     * before the heuristic rolled off 114-2) becomes selectable in the same
     * session the catalogue lands.
     */
    private val _availableSemesters = MutableStateFlow(semesterOptions(_currentSemester.value))
    val availableSemesters: StateFlow<List<String>> = _availableSemesters

    private val _currentDayTime = MutableStateFlow(currentDayTime())
    val currentMinute: StateFlow<Int> = _currentDayTime
        .map { it.minuteOfDay }
        .stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            currentDayTime().minuteOfDay
        )

    /** The courses of the term on screen whose assignments are all ignored. */
    val courseNosIgnoringAssignments: StateFlow<Set<String>> = combine(
        _currentSemester,
        ignoredCourses.keys,
    ) { semester, keys ->
        CourseIgnores.courseNosIn(semester, keys)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /**
     * Whether the cells offer to ignore a course's assignments: only for the
     * term in session, the one assignments are fetched for. Rechecked each
     * minute, in case the term rolls over while the table is open.
     */
    val canIgnoreAssignments: StateFlow<Boolean> = combine(
        _currentSemester,
        _currentDayTime,
    ) { semester, _ ->
        semester == courseService.currentSemesterCode()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // One-shot UI events. These live above `init` with the rest of the
    // state for the same reason every other field does: init's collectors
    // run synchronously during construction under Main.immediate, so a
    // field declared below it is still null when they reach it.
    private val _tripleConflictEvent = MutableSharedFlow<TripleConflictError>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val tripleConflictEvent: SharedFlow<TripleConflictError> = _tripleConflictEvent.asSharedFlow()

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

    private val _resetFailedEvent = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** A term whose reset the backend could not take, and which was left as it was. */
    val resetFailedEvent: SharedFlow<String> = _resetFailedEvent.asSharedFlow()

    /** The class tables imported from share codes, and whose the page shows. */
    val importedTables: StateFlow<ImportedClassTables> = importedClassTableStore.tables

    /**
     * Whose class table the page shows and on which term, set together when the store switches,
     * so the page redraws a switch once; see [followShownTable]. The store's copy of the person
     * carries a rename or a new lookup.
     */
    private val _displayed = MutableStateFlow(DisplayedTable(personId = null, semester = null, switches = 0))

    /**
     * What the page draws: the user's own class table, or someone else's, read-only, on the terms
     * they shared. Everything of the user's own (the fetch, the cache, the edits) keeps running on
     * [courses] and [currentSemester] while someone else's shows, and shows again on the way back.
     */
    val shownTable: StateFlow<ShownClassTable> = combine(
        _courses,
        _currentSemester,
        _availableSemesters,
        importedTables,
        _displayed,
    ) { courses, semester, semesters, tables, displayed ->
        shownTableOf(courses, semester, semesters, tables, displayed)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ShownClassTable(null, _currentSemester.value, _availableSemesters.value, emptyList(), 0),
    )

    /**
     * The class table as a week from today, with the school's course changes on its blocks, while
     * it shows the term in session and the changes feed has answered; null for a plain timetable.
     * Rechecked each minute, for the day and the term to roll over. As the iPhone's `showsThisWeek`.
     */
    val thisWeek: StateFlow<ClassTableWeek?> = combine(
        courseChangeStore.changes,
        shownTable,
        _currentDayTime,
    ) { changes, shown, _ ->
        ClassTableWeek(AppClock.localDateTime().toLocalDate(), changes).takeIf {
            ClassTableThisWeek.showsThisWeek(changes.term, shown.semester, courseService.currentSemesterCode())
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _isRefreshingShownPerson = MutableStateFlow(false)

    /** A pull to refresh on someone else's class table, which looks their term up again. */
    val isRefreshingShownPerson: StateFlow<Boolean> = _isRefreshingShownPerson

    private val _importedTableProblems = MutableSharedFlow<ImportedTableProblem>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** A lookup or a save for someone else's class table that did not go through. */
    val importedTableProblems: SharedFlow<ImportedTableProblem> = _importedTableProblems.asSharedFlow()

    // Double taps on the Class Table tab, for whether the next one swaps back or steps on.
    private val tabDoubleTaps = ClassTableTabDoubleTaps(SystemClock::elapsedRealtime)

    /** Bumped after every write of a term's courses ([DataCache.coursesVersion]), for [refreshShareTerms]'s readers. */
    val coursesVersion: StateFlow<Int> get() = dataCache.coursesVersion

    /** The user's terms the share sheet can offer; see [refreshShareTerms]. */
    private val _shareTerms = MutableStateFlow<List<ClassTableShare.Term>>(emptyList())
    val shareTerms: StateFlow<List<ClassTableShare.Term>> = _shareTerms

    private var hasLoaded = false
    private var loadJob: Job? = null

    // Every fetch goes through this, so launch, a sign-in and a pull that
    // land together run the pipeline once.
    private val fetchFlight = SingleFlight(viewModelScope)

    private val refreshTriggers =
        RefreshTriggers(ClassTableRefreshPolicy, SystemClock::elapsedRealtime)

    init {
        viewModelScope.launch { importedClassTableStore.load() }
        viewModelScope.launch {
            // Another page, the tab's menu or a deletion may switch to someone else's class table.
            importedTables.collect(::followShownTable)
        }
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
                val semester = _currentSemester.value
                val fresh = resolveCustomNames(dataCache.loadCourses(semester))
                if (fresh.isNotEmpty()) {
                    showCourses(fresh, semester)
                    TigerDuckTheme.buildCourseColorMap(fresh)
                    // Widget refresh is driven by CourseColorStore itself, so
                    // subscribers don't need to re-trigger it.
                }
            }
        }
        viewModelScope.launch {
            dataCache.backgroundSyncVersion.drop(1).collect {
                if (!authService.authState.value) return@collect
                val account = authService.storedStudentId
                val semester = _currentSemester.value
                val courses = dataCache.loadCourses(semester)
                val assignments = dataCache.loadAssignments()
                // Signed out, or in as someone else, while the files were
                // read: what they held was the departing account's.
                if (!authService.isStillSignedInAs(account)) return@collect
                // Taken as-is, empty included: a sync can now prune the
                // whole term (a reset made elsewhere), and holding on to
                // the old list would keep showing courses that are gone.
                val fresh = resolveCustomNames(courses)
                showCourses(fresh, semester)
                TigerDuckTheme.buildCourseColorMap(fresh)
                _assignments.value = assignments
            }
        }
        viewModelScope.launch {
            dataCache.assignmentMarksVersion.drop(1).collect {
                if (authService.authState.value) loadAssignmentMarks()
            }
        }
        viewModelScope.launch {
            // Clear on logout, refresh on login — a login while the app is
            // open, not the value replayed to this collector. load() fetches
            // for a user signed in at launch; fetching on the replay as well
            // ran the pipeline twice on every cold start.
            var wasAuthed: Boolean? = null
            authService.authState.collect { isAuthed ->
                val signedIn = isAuthed && wasAuthed == false
                wasAuthed = isAuthed
                if (!isAuthed) {
                    // A fetch still running belongs to the account that
                    // left, and so does a load still reading its cache, and
                    // the warm of its other terms.
                    fetchFlight.cancel()
                    loadJob?.cancel()
                    warmJob?.cancel()
                    termWarm.reset()
                    roomLookupsAtMs.clear()
                    _courses.value = emptyList()
                    _shareTerms.value = emptyList()
                    _assignments.value = emptyList()
                    _ignoredAssignmentIds.value = emptySet()
                    _markedCompletedIds.value = emptySet()
                    ignoredCourses.clear()
                    tombstoneEdits.clear()
                    clearSelection()
                    _moodleCourseIdByIdnumber.value = emptyMap()
                    courseCustomNames = emptyMap()
                    hasLoaded = false
                    _hasSettled.value = false
                    TigerDuckTheme.clearCourseColorMap()
                } else if (signedIn) {
                    // As a launch, so the page counts as loaded. Launched, so
                    // a sign-out mid-fetch is not held up by it.
                    loadNow(fetch = true)
                }
            }
        }
        viewModelScope.launch {
            // Language change → re-fetch from the network so course names
            // come back in the new locale. A rerun: a fetch already in flight
            // asked for the old language's names.
            appPreferences.appLanguageChanged.collect {
                courseService.clearInMemoryLookupCache()
                if (authService.authState.value) requestRefresh(rerun = true)
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
                    // An edit of the list like any other, for a commit
                    // reading the term meanwhile; see showEdit.
                    val edit = showEdit(relabeled, semester)
                    saveEdit(relabeled, semester, edit)
                    TigerDuckTheme.buildCourseColorMap(relabeled)
                }
            }
        }
    }

    private fun currentDayTime(): ClassTableSelection.DayTime =
        ClassTableSelection.dayTimeFrom(AppClock.calendar())

    // --- Someone else's class table ---

    /** Someone else's class table is read-only, so every edit of the user's own checks this. */
    private val isShowingSomeoneElse: Boolean get() = _displayed.value.personId != null

    /** Moves the page to whoever the store shows now; see [nextDisplayedTable]. */
    private fun followShownTable(tables: ImportedClassTables) {
        val next = nextDisplayedTable(_displayed.value, tables, shownTable.value.semester) ?: return
        _displayed.value = next
        // A course of the class table just left.
        clearSelection()
    }

    /** Shows [personId]'s class table, or the user's own for null. */
    fun showTable(personId: String?) = importedClassTableStore.show(personId)

    /**
     * A double tap on the Class Table tab while it shows: back to the class table shown before,
     * or within three seconds of the last double tap, the next one. Returns whether it switched.
     */
    fun tabDoubleTapped(): Boolean {
        val before = importedTables.value.shownPersonId
        if (tabDoubleTaps.stepsOn()) importedClassTableStore.showNext() else importedClassTableStore.showPrevious()
        return importedTables.value.shownPersonId != before
    }

    fun renamePerson(personId: String, nickname: String) {
        viewModelScope.launch {
            val change = importedClassTableStore.rename(personId, nickname)
            if (change == ImportedClassTableChange.FAILED) _importedTableProblems.tryEmit(ImportedTableProblem.SAVE_FAILED)
        }
    }

    /** Deleting the shown person shows the user's own class table again, as any switch does. */
    fun deletePerson(personId: String) {
        viewModelScope.launch {
            val change = importedClassTableStore.delete(personId)
            if (change == ImportedClassTableChange.FAILED) _importedTableProblems.tryEmit(ImportedTableProblem.SAVE_FAILED)
        }
    }

    /** Pull to refresh on someone else's class table: looks the term on screen up again. */
    private fun refreshShownPerson() {
        if (_isRefreshingShownPerson.value) return
        val shown = shownTable.value
        val person = shown.person ?: return
        val term = person.terms.firstOrNull { it.semester == shown.semester } ?: return
        _isRefreshingShownPerson.value = true
        viewModelScope.launch {
            try {
                val refreshed = importedCourseLookup.refreshed(term.semester, term.courseNos, term.courses)
                if (refreshed == null) {
                    _importedTableProblems.tryEmit(ImportedTableProblem.LOOKUP_FAILED)
                    return@launch
                }
                val change = importedClassTableStore.updateCourses(
                    person.id, term.semester, term.courseNos, refreshed, person.importedAt,
                )
                if (change == ImportedClassTableChange.FAILED) {
                    _importedTableProblems.tryEmit(ImportedTableProblem.SAVE_FAILED)
                }
            } finally {
                _isRefreshingShownPerson.value = false
            }
        }
    }

    private suspend fun loadAssignmentMarks() {
        val account = authService.storedStudentId
        val readAt = dataCache.assignmentMarksVersion.value
        val ignored = dataCache.loadIgnoredAssignments()
        val marked = dataCache.loadMarkedCompletedAssignments()
        val courseKeys = dataCache.loadIgnoredCourses()
        if (!authService.isStillSignedInAs(account)) return
        _ignoredAssignmentIds.value = ignored
        _markedCompletedIds.value = marked
        ignoredCourses.reloaded(courseKeys, readAt)
    }

    /** [edit]'s write is done, so the cache decides again; it may hold more by now. */
    private fun ignoredCoursesSaved(edit: Long) {
        if (ignoredCourses.saved(edit)) viewModelScope.launch { loadAssignmentMarks() }
    }

    /** The ignored ids of [all], counting those ignored with their course. */
    private fun ignoredIncludingCourses(
        all: List<Assignment>,
        ignored: Set<String>,
        courseKeys: Set<String>,
    ): Set<String> =
        ignored + CourseIgnores.assignmentIds(all, courseKeys, courseService.currentSemesterCode())

    /**
     * The signed-in student's id, for the credits label.
     *
     * A plain read rather than a flow: the credits row only renders inside the
     * logged-in branch, and the id cannot change without a logout taking that
     * branch away and recomposing anyway.
     */
    val studentId: String?
        get() = debugFixtures.studentIdOverride
            ?: authService.storedStudentId

    /**
     * The actual live semester code (not whatever the user picked).
     *
     * Private since the today carousel stopped being gated on "is the picker
     * on the live term" — the only thing that asks now is [liveCourses].
     */
    private val liveSemesterCode: String
        get() = courseService.currentSemesterCode()

    /**
     * Keeps the persisted selection selectable even after it ages out of the
     * catalogue window — a picker whose selected value matches no option
     * renders blank.
     */
    private fun semesterOptions(selected: String): List<String> {
        val options = semesterCatalog.availableSemesters()
        return if (options.contains(selected)) options else options + selected
    }

    /**
     * The catalogue can land after construction on a cold launch, so re-apply
     * the "never picked → newest term" rule once it does.
     *
     * Deliberately does not persist the move: an untouched picker should keep
     * tracking the newest term rather than freezing on whichever one happened
     * to be newest at first launch.
     */
    private suspend fun followNewestSemesterIfUnpicked() {
        if (appPreferences.classTableSelectedSemester != null) return
        val newest = semesterCatalog.availableSemesters().firstOrNull() ?: return
        if (newest == _currentSemester.value) return
        _currentSemester.value = newest
        // A warm of this term in flight is overtaken by the fetch this runs in.
        termWarm.supersede(newest)
        // The grid is still showing the term we just moved off. Swap in the
        // new term's cache immediately rather than leaving the wrong timetable
        // up for the length of the network round-trip.
        val cached = dataCache.loadCourses(newest)
        showCourses(cached, newest)
        TigerDuckTheme.buildCourseColorMap(cached)
        refreshLiveSemesterCourses()
    }

    /** Format semester code for display, e.g. "1142" → "114-2". */
    fun displayLabel(code: String): String = semesterDisplayLabel(code)

    fun setSemester(code: String) {
        val displayed = _displayed.value
        if (displayed.personId != null) {
            // A term picked on someone else's class table is not the user's pick.
            _displayed.value = displayed.copy(semester = code)
            return
        }
        if (code == _currentSemester.value) return
        appPreferences.classTableSelectedSemester = code
        _currentSemester.value = code
        // A warm of this term in flight is overtaken by the fetch below.
        termWarm.supersede(code)
        viewModelScope.launch {
            val cached = resolveCustomNames(dataCache.loadCourses(code))
            showCourses(cached, code)
            TigerDuckTheme.buildCourseColorMap(cached)
            refreshLiveSemesterCourses()
            // A rerun: a fetch in flight is for the term just left.
            fetchFlight.rerun(::fetchData)
        }
    }

    val totalCredits: Float get() = _courses.value.fold(0f) { acc, c -> acc + c.credits }

    /**
     * Reads which of the user's terms a share code can carry, for the share sheet, newest first.
     * The term on screen comes from the table, which a refresh can have made newer than its cache,
     * an emptied term included; the others from the cache, so sharing needs no request.
     */
    fun refreshShareTerms() {
        val shownSemester = _currentSemester.value
        val shown = _courses.value
        val semesters = _availableSemesters.value
        viewModelScope.launch {
            val account = authService.storedStudentId
            val deleted = dataCache.loadDeletedCourseNos()
            val terms = semesters.mapNotNull { semester ->
                val courses = if (semester == shownSemester) shown else dataCache.loadCourses(semester)
                ClassTableShare.shareable(semester, courses) { CourseTombstoneKeys.isHidden(it, semester, deleted) }
                    .takeIf { it.isNotEmpty() }
                    ?.let { ClassTableShare.Term(semester, it) }
            }
            // Read for an account that has since signed out: none of it is the next one's.
            _shareTerms.value = if (authService.isStillSignedInAs(account)) terms else emptyList()
        }
    }

    /** The live term's roster, wherever it currently lives. */
    private val liveCourses: List<Course>
        get() = if (_currentSemester.value == liveSemesterCode) {
            _courses.value
        } else {
            _liveSemesterCourses.value
        }

    /**
     * The term [liveCourses] actually holds, which is what a carousel card
     * belongs to. Taken from whichever list it reads rather than recomputed
     * from the calendar, so a term that rolls over while the view model is
     * alive cannot pair the old roster with the new term.
     */
    private val liveCoursesTerm: String
        get() = if (_currentSemester.value == liveSemesterCode) {
            _currentSemester.value
        } else {
            liveSemesterCoursesTerm ?: liveSemesterCode
        }

    val todayCourses: List<Course>
        get() = ClassTableSelection.todayCourses(
            liveCourses,
            AppClock.localDateTime().toLocalDate(),
            academicCalendar.quietDays(),
        )

    /**
     * Loads the live term's roster when the picker moves off it, and drops it
     * again when the picker comes back — [liveCourses] reads [courses] then,
     * so holding a second copy would only be one more thing to keep in sync.
     */
    private suspend fun refreshLiveSemesterCourses() {
        val live = liveSemesterCode
        _liveSemesterCourses.value = if (_currentSemester.value == live) {
            emptyList()
        } else {
            resolveCustomNames(dataCache.loadCourses(live))
        }
        liveSemesterCoursesTerm = live
    }

    val activeWeekdays: List<Int>
        get() = ClassTableCellLayout.activeWeekdays(_courses.value)

    val activePeriods: List<TimetablePeriod>
        get() = ClassTableCellLayout.activePeriods(
            _courses.value,
            showAllPeriods = appPreferences.alwaysShowAllPeriods,
        )

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
            // Someone else's course shows the name it was looked up with.
            if (selectedIsSomeoneElses) return null
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
            return ClassTableSelection.detailTimeRange(course, weekday, _selectedPeriods.value)
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
            return ClassTableSelection.classroom(
                course, _selectedWeekday.value, _selectedPeriodId.value,
            )
        }

    /** The selected block on the selected day as the school changed it; null without a day or a change. */
    val selectedMeeting: org.ntust.app.tigerduck.coursechanges.ClassSlot?
        get() {
            val course = _selectedCourse.value ?: return null
            return org.ntust.app.tigerduck.coursechanges.CourseChangeSummary.shownMeeting(
                course, _selectedSlot.value, _selectedPeriods.value, _selectedDate.value, courseChangeStore.current(),
            )
        }

    /**
     * 課程異動's rows for the selected course from today on, leaving [shown]'s own change to the
     * cards; none for a detail opened without a day.
     */
    fun selectedChangeRows(
        shown: org.ntust.app.tigerduck.coursechanges.ClassSlot?,
        cancelled: String,
        locale: java.util.Locale,
    ): List<org.ntust.app.tigerduck.coursechanges.CourseChangeRow> {
        val course = _selectedCourse.value ?: return emptyList()
        if (_selectedDate.value == null) return emptyList()
        val changes = courseChangeStore.current()
        val today = AppClock.localDateTime().toLocalDate()
        return org.ntust.app.tigerduck.coursechanges.CourseChangeSummary.rows(
            org.ntust.app.tigerduck.coursechanges.CourseMeetings.upcomingChanges(course, today, changes),
            shown,
            cancelled,
            locale,
        )
    }

    /** The school's course changes, for the today strip to follow. */
    val courseChanges: StateFlow<org.ntust.app.tigerduck.coursechanges.CourseChanges>
        get() = courseChangeStore.changes

    /** A today-strip card for [course] now, by the times the school changed it to. */
    fun todayCard(course: Course): ClassTableSelection.TodayCard =
        ClassTableSelection.todayCard(
            course,
            AppClock.localDateTime().toLocalDate(),
            _currentDayTime.value.minuteOfDay,
            courseChangeStore.current(),
        )

    /** The classes going on now as the school changed them; a cancelled one is not. */
    val ongoingCourses: List<org.ntust.app.tigerduck.coursechanges.ClassSlot>
        get() = ClassTableSelection.ongoingClasses(
            liveCourses,
            AppClock.localDateTime().toLocalDate(),
            _currentDayTime.value.minuteOfDay,
            courseChangeStore.current(),
        )

    // coursesAt / cellRole were removed for the same reason hasAssignment was
    // (below): they read _courses.value, which Compose does not track. The
    // grid took only the view model and the weekday/period lists, so a course
    // added into rows and days already on screen left every one of those
    // inputs equal, Compose skipped the grid, and the new course only showed
    // up after a restart. TimetableGrid is now handed the collected list and
    // asks ClassTableCellLayout directly.

    fun wouldCauseTripleConflict(candidate: Course): TripleConflictError? =
        ClassTableCellLayout.findTripleConflict(_courses.value, candidate)

    // hasAssignment / assignmentsFor were removed: they read _assignments.value
    // outside the snapshot system, so composables calling them never recomposed
    // when the assignments fetch landed. The screen now collects
    // [courseNosWithAssignments] and [outstandingAssignments] instead.

    /**
     * [fromLiveTerm] is for the today carousel, whose courses come from the
     * live term rather than the one the picker is showing.
     */
    /**
     * Whether the selected course is of a term other than the grid's: the live term, picked from
     * the today strip while the grid shows another, whose copy is in [liveSemesterCourses].
     */
    val selectedFromAnotherTerm: Boolean
        get() = selectedCourseSemester.let { it != null && it != _currentSemester.value }

    fun selectCourse(
        course: Course,
        weekday: Int,
        periodId: String,
        fromLiveTerm: Boolean = false,
        // The block a today card stands for; a grid tap opens the cell's, around [periodId].
        periods: List<String>? = null,
        // The day whose course changes the detail shows: the column's in a week from today, a
        // card's own; null shows none.
        date: java.time.LocalDate? = null,
        // The piece the Current class card or a Home card stands for, whose own changes the detail shows.
        slot: org.ntust.app.tigerduck.coursechanges.ClassSlot? = null,
    ) {
        _selectedDate.value = date
        _selectedSlot.value = slot
        _selectedWeekday.value = weekday
        _selectedPeriodId.value = periodId
        _selectedPeriods.value = periods ?: ClassTableSelection.block(
            course,
            weekday,
            periodId,
            ClassTableCellLayout.activePeriods(
                shownTable.value.courses,
                showAllPeriods = appPreferences.alwaysShowAllPeriods,
            ).map { it.id },
        )
        selectedCourseSemester = if (fromLiveTerm) liveCoursesTerm else _currentSemester.value
        selectedIsSomeoneElses = ClassTableSelection.isSomeoneElses(isShowingSomeoneElse, fromLiveTerm)
        _selectedCourse.value = course
    }

    /**
     * The course detail's title for [course], as it is now: a rename made from the detail shows
     * at once, which [selectedCourseFullName], read off the course as it was selected, would not.
     */
    fun detailTitle(course: Course): String =
        ClassTableSelection.detailTitle(
            course,
            // Someone else's course shows the name it was looked up with.
            if (selectedIsSomeoneElses) {
                null
            } else {
                courseService.cachedFullCourseName(selectedCourseSemester ?: _currentSemester.value, course.courseNo)
            },
        )

    /**
     * Numeric Moodle course id for [course], or null when we have no entry
     * for it in the idnumber map yet (e.g. a manual course without a Moodle
     * counterpart, or a cold start before the first sync). The detail
     * popup uses this to decide whether to render the "open in Moodle"
     * affordance.
     *
     * A course added by hand carries no idnumber, so it is looked up as
     * `<term><courseNo>` — the same shape a portal course falls back to in
     * `CourseService.lookupOrFallback`, and a key [MoodleCourseIds.idMap]
     * writes for every code a course answers to, 合開 aliases included. The
     * term is the one the course was selected from, which for the today
     * carousel is the live term even while the grid shows another.
     */
    fun moodleCourseIdFor(course: Course): Int? {
        // Someone else's class table opens nothing of theirs in the user's Moodle.
        if (selectedIsSomeoneElses) return null
        val semester = selectedCourseSemester ?: _currentSemester.value
        val idnumber = course.moodleIdNumber?.takeIf { it.isNotEmpty() }
            ?: "$semester${course.courseNo}"
        return lookupMoodleCourseId(idnumber)
    }

    /** The term the selected course is shown from, for its syllabus; see [moodleCourseIdFor]. */
    val selectedCourseTerm: String
        get() = selectedCourseSemester ?: _currentSemester.value

    /**
     * [course]'s syllabus in [semester] from the school's course query, public as it is; null
     * when it has none, or no course number to ask by. Throws when it cannot be fetched.
     */
    suspend fun syllabusFor(course: Course, semester: String): org.ntust.app.tigerduck.network.CourseSyllabus? {
        if (semester.isBlank() || course.courseNo.isBlank()) return null
        return courseService.syllabus(semester, course.courseNo)
    }

    /** Normalised on both sides: see [MoodleCourseIds.normalizedIdnumber]. */
    private fun lookupMoodleCourseId(idnumber: String): Int? =
        _moodleCourseIdByIdnumber.value[MoodleCourseIds.normalizedIdnumber(idnumber)]

    /** Closes the course detail, whose sheet shows only while a course is selected. */
    fun clearSelection() {
        _selectedCourse.value = null
        selectedCourseSemester = null
        selectedIsSomeoneElses = false
    }

    val existingCourseNos: Set<String>
        get() = _courses.value.map { it.courseNo }.toSet()

    fun addCourse(course: Course): Boolean {
        if (isShowingSomeoneElse) return false
        wouldCauseTripleConflict(course)?.let {
            _tripleConflictEvent.tryEmit(it)
            return false
        }
        val flagged = course.copy(isManual = true)
        val updated = _courses.value + flagged
        val semester = _currentSemester.value
        val edit = showEdit(updated, semester)
        // Ahead of the write below: a fetch folding meanwhile reads the
        // tombstones with this on top, or its filter would drop the course
        // just added back; see ClassTableTombstoneEdits.
        val tombstone = tombstoneEdits.edit { CourseTombstoneKeys.unhide(course.courseNo, semester, it) }
        viewModelScope.launch {
            // First, before the writes below: a removal asked after this add
            // must read as the newer action however long they take. The
            // launch runs here, on this thread, up to its first suspension
            // (Main.immediate), so the stamp is the tap's.
            val reAddedAt = pendingCourseDeletes.stamp()
            try {
                try {
                    // Its hide lifted, and the add noted for every sync under way
                    // or begun before it is sent, whose older read must not prune
                    // it again; see DataCache.addCourseBack.
                    dataCache.addCourseBack(course.courseNo, semester)
                } finally {
                    tombstoneEdits.saved(tombstone)
                }
                saveEdit(updated, semester, edit)
                // A delete of it still queued goes first, or it could land after
                // this add and take the course off the server again; as the
                // iPhone's `discardCourseDeletes` before an add.
                pendingCourseDeletes.forget(semester, course.courseNo, reAddedAt)
                // Kept until it lands: in a term the server already has, no
                // sync would send it again otherwise.
                pendingCourseAdds.send(updated, semester, course.courseNo, addedAt = reAddedAt)
            } finally {
                dataCache.courseAddSent(course.courseNo, semester)
            }
            widgetUpdater.requestUpdate()
        }
        localEdits.noteEdit()
        TigerDuckTheme.buildCourseColorMap(updated)
        return true
    }

    /**
     * Takes back a course [addCourse] added in this Add course session, for
     * the sheet's tap-to-toggle: add a course, tap it again to back out.
     * Without a tombstone of its own: one in `deletedCourseNos` would later
     * hide a real enrolled course with the same [courseNo] from cache and
     * network merges. Otherwise the regular delete, so the backend row the
     * add already uploaded goes too, or the next full sync brings the course
     * back. As the iPhone's `removeUserAddedCourse`, which sends the same
     * `DELETE /sync/courses/{key}`.
     *
     * That DELETE writes a server tombstone (the backend's `delete_course`),
     * which hides the course on every other device. From tigerduck-backend
     * 3.3.6 the backend records it as a hand-added course's, leaves it out of
     * this device's full sync, and lifts it on this device's next roster
     * upload that lists the course: once course selection lists it, the class
     * table shows it, with no hide here in the way, and that upload names it.
     * Adding it back by hand, whose upload forces its key, lifts it at once.
     * The iPhone's take-back works the same way.
     *
     * Only a hand-added course: the sheet gates on its own session, and this
     * is the net under it, so an enrolled course leaves only through
     * [deleteCourse].
     */
    fun removeAddedCourse(courseNo: String) {
        if (isShowingSomeoneElse) return
        val course = _courses.value.firstOrNull { it.courseNo == courseNo } ?: return
        if (!course.isManual) return
        deleteCourse(courseNo, tombstone = false)
    }

    /**
     * Sets the user-supplied display override for [courseNo]. A blank input or
     * one that matches the current default — either the abbreviation-aware
     * [Course.courseName] or the full cached name the rename dialog prefilled
     * — clears the override so the course re-follows the abbreviation toggle.
     */
    fun setCustomCourseName(courseNo: String, newName: String) {
        if (isShowingSomeoneElse) return
        val trimmed = newName.trim()
        val locale = currentCourseLocale
        // The live term's copy for a course renamed from the today strip, while the grid shows
        // another term: it may not be on the grid at all, or the grid's course of that number is
        // another term's.
        val course = ClassTableSelection.renameTarget(
            courseNo, _courses.value, _liveSemesterCourses.value, selectedFromAnotherTerm,
        ) ?: return
        val semester = termOf(course)
        val defaultName = defaultNameFor(course)
        val override = trimmed.takeIf {
            it.isNotEmpty() && it != course.courseName && it != defaultName
        }
        courseCustomNames =
            CourseNameOverrides.withOverride(courseCustomNames, courseNo, locale, override)
        val updated = ClassTableSelection.renamed(_courses.value, courseNo, override)
        val edit = showEdit(updated, _currentSemester.value)
        _liveSemesterCourses.value = ClassTableSelection.renamed(_liveSemesterCourses.value, courseNo, override)
        viewModelScope.launch {
            // Onto the names as they stand, not this screen's copy: a sync may have changed others since.
            dataCache.updateCourseCustomNames { CourseNameOverrides.withOverride(it, courseNo, locale, override) }
            saveEdit(updated, _currentSemester.value, edit)
            widgetUpdater.requestUpdate()
        }
        syncCourseOverride(course, semester, customName = trimmed.ifEmpty { "" }, locale = locale)
        localEdits.noteEdit()
    }

    /**
     * The term a rename's target [course] was read from: the live term's when
     * it is an element of that list, else the grid's
     * ([ClassTableSelection.renameTarget]). By identity, and read before the
     * lists are rebuilt with the rename, which replaces every element. The
     * outbox lets a name go on a 404 once the course has left this term.
     */
    private fun termOf(course: Course): String =
        if (_liveSemesterCourses.value.any { it === course }) liveCoursesTerm else _currentSemester.value

    /** Clears any user override for [courseNo], restoring the default name. */
    fun revertCourseName(courseNo: String) {
        if (isShowingSomeoneElse) return
        val locale = currentCourseLocale
        val course = ClassTableSelection.renameTarget(
            courseNo, _courses.value, _liveSemesterCourses.value, selectedFromAnotherTerm,
        )
        val semester = if (course != null) termOf(course) else null
        courseCustomNames =
            CourseNameOverrides.withOverride(courseCustomNames, courseNo, locale, null)
        val updated = ClassTableSelection.renamed(_courses.value, courseNo, null)
        val edit = showEdit(updated, _currentSemester.value)
        _liveSemesterCourses.value = ClassTableSelection.renamed(_liveSemesterCourses.value, courseNo, null)
        viewModelScope.launch {
            // As setCustomCourseName: onto the names as they stand.
            dataCache.updateCourseCustomNames { CourseNameOverrides.withOverride(it, courseNo, locale, null) }
            saveEdit(updated, _currentSemester.value, edit)
            widgetUpdater.requestUpdate()
        }
        if (course != null && semester != null) syncCourseOverride(course, semester, customName = "", locale = locale)
        localEdits.noteEdit()
    }

    /**
     * Ignores, or stops ignoring, every assignment of [course], from the
     * cell's long-press menu; see [canIgnoreAssignments].
     *
     * On the application scope, and started at once: leaving the table must
     * not cut the write or the PATCH short, and two taps in a row must reach
     * [CourseIgnoreSync] in the order they were made.
     */
    fun toggleIgnoreAssignments(course: Course) {
        if (isShowingSomeoneElse) return
        val semester = _currentSemester.value
        if (semester != liveSemesterCode) return
        val key = CourseIgnores.key(semester, course.courseNo)
        val ignore = key !in ignoredCourses.keys.value
        val edit = ignoredCourses.edit { if (ignore) it + key else it - key }
        // A course without an idnumber is keyed "<term><courseNo>" on the
        // backend, as the colour and name overrides already assume.
        val moodleId = course.moodleIdNumber?.takeIf { it.isNotEmpty() }
            ?: "$semester${course.courseNo}"
        val moodleCourseId = course.moodleNumericCourseId ?: lookupMoodleCourseId(moodleId)
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                // Done with the write, not the PATCH: a slow request must not
                // hold the tap over a change made meanwhile, such as Use Server.
                courseIgnoreSync.setIgnored(semester, course.courseNo, moodleId, moodleCourseId, ignore) {
                    ignoredCoursesSaved(edit)
                }
            } finally {
                ignoredCoursesSaved(edit)
            }
        }
        localEdits.noteEdit()
    }

    /**
     * Removes [courseNo] from the term, here and on the backend. With a
     * [tombstone] the course stays hidden here whatever the server lists; one
     * added by hand that course selection did not list then stays hidden only
     * until it does, as the student has enrolled ([DataCache.hideDeletedCourse]).
     * Without one, for [removeAddedCourse], only the queued DELETE keeps it
     * out.
     */
    fun deleteCourse(courseNo: String, tombstone: Boolean = true) {
        if (isShowingSomeoneElse) return
        val deletedCourse = _courses.value.firstOrNull { it.courseNo == courseNo }
        val moodleId = deletedCourse?.moodleIdNumber
        val updated = _courses.value.filter { it.courseNo != courseNo }
        val semester = _currentSemester.value
        val listEdit = showEdit(updated, semester)
        val ignoreKey = CourseIgnores.key(semester, courseNo)
        val edit = ignoredCourses.edit { it - ignoreKey }
        appScope.launch {
            try {
                courseIgnoreSync.forget { it == ignoreKey }
            } finally {
                ignoredCoursesSaved(edit)
            }
        }
        // Ahead of the write below: a fetch folding meanwhile reads the
        // tombstones with this on top, or the course's own answer would bring
        // it back; see ClassTableTombstoneEdits.
        val tombstoneEdit = if (tombstone) {
            tombstoneEdits.edit { CourseTombstoneKeys.hide(courseNo, semester, it) }
        } else {
            null
        }
        viewModelScope.launch {
            // First, as for an add: a re-add asked before this removal must
            // not let its delete go, however long the writes below take.
            val queuedAt = pendingCourseDeletes.stamp()
            if (tombstoneEdit != null) {
                try {
                    // Kept before the mark, which a course selection answer
                    // lifts only once no DELETE of the course is waiting; see
                    // DataCache.hideDeletedCourse.
                    pendingCourseDeletes.keep(semester, courseNo, queuedAt)
                    dataCache.hideDeletedCourse(courseNo, semester, deletedCourse)
                } finally {
                    tombstoneEdits.saved(tombstoneEdit)
                }
            }
            saveEdit(updated, semester, listEdit)
            // A colour or name of it still waiting to go up goes with it; see
            // PendingCourseOverrides.forget.
            pendingCourseOverrides.forget(listOfNotNull(moodleId))
            widgetUpdater.requestUpdate()
            // Kept and sent again by every full sync until it lands, which
            // keeps the course out of the term meanwhile, and sent after an
            // add of it already under way, which landing later would bring it
            // back: the outbox takes PendingCourseAdds.removing itself, so it
            // is not taken here. See PendingCourseDeletes. One kept above
            // goes as kept: a full sync's retry may have sent it already.
            if (tombstoneEdit != null) {
                pendingCourseDeletes.sendKept(semester, courseNo)
            } else {
                pendingCourseDeletes.send(semester, courseNo, queuedAt)
            }
            runCatching { pushApiClient.uploadCourses(updated, semester) }
                .onFailure { e -> Log.w("ClassTable", "uploadCourses after delete failed: ${e.logSummary()}") }
        }
        localEdits.noteEdit()
        TigerDuckTheme.buildCourseColorMap(updated)
    }

    /**
     * Assigns [newHex] to [courseNo]. Pass null to go back to the palette's
     * colour for it, which is then pinned so the server can be sent it. Any
     * other course pinned to [newHex] moves to a free colour, pinned and
     * synced — see [CourseColorPick].
     */
    fun updateCourseColor(courseNo: String, newHex: String?) {
        if (isShowingSomeoneElse) return
        val pick = CourseColorPick.assign(
            courses = _courses.value,
            courseNo = courseNo,
            hex = newHex,
            shown = TigerDuckTheme::courseColorVibrant,
        )
        val updated = pick.courses
        val edit = showEdit(updated, _currentSemester.value)
        TigerDuckTheme.buildCourseColorMap(updated)
        viewModelScope.launch {
            saveEdit(updated, _currentSemester.value, edit)
            widgetUpdater.requestUpdate()
        }
        // A concrete hex each, a default included: the backend reads a
        // missing one as "unchanged".
        for ((no, hex) in pick.toSync) {
            updated.firstOrNull { it.courseNo == no }?.let { syncCourseOverride(it, _currentSemester.value, colorHex = hex) }
        }
        localEdits.noteEdit()
    }

    /**
     * Sends [course]'s colour or name to the backend, keyed by that course's
     * own term. Kept in [PendingCourseOverrides] until the backend confirms
     * it: sent once, a change made offline stayed on this phone alone
     * (tigerduck-app-android#380). [semester] is the term the course was read
     * from, for the outbox to tell a course that has since left this phone.
     * On the application scope, and started at once: leaving the table must
     * not cut the write short, and two changes in a row must reach the
     * outbox in the order they were made.
     */
    private fun syncCourseOverride(
        course: Course,
        semester: String,
        colorHex: String? = null,
        customName: String? = null,
        locale: String? = null,
    ) {
        val moodleId = course.moodleIdNumber ?: return
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            pendingCourseOverrides.send(
                moodleId,
                colorHex = colorHex,
                customName = customName,
                locale = locale,
                semester = semester,
                courseNo = course.courseNo,
            )
        }
    }

    fun load() {
        if (hasLoaded) return
        loadNow(fetch = refreshTriggers.onLaunch())
    }

    /**
     * The cache onto the screen, then the school servers if [fetch]: at
     * launch as ClassTableRefreshPolicy says, and always on a sign-in, which
     * is this page's launch for the account signing in.
     */
    private fun loadNow(fetch: Boolean) {
        hasLoaded = true
        loadJob = viewModelScope.launch {
            loadAssignmentMarks()
            val semester = _currentSemester.value
            val cached = dataCache.loadCourses(semester)
            val cachedA = dataCache.loadAssignments()
            val cachedMoodleIds = dataCache.loadMoodleCourseIds()
            courseCustomNames = dataCache.loadCourseCustomNames()
                .mapValues { it.value.toMutableMap() }
                .toMutableMap()
            if (cached.isNotEmpty()) {
                showCourses(resolveCustomNames(cached), semester)
                _assignments.value = cachedA
                TigerDuckTheme.buildCourseColorMap(_courses.value)
            }
            if (cachedMoodleIds.isNotEmpty()) {
                // A map written before keys were normalised still has
                // Moodle's "114h" spelling; the lookup asks for "114H".
                _moodleCourseIdByIdnumber.value =
                    cachedMoodleIds.mapKeys { MoodleCourseIds.normalizedIdnumber(it.key) }
            }
            refreshLiveSemesterCourses()
            if (fetch) fetchFlight.join(::fetchData)
            // Not in a finally: a sign-out cancels this job and has just
            // cleared the flag for the next account.
            _hasSettled.value = true
        }
    }

    /** The app came back to the foreground. */
    fun onAppForeground() {
        if (!hasLoaded || !authService.authState.value) return
        if (refreshTriggers.onForeground()) viewModelScope.launch { fetchFlight.join(::fetchData) }
    }

    /** The class table was shown: on launch, or on coming back to it from another page. */
    fun onPageShown() {
        if (!authService.authState.value) return
        if (refreshTriggers.onShown()) {
            viewModelScope.launch { fetchFlight.join(::fetchData) }
        } else {
            // A visit that fetches looks every course up anyway.
            lookUpMissingRooms()
        }
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
     * The class table is on screen and the activity resumed: on coming to
     * the page, and on each return from the background with it showing —
     * the page's own ON_RESUME, so a return with another page up is not
     * one. The Play rating's moment (issue #332), if ReviewPrompt agrees;
     * not over the course detail sheet, which such a return can find open.
     * On the launch's first resume, before any load, ReviewPrompt declines.
     * [pageResumed] is the page's own answer, asked again right before the
     * launch, to whether it is still on screen and resumed.
     */
    fun onPageResumed(pageResumed: () -> Boolean) {
        if (_selectedCourse.value != null) return
        reviewPrompt.calmMoment(pageResumed)
    }

    /**
     * Reset the timetable for the semester on screen.
     *
     * Scoped, because the timetable itself is now per term: an unscoped reset
     * would drop the other terms' rows on the backend and, through
     * `courses_reset_at`, tell every other device to wipe its local overlay
     * for terms the user never asked to touch. The tombstones lifted are this
     * term's plus any legacy bare ones, which hide in every term and so have
     * to go for this term to actually come back.
     */
    fun resetCourses() {
        if (isShowingSomeoneElse) return
        val semester = _currentSemester.value
        // One at a time per term, and latched for the syncs that can run
        // in the middle — see DataCache.beginReset.
        if (!dataCache.beginReset(semester)) return
        // A warm of the term still in its lookups would save and upload the
        // old roster over the wipe; see ClassTableTermWarm.supersede.
        termWarm.supersede(semester)
        localEdits.noteEdit()
        viewModelScope.launch {
            // Released once, whichever way the reset ends: a second reset of
            // the term may already hold the latch by the time this one's
            // outer finally runs.
            var latched = true
            fun release() {
                if (latched) dataCache.endReset(semester)
                latched = false
            }
            try {
                // After any fetch still running, and as a run of its own. A
                // fetch that read the term before the wipe would save its
                // rows back after it, hand-added courses included, and the
                // fetch below would then keep them.
                fetchFlight.runAlone {
                    val wiped = try {
                        wipeTerm(semester)
                    } finally {
                        release()
                    }
                    // Left as it was, and said so, as iOS does; a refetch
                    // over it would change nothing.
                    if (wiped) fetchData() else _resetFailedEvent.tryEmit(semester)
                }
            } finally {
                release()
            }
        }
    }

    /**
     * [resetCourses]' part that forgets the term, on the server and then
     * here; see [ClassTableTermReset]. False, with nothing changed, when the
     * server could not be told.
     */
    private suspend fun wipeTerm(semester: String): Boolean {
        // Read before the wipe takes the term's cache: the colours and names
        // still waiting to go up for its courses go with it below.
        val moodleIds = dataCache.loadCourses(semester).mapNotNull { it.moodleIdNumber }
        // With no course add sent between the DELETE and the wipe; see PendingCourseAdds.removing.
        val held = pendingCourseAdds.removing {
            termReset.run(semester) { pushApiClient.deleteAllCourses(semester) }
                // The wipe took the term's rows, queued single deletes included.
                // Before the lock goes: a retry waiting behind the wipe would
                // otherwise send one after the refetch re-uploads the roster.
                ?.also { pendingCourseDeletes.forgetTerm(semester) }
        } ?: return false
        if (_currentSemester.value == semester) _courses.value = emptyList()
        // Their names went from the file with the term.
        courseCustomNames = courseCustomNames - held
        pendingCourseOverrides.forget(moodleIds)
        val inTerm = { key: String -> CourseIgnores.semesterOf(key) == semester }
        val edit = ignoredCourses.edit { keys -> keys.filterNotTo(mutableSetOf(), inTerm) }
        try {
            courseIgnoreSync.forget(inTerm)
        } finally {
            ignoredCoursesSaved(edit)
        }
        widgetUpdater.requestUpdate()
        return true
    }

    /** A pull to refresh: what the schools have now, not an answer kept for sharing. */
    fun refresh() {
        // The school's course changes too, on either class table, as on iOS (#911).
        viewModelScope.launch { courseChangeStore.refresh() }
        if (isShowingSomeoneElse) {
            refreshShownPerson()
            return
        }
        moodleService.expireSharedResults()
        courseService.expireSharedResults()
        requestRefresh(rerun = false)
    }

    private fun requestRefresh(rerun: Boolean) {
        viewModelScope.launch {
            _isLoading.value = true
            if (!networkChecker.isAvailable()) {
                _noNetworkEvent.tryEmit(Unit)
                // The user just saw an error, not a moment to ask for a rating.
                reviewPrompt.loadFailed()
                kotlinx.coroutines.yield()
                _isLoading.value = false
                return@launch
            }
            if (rerun) {
                fetchFlight.rerun(::fetchData)
            } else {
                // A pull: what was just submitted, confirmed ones included.
                moodleService.recheckingSubmissions { fetchFlight.join(::fetchData) }
            }
        }
    }

    private suspend fun fetchData() {
        // Whatever asked for it, a pull included — see RefreshPolicy.minInterval.
        refreshTriggers.fetchStarted()
        val studentId = authService.storedStudentId ?: run { _isLoading.value = false; return }
        val password = authService.storedPassword ?: run { _isLoading.value = false; return }
        if (!networkChecker.isAvailable()) {
            _isLoading.value = false; return
        }
        _isLoading.value = true
        try {
            // Before anything else, so a newly-published term is offered by the
            // picker and 選課 enrolments are attributed to the term the portal
            // is actually serving. TTL-throttled, so this costs one request an
            // hour no matter how often the class table refreshes.
            semesterCatalog.refreshIfStale()
            followNewestSemesterIfUnpicked()
            val semester = _currentSemester.value
            // This fetch's rows are newer than a warm of the term still in flight.
            termWarm.supersede(semester)
            _availableSemesters.value = semesterOptions(semester)
            // Assignments are "upcoming from now", so they belong to the term
            // in session.
            val isCurrentSemester = semester == courseService.currentSemesterCode()
            // 選課 serves exactly one term and its 選課清單 page has no term
            // marker, so its course numbers belong to whichever term the
            // catalogue reports as open — not to whatever term is in session.
            // Keying this off currentSemesterCode() mis-filed 115-1 enrolments
            // into 114-2 for the weeks between 選課 opening and the month
            // heuristic rolling over.
            val servesSelectionSemester = semester == semesterCatalog.selectionSemesterCode()
            Log.i(
                "ClassTableVM",
                "fetchData start: semester=$semester isCurrent=$isCurrentSemester " +
                    "servesSelection=$servesSelectionSemester"
            )

            // The transcript for this fetch and the warm of the other terms
            // after it: asked of the score system once at most, through its
            // day-long cache, for the account this fetch began under.
            val transcript = ClassTableTranscript(
                fetch = {
                    if (BuildConfig.DEBUG) ServerFailureSimulator.check(ServerKind.SCORE_SYSTEM)
                    scoreService.fetchScoreReport(studentId, password)
                },
                cached = { dataCache.loadScoreReport(studentId)?.report },
                stillSignedIn = { authService.isStillSignedInAs(studentId) },
                record = { ServerStatusTracker.set(it, ServerKind.SCORE_SYSTEM) },
            )

            // Kick off the enrollment sources concurrently — they hit
            // different hosts (courseselection, moodle2 and stuinfosys
            // .ntust.edu.tw).
            //   Source 1: course-selection portal, open term only.
            //   Source 2: Moodle enrollment list, all semesters: names, Moodle
            //             IDs and links, and the assignments' courses. It adds
            //             no course to a term (tigerduck-app#890).
            //   Source 3: the transcript, once course selection gave nothing:
            //             past terms are built from it, and the open term
            //             falls back on it.
            // Before the fetch: a sign-out while it runs drops its roster.
            val generation = dataCache.writeGeneration()
            val (selectionNos, moodleAll, report) = coroutineScope {
                val selectionDef = async {
                    if (servesSelectionSemester) {
                        try {
                            courseService.fetchEnrolledCourseNos(studentId, password)
                        } catch (e: Exception) {
                            Log.e("ClassTableVM", "Failed to fetch course list: ${e.logSummary()}")
                            emptyList()
                        }
                    } else emptyList()
                }
                val moodleDef = async {
                    try {
                        moodleService.fetchEnrolledCourses()
                    } catch (e: Exception) {
                        Log.e("ClassTableVM", "Failed to fetch Moodle enrolled courses: ${e.logSummary()}")
                        emptyList<MoodleEnrolledCourse>()
                    }
                }
                val reportDef = async {
                    if (selectionDef.await().isEmpty()) transcript.fetched() else transcript.cachedOnly()
                }
                Triple(selectionDef.await(), moodleDef.await(), reportDef.await())
            }
            Log.i("ClassTableVM", "selectionNos=${selectionNos.size} -> $selectionNos")
            // Before the merge below rewrites the cache — see
            // DataCache.recordSelectionRoster. The fetch's version of the term.
            val recorded = dataCache.recordSelectionRoster(semester, selectionNos, generation)
            Log.i(
                "ClassTableVM",
                "moodleAll=${moodleAll.size} sampleIdnums=${
                    moodleAll.take(5).map { it.idnumber }
                } semesters=${moodleAll.map { it.semesterCode }.distinct()}"
            )
            // Build the idnumber → numeric-id map across ALL semesters
            // (not just the one currently displayed) so the detail popup's
            // Moodle button works for historical semesters too, keyed on
            // every code a course answers to so a 合開 course reached through
            // its second department's code still resolves — see
            // MoodleCourseIds.idMap. Only overwrite when Moodle returned
            // something so a transient failure keeps the previously cached
            // mapping live; account switches still reset it because logout
            // wipes the cache file via DataCache.clearAllUserData.
            if (moodleAll.isNotEmpty()) {
                val fresh = MoodleCourseIds.idMap(moodleAll)
                _moodleCourseIdByIdnumber.value = fresh
                dataCache.saveMoodleCourseIds(fresh)
            }

            val moodleForSem = CourseRosterMerge.moodleCoursesFor(semester, moodleAll)
            val moodleByNo = moodleForSem.associateBy { it.courseNo }
            Log.i(
                "ClassTableVM",
                "moodleForSem[$semester]=${moodleForSem.size} -> ${moodleForSem.map { it.courseNo }}"
            )

            val source = ClassTableTermSource.of(semester, selectionNos, report, servesSelectionSemester, cached = latestCourses(semester))
            Log.i("ClassTableVM", "source[$semester]=$source")
            // Whose assignments are asked for: course selection's courses,
            // else every Moodle course of the term, as before Moodle stopped
            // adding courses to it.
            val assignmentNos = CourseRosterMerge.assignmentCourseNos(selectionNos, moodleForSem)

            // Course detail lookups and assignment fetching are fully
            // independent — run them concurrently and apply each result as
            // soon as it lands.
            coroutineScope {
                val coursesJob = launch {
                    fetchTermCourses(semester, source, moodleByNo, recorded, countsAsUse = true) { authService.isStillSignedInAs(studentId) }
                }

                // Assignments are always "upcoming from now", so they belong
                // to the active enrolment — fetch only when viewing the
                // current semester. Uses the Moodle enrolment list we already
                // fetched above.
                val assignmentsJob = if (isCurrentSemester) {
                    launch {
                        try {
                            val answer = moodleService.fetchAssignments(
                                enrolledCourses = moodleAll,
                                rosterCourseNos = assignmentNos.toSet()
                            )
                            // Null keeps the cache: an enrolment fetch that
                            // failed above left no course to ask about. An
                            // empty answer replaces it like any other, see
                            // CourseRosterMerge.assignmentsToStore.
                            val merged = CourseRosterMerge.assignmentsToStore(
                                remote = answer?.assignments,
                                cached = _assignments.value,
                            )
                            // Shown as written: the write reconciles the list
                            // against newer answers, and nothing shows or is
                            // armed when it was refused.
                            val written = if (merged != null && answer != null) moodleService.storeAssignments(merged, answer) else null
                            if (written != null) {
                                _assignments.value = written
                                // The reminders and the Live Update follow the
                                // cache, as Home and the worker make them do.
                                notificationScheduler.rescheduleFromCache(dataCache, appPreferences)
                                liveActivityManager.refresh()
                            }
                        } catch (e: Exception) {
                            Log.e("ClassTableVM", "Failed to fetch assignments: ${e.logSummary()}")
                        }
                    }
                } else null

                coursesJob.join()
                assignmentsJob?.join()
            }
            _syncCompleteEvent.tryEmit(Unit)
            // The other terms, with Moodle's answer and this fetch's
            // transcript, which their rosters come from.
            warmTerms(studentId, password, moodleAll, transcript)
        } catch (e: CancellationException) {
            // Structured concurrency: viewModelScope cancellation has to keep
            // propagating, or navigating away mid-refresh leaves this coroutine
            // running against a dead screen.
            throw e
        } catch (e: Exception) {
            // This method had no catch at all. Nothing here is guarded except
            // the per-course lookups and the assignments job, so anything that
            // threw in between — a null row in a network payload, a cache row
            // Gson could not populate — propagated out of viewModelScope. With
            // no CoroutineExceptionHandler anywhere in the app, that reached
            // the default uncaught handler and killed the process instead of
            // failing one refresh. This is the only boundary in that path.
            Log.e("ClassTableVM", "fetchData failed: ${e.logSummary()}")
            reviewPrompt.loadFailed()
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * [semester]'s courses as the school has them now — see
     * [ClassTableTermFetch] — folded onto the term's list as it stands once
     * the lookups land and, when it is the term on screen, shown; then saved,
     * uploaded and sent to the widget. A course QueryCourse has no rows for
     * keeps the row the term has, or else drops to Moodle metadata, which
     * still renders with the name and credits but no schedule.
     *
     * [source] says what is looked up and how it is drawn; see
     * [ClassTableTermSource]. A past term rebuilt from its transcript records
     * its drops before it is saved, see [ClassTableTermFetch.committed].
     *
     * The commit runs under [PendingCourseAdds.uploading], in the order the
     * backend's DELETEs run in and held through the upload: a reset's DELETE
     * waits for an upload under way rather than being undone by it. The term
     * is left as it is when nothing came back, and when [mayCommit] says no
     * under that lock, before the snapshot or again before the upload: a warm
     * whose term was picked, fetched or reset meanwhile has rows older than
     * what that left — see [ClassTableTermWarm.supersede]. Nor when the
     * course selection answer the fetch [recorded] was replaced by a newer
     * one, at the snapshot or before the upload ([DataCache.isLatestAnswer]):
     * that answer dropped what it no longer lists, and its fetch saves and
     * sends the term. One refused as it was recorded is not even looked up
     * ([RecordedSelection.Refused]).
     *
     * Returns whether every query went through: false when one failed, which
     * [warmTerms] stops on — the Moodle metadata a failed lookup falls back
     * to cannot be told from a term QueryCourse no longer indexes.
     */
    private suspend fun fetchTermCourses(
        semester: String,
        source: ClassTableTermSource,
        moodleByNo: Map<String, MoodleEnrolledCourse>,
        recorded: RecordedSelection?,
        // The page's own fetch is a day of use; a warm of the other terms is not.
        countsAsUse: Boolean = false,
        mayCommit: () -> Boolean = { true },
    ): Boolean {
        // A newer answer's fetch, or a wipe, has the term already: no failure for the warm to stop on.
        if (recorded == RecordedSelection.Refused) return true
        val answer = recorded?.answer
        // Before the lookups: a sign-out while they run drops the rebuild's drops.
        val generation = dataCache.writeGeneration()
        // And the upload: the next account's token would take this roster as its own.
        val signIn = authTokenManager.signInGeneration
        val answers = termFetch.lookUp(
            semester, source.rosterNos, moodleByNo, before = latestCourses(semester), atMost = source.lookupsAtOnce,
        )
        // A rebuilt term is its transcript's even with nothing to look up.
        if (answers.isEmpty && source !is ClassTableTermSource.Rebuild) return answers.answered
        // A rebuilt past term's roster, read off its final transcript as it is committed.
        var rebuiltRoster: List<String>? = null
        pendingCourseAdds.uploading {
            if (!mayCommit()) return@uploading
            val merged = termFetch.committed(
                answers, source, semester, moodleByNo.keys,
                latest = { latestCourses(semester) },
                waitingAdds = { pendingCourseAdds.waitingIn(semester) },
                // With the courses dropped on the server, which a term only gaining leaves out too.
                droppedNos = { dataCache.fetchDropsIn(semester) },
                recordFinalRoster = {
                    rebuiltRoster = it.roster
                    dataCache.recordFinalRoster(semester, it.roster, alsoListed = it.dropping, generation)
                },
            ) { drawn ->
                // Folded and saved under the courses' lock, onto the term as its cache holds it: a
                // sync or the background fetch that took a course out of it lands wholly before.
                var folded: List<Course>? = null
                dataCache.updateCourses(semester) { current, _ ->
                    if (answer != null && !dataCache.isLatestAnswer(semester, answer)) return@updateCourses current
                    // The reads that suspend first, the list chosen and folded
                    // after them with nothing in between; the order is foldLatest's.
                    folded = termFetch.foldLatest(
                        drawn, semester,
                        editGeneration = { termEdits[semester] ?: 0 },
                        cachedCourses = { current },
                        storedTombstones = { dataCache.loadDeletedCourseNos() },
                        shownCourses = { _courses.value.takeIf { coursesSemester == semester } },
                        unsavedEdits = { unsavedEditsIn(semester) },
                        // Again under the lock: a course selection answer or a sync may have dropped a course since.
                        droppedNos = { dataCache.fetchDropsIn(semester) },
                        waitingAdds = { pendingCourseAdds.waitingIn(semester) },
                        withPendingEdits = tombstoneEdits::over,
                        names = { courseCustomNames },
                        locale = currentCourseLocale,
                    )
                    // Only apply if the user hasn't flipped to a different semester
                    // mid-flight.
                    folded?.let { merged ->
                        if (_currentSemester.value == semester) {
                            showCourses(merged, semester)
                            TigerDuckTheme.buildCourseColorMap(merged)
                        }
                    }
                    folded ?: current
                }
                folded
            } ?: run {
                // Nothing to draw, edited throughout its reads, or a newer
                // answer's: left as it is, for the next fetch.
                Log.w("ClassTableVM", "fetchTermCourses: $semester drew nothing, was edited throughout the snapshot or superseded, left as it is")
                return@uploading
            }
            // The class table loaded: a day of use towards the Play rating ask
            // (issue #332).
            if (countsAsUse) reviewPrompt.classTableLoaded()
            // Superseded during the save: the reset's wipe, or the newer
            // fetch's save, takes the file, and these rows need not go up to
            // be taken down again. A newer answer's roster would be undone.
            if (!mayCommit()) return@uploading
            if (answer != null && !dataCache.isLatestAnswer(semester, answer)) return@uploading
            // As the term's roster when this fetch's course selection answer or a past term's final
            // transcript gave it, and the upload lists all of it; see CourseUpload.of.
            val roster = when (source) {
                is ClassTableTermSource.Selection -> source.rosterNos
                is ClassTableTermSource.Rebuild -> rebuiltRoster
                else -> null
            }
            val upload = CourseUpload.of(
                merged, semester, roster, hidden = CourseTombstoneKeys.hiddenIn(semester, dataCache.loadDeletedCourseNos()),
            )
            runCatching { pushApiClient.uploadCourses(upload.courses, semester, signIn = signIn, rosterTerms = upload.rosterTerms) }
                .onFailure { Log.w("ClassTableVM", "uploadCourses failed (non-fatal): ${it.logSummary()}") }
            widgetUpdater.requestUpdate()
        }
        return answers.answered
    }

    /**
     * [semester]'s list as it stands: the one on screen when it is that
     * term's — the list every edit writes first, ahead of its cache — else
     * its cache; see [coursesSemester].
     */
    private suspend fun latestCourses(semester: String): List<Course> =
        if (coursesSemester == semester) _courses.value else dataCache.loadCourses(semester)

    /**
     * After the first fetch of a session: every term the picker offers that
     * has no cache yet, fetched in the background, one at a time, so a term
     * picked later shows at once (tigerduck-app-android#265); see
     * [ClassTableTermWarm], which runs it once per sign-in. Reuses the fetch's
     * [moodleAll], Moodle's one answer for every term, and its [transcript],
     * which the terms' rosters come from: asked for here if the fetch had no
     * need of it. Without one, only the term course selection serves is
     * warmed, and the rest wait for a later fetch. Both are [studentId]'s,
     * and reach no term once that account has left. On a job
     * of its own, not the fetch's: a pull must not wait on the other terms,
     * and a sign-out cancels it.
     */
    private fun warmTerms(
        studentId: String,
        password: String,
        moodleAll: List<MoodleEnrolledCourse>,
        transcript: ClassTableTranscript,
    ) {
        if (warmJob?.isActive == true) return
        warmJob = viewModelScope.launch {
            try {
                termWarm.run(
                    stillSignedIn = { authService.isStillSignedInAs(studentId) },
                    shared = { transcript.fetched() },
                ) { term, report, isCurrent -> warmTerm(term, isCurrent, studentId, password, moodleAll, report) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The only boundary on this job, as fetchData's catch is on
                // its own: with no CoroutineExceptionHandler in the app, a
                // cache row Gson could not populate would otherwise kill the
                // process over a term nobody asked for.
                Log.e("ClassTableVM", "warm failed: ${e.logSummary()}")
            }
        }
    }

    /**
     * One term of [warmTerms], fetched as [fetchData] fetches the term on
     * screen: 選課's roster when 選課 serves it, else the classes [report], the
     * transcript, lists for it — a past term rebuilt from them — looked up
     * course by course and cached, unless [isCurrent] says a pick, a fetch or
     * a reset of the term overtook it meanwhile, or the account left. A term
     * neither answers for, a past one with [report] null among them, is left
     * for a later warm. Says whether the school answered.
     */
    private suspend fun warmTerm(
        semester: String,
        isCurrent: () -> Boolean,
        studentId: String,
        password: String,
        moodleAll: List<MoodleEnrolledCourse>,
        report: ScoreReport?,
    ): Boolean {
        // A reset in progress wipes the term; a roster saved under it would
        // be the old one, kept. Its own refetch fills the term.
        if (semester in dataCache.resettingSemesters()) return true
        val servesSelection = semester == semesterCatalog.selectionSemesterCode()
        // Before the fetch: a sign-out while it runs drops its roster.
        val generation = dataCache.writeGeneration()
        val selectionNos = if (servesSelection) {
            try {
                courseService.fetchEnrolledCourseNos(studentId, password)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("ClassTableVM", "warm: failed to fetch course list for $semester: ${e.logSummary()}")
                return false
            }
        } else {
            emptyList()
        }
        val recorded = dataCache.recordSelectionRoster(semester, selectionNos, generation)
        val moodleByNo = CourseRosterMerge.moodleCoursesFor(semester, moodleAll).associateBy { it.courseNo }
        val source = ClassTableTermSource.of(semester, selectionNos, report, servesSelection, cached = latestCourses(semester))
        Log.i("ClassTableVM", "warm: $semester $source")
        // Nothing to draw an uncached term from.
        if (source == ClassTableTermSource.Unanswered) return true
        // Not over a reset or a fetch of the term begun since, nor under a
        // reset Settings has latched meanwhile.
        val answered = fetchTermCourses(semester, source, moodleByNo, recorded) {
            isCurrent() && semester !in dataCache.resettingSemesters()
        }
        // The today strip reads the live term off its own copy while the
        // picker shows another term, and that copy was empty until now.
        if (semester == liveSemesterCode) refreshLiveSemesterCourses()
        return answered
    }

    /**
     * Looks the courses of the user's term that still have no room up again,
     * and shows, saves and sends to the widget the rooms found
     * (tigerduck-app-android#264). Rooms are announced late, and the page
     * fetches on launch only, so a room the school added since would wait for
     * a pull. On each visit, at most once per [MissingRoomLookupInterval] per
     * term, and only after the first load: its fetch looks every course up.
     *
     * Folded onto [courses] as they are when the lookups land, as every edit
     * is, so a course added meanwhile is not written over; a term switched
     * meanwhile is left to its own fetch.
     */
    private fun lookUpMissingRooms() {
        if (!_hasSettled.value || _isLoading.value) return
        // The list's own term, not the picker's: see coursesSemester.
        val semester = coursesSemester
        val now = SystemClock.elapsedRealtime()
        if (!RefreshPolicy.hasPassed(roomLookupsAtMs[semester] ?: 0L, now, MissingRoomLookupInterval)) return
        val courseNos = ClassTableCourseMerge.nosWithoutRoom(_courses.value)
        if (courseNos.isEmpty() || !networkChecker.isAvailable()) return
        roomLookupsAtMs[semester] = now
        val account = authService.storedStudentId
        viewModelScope.launch {
            val found = courseNos.map { courseNo ->
                // Without Moodle to fall back on: null for a query that failed
                // or a course it does not know, which the row then waits out.
                async { courseNo to courseService.lookupOrFallback(semester, courseNo, moodle = null).course }
            }.awaitAll().mapNotNull { (courseNo, course) -> course?.let { courseNo to it } }.toMap()
            if (!authService.isStillSignedInAs(account) || coursesSemester != semester) return@launch
            val updated = ClassTableCourseMerge.withRoomsFound(_courses.value, found) ?: return@launch
            val edit = showEdit(updated, semester)
            TigerDuckTheme.buildCourseColorMap(updated)
            saveEdit(updated, semester, edit)
            widgetUpdater.requestUpdate()
            if (semester == liveSemesterCode) {
                // Home and the Live Update read today's courses off the cache.
                liveActivityManager.refresh()
                dataCache.notifyBackgroundSyncComplete()
            }
        }
    }
}

/** The class table's label for a semester code: all but the last character, a dash, the last. */
internal fun semesterDisplayLabel(code: String): String {
    if (code.length < 2) return code
    return code.dropLast(1) + "-" + code.last()
}
