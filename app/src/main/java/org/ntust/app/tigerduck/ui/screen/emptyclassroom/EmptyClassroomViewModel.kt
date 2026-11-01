package org.ntust.app.tigerduck.ui.screen.emptyclassroom

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.academic.Holiday
import org.ntust.app.tigerduck.shared.timeline.CourseMeetings
import org.ntust.app.tigerduck.emptyclassroom.CampusUsage
import org.ntust.app.tigerduck.emptyclassroom.ClassStop
import org.ntust.app.tigerduck.emptyclassroom.ClassroomUsageError
import org.ntust.app.tigerduck.emptyclassroom.ClassroomUsageService
import org.ntust.app.tigerduck.emptyclassroom.DayOff
import org.ntust.app.tigerduck.emptyclassroom.EmptyClassroomSchedule
import org.ntust.app.tigerduck.emptyclassroom.KeptUsage
import org.ntust.app.tigerduck.emptyclassroom.RoomUsage
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.component.ServerStatus
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject

/** What the finder shows under its day and period pickers. */
sealed interface FinderContent {
    data object Loading : FinderContent

    data class Rooms(val usage: CampusUsage, val fetchedAtMs: Long) : FinderContent

    /** A weekend, a day outside the term, or a weekday the site answers with no rooms. */
    data object NoData : FinderContent

    /** The site shows the weekly timetable on a holiday, so its rooms are not shown. */
    data class HolidayOff(val holiday: Holiday) : FinderContent

    data class Failed(val offline: Boolean) : FinderContent
}

data class FinderState(
    val now: LocalDateTime,
    val selectedDay: LocalDate,
    val selectedPeriod: Int = 0,
    val content: FinderContent = FinderContent.Loading,
    /** The user's classes on [selectedDay]. */
    val classes: List<ClassStop> = emptyList(),
    val pickedBuilding: String? = null,
    /** A pull's spinner. */
    val isRefreshing: Boolean = false,
    /** A day on screen is being read again, without a pull. */
    val isReloading: Boolean = false,
    /**
     * How the room booking site answered the last read, for the header's dot: a read that fails
     * keeps the day already on screen, and only the dot then says so.
     */
    val siteStatus: ServerStatus = ServerStatus.UNKNOWN,
    /** When the day on screen was read, for the dot's last sync; null for a day nothing was read for. */
    val syncedAtMs: Long? = null,
    val nextTeachingDay: LocalDate? = null,
    /**
     * The room whose sheet is open. Only its name is kept, so the sheet shows the room as the
     * latest read has it; another day, or rooms without it, close the sheet.
     */
    val openRoomName: String? = null,
) {
    val today: LocalDate get() = now.toLocalDate()

    /** Today and the 13 days after it. */
    val days: List<LocalDate>
        get() = (0 until EmptyClassroomSchedule.DAY_COUNT).map { today.plusDays(it.toLong()) }

    val isShowingToday: Boolean get() = selectedDay == today

    /** Periods already over on the day shown: none on a later day. */
    val endedPeriods: Int get() = if (isShowingToday) EmptyClassroomSchedule.endedPeriods(now) else 0

    /** The period in progress, or the next in a break, today only. */
    val currentPeriod: Int? get() = if (isShowingToday) EmptyClassroomSchedule.upcomingPeriod(now) else null

    val usage: CampusUsage? get() = (content as? FinderContent.Rooms)?.usage

    /** The room whose sheet is open, as the rooms on screen have it. */
    val openRoom: RoomUsage? get() = openRoomName?.let { name -> usage?.rooms?.firstOrNull { it.room == name } }
}

/**
 * The finder's state: the chosen day and period, that day's rooms, the user's classes that day,
 * and the building the user picked. Everything else on the screen derives from these.
 */
@HiltViewModel
class EmptyClassroomViewModel internal constructor(
    private val service: ClassroomUsageService,
    private val env: FinderEnvironment,
) : ViewModel() {
    @Inject
    constructor(service: ClassroomUsageService, env: AppFinderEnvironment) : this(service, env as FinderEnvironment)

    private val _state = MutableStateFlow(
        env.now().let { FinderState(now = it, selectedDay = it.toLocalDate(), pickedBuilding = env.pickedBuilding) },
    )
    val state: StateFlow<FinderState> = _state.asStateFlow()

    val signedIn: StateFlow<Boolean> get() = env.signedIn

    private var courses: List<Course> = emptyList()
    private var hasOpened = false

    /** Between [appear] and [disappear]: only then does the finder read anything. */
    private var isStarted = false
    private var loadJob: Job? = null
    private var tickerJob: Job? = null
    private var coursesJob: Job? = null

    /** Moved by each account change, so a course read started for the account before shows nothing. */
    private var accountEpoch = 0

    /** The day the load in flight reads, and which load it is. */
    private var running: Pair<LocalDate, Int>? = null
    private var loads = 0

    init {
        // A sign-out, or a sign-in on another account, while the screen exists: what it holds
        // belonged to the account before.
        viewModelScope.launch { env.signedIn.drop(1).collect { accountChanged() } }
        // A sync that rewrote the timetable while the screen is open; off screen, the next
        // appear() reads the courses anyway.
        viewModelScope.launch { env.coursesChanged.drop(1).collect { if (isStarted) reloadCourses() } }
        viewModelScope.launch { env.calendarChanged.collect { calendarChanged() } }
        viewModelScope.launch { env.courseChangesChanged.collect { refreshClasses() } }
    }

    /**
     * Opens on the current period, or the next teaching day's first, the first time, after the
     * day shown has passed and once the period chosen today has ended; then loads the day. A
     * pinned tab keeps this model, so a morning's period would otherwise stay into the afternoon.
     * As iOS (tigerduck-app/tigerduck-app#533).
     */
    fun appear() {
        isStarted = true
        tick()
        val state = _state.value
        if (!hasOpened || state.selectedDay < state.today || state.selectedPeriod < state.endedPeriods) {
            val (day, period) = EmptyClassroomSchedule.opening(
                state.now, EmptyClassroomSchedule.DAY_COUNT - 1, ::isTeachingDay,
            )
            edit { it.copy(selectedDay = day, selectedPeriod = period) }
            hasOpened = true
        }
        startTicker()
        reloadCourses()
        load(force = false)
    }

    /**
     * The screen went away. A read it started goes on, as a day takes long to read, and its
     * spinners with it: a return to the day joins the read instead of sending its requests again.
     * As iOS (tigerduck-app/tigerduck-app#628).
     */
    fun disappear() {
        isStarted = false
        tickerJob?.cancel()
        tickerJob = null
    }

    /**
     * The signed-in account changed: what the screen holds belonged to the one before, a read
     * still running off screen included. Off screen that is only forgotten; the next [appear]
     * reads for the new account.
     */
    fun accountChanged() {
        stopLoading()
        accountEpoch++
        coursesJob?.cancel()
        coursesJob = null
        courses = emptyList()
        edit {
            it.copy(
                content = FinderContent.Loading,
                syncedAtMs = null,
                classes = emptyList(),
                pickedBuilding = env.pickedBuilding,
                isRefreshing = false,
                isReloading = false,
                siteStatus = ServerStatus.UNKNOWN,
            )
        }
        if (!isStarted) return
        reloadCourses()
        load(force = false)
    }

    /**
     * The user's classes come from the class table, which a sync can change while the screen is
     * open. The cache read suspends, so a read the account has changed under shows nothing.
     */
    fun reloadCourses() {
        coursesJob?.cancel()
        val epoch = accountEpoch
        val account = env.account()
        coursesJob = viewModelScope.launch {
            val read = runCatching { env.courses() }.getOrElse { if (it is CancellationException) throw it else emptyList() }
            if (epoch != accountEpoch || env.account() != account) return@launch
            courses = read
            refreshClasses()
        }
    }

    /**
     * The academic calendar changed. It can arrive after the day was read, as on a first launch,
     * when every weekday still counted as a class day, so a day it takes off stops its read and
     * shows no rooms.
     */
    private fun calendarChanged() {
        refreshClasses()
        val off = EmptyClassroomSchedule.dayOff(_state.value.selectedDay, env.calendar()) ?: return
        stopLoading()
        show(contentOf(off))
    }

    fun select(day: LocalDate) {
        if (day == _state.value.selectedDay) return
        edit { it.copy(selectedDay = day) }
        refreshClasses()
        load(force = false)
    }

    fun select(period: Int) {
        if (period !in EmptyClassroomSchedule.periodIds.indices) return
        edit { it.copy(selectedPeriod = period) }
    }

    fun pick(building: String) {
        env.pickedBuilding = building
        edit { it.copy(pickedBuilding = building) }
    }

    fun open(room: RoomUsage) {
        edit { it.copy(openRoomName = room.room) }
    }

    fun closeRoom() {
        edit { it.copy(openRoomName = null) }
    }

    /** A pull: reads the day again even when it was read moments ago, and the user's classes. */
    fun refresh() {
        edit { it.copy(isRefreshing = true) }
        reloadCourses()
        load(force = true)
    }

    fun retry() {
        edit { it.copy(content = FinderContent.Loading, syncedAtMs = null) }
        load(force = true)
    }

    private fun isTeachingDay(day: LocalDate): Boolean = EmptyClassroomSchedule.isTeachingDay(day, env.calendar())

    private fun refreshClasses() {
        val state = _state.value
        // Every day counts as a class day: the rooms, which the stops are for, show only on one.
        val slots = CourseMeetings.meetingSlots(courses, state.selectedDay, env.courseChanges(), isClassDay = { true })
        val classes = ClassStop.stops(slots)
        val remaining = java.time.temporal.ChronoUnit.DAYS.between(state.selectedDay, state.days.last()).toInt()
        edit {
            it.copy(
                classes = classes,
                nextTeachingDay = EmptyClassroomSchedule.nextTeachingDay(state.selectedDay, remaining, ::isTeachingDay),
            )
        }
    }

    private fun stopLoading() {
        loadJob?.cancel()
        loadJob = null
        running = null
    }

    private fun load(force: Boolean) {
        val day = _state.value.selectedDay
        // A repeated appear() while this day loads keeps that load, and so does a return after
        // disappear(). Another day, a pull and an account change still end it, so a new read
        // does not queue behind it.
        if (!force && running?.first == day) return
        stopLoading()
        refreshClasses()
        EmptyClassroomSchedule.dayOff(day, env.calendar())?.let { return show(contentOf(it)) }
        val credentials = env.credentials() ?: return show(FinderContent.Loading)
        val kept = service.keptUsage(day)
        edit {
            it.copy(
                content = kept?.let(::contentOf) ?: FinderContent.Loading,
                syncedAtMs = kept?.fetchedAtMs,
                isReloading = kept != null && !it.isRefreshing,
            )
        }
        val isToday = day == _state.value.today
        val id = ++loads
        running = day to id
        loadJob = viewModelScope.launch {
            val outcome = try {
                Result.success(service.usage(day, isToday, force, credentials))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (running?.second == id) running = null
            if (!isActive || _state.value.selectedDay != day || !env.isCurrent(credentials)) return@launch
            outcome.onSuccess { fresh ->
                edit {
                    it.copy(
                        content = contentOf(fresh),
                        syncedAtMs = fresh.fetchedAtMs,
                        isRefreshing = false,
                        isReloading = false,
                        siteStatus = ServerStatus.OK,
                    )
                }
            }.onFailure { error ->
                if (error is ClassroomUsageError.SignedOut) return@launch
                val offline = env.isOffline(error)
                edit {
                    // A day already on screen stays when reading it again fails; the service
                    // stops offering a day as old as half a day to stand in while it reloads.
                    val content = if (it.content == FinderContent.Loading) FinderContent.Failed(offline) else it.content
                    it.copy(
                        content = content,
                        isRefreshing = false,
                        isReloading = false,
                        siteStatus = ServerStatus.FAILED,
                    )
                }
            }
        }
    }

    private fun show(content: FinderContent) {
        edit { it.copy(content = content, syncedAtMs = null, isRefreshing = false, isReloading = false) }
    }

    private fun contentOf(off: DayOff): FinderContent = when (off) {
        is DayOff.HolidayOff -> FinderContent.HolidayOff(off.holiday)
        DayOff.Weekend, DayOff.OutOfTerm -> FinderContent.NoData
    }

    private fun contentOf(kept: KeptUsage): FinderContent =
        if (kept.usage.rooms.isEmpty()) FinderContent.NoData else FinderContent.Rooms(kept.usage, kept.fetchedAtMs)

    /** The app clock, read again as each minute passes; a day that has passed reopens the finder. */
    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = viewModelScope.launch {
            while (isActive) {
                val now = env.now()
                delay(((60 - now.second) * 1000L - now.nano / 1_000_000).coerceAtLeast(1_000L))
                val before = _state.value.today
                tick()
                if (_state.value.today != before && _state.value.selectedDay < _state.value.today) {
                    hasOpened = false
                    appear()
                }
            }
        }
    }

    private fun tick() {
        edit { it.copy(now = env.now()) }
    }

    /**
     * Every change of the state goes through here, so an open room's sheet closes on another day
     * and once the rooms on screen lack the room, a loading screen included; the room's return
     * in a later read does not open it again.
     */
    private inline fun edit(transform: (FinderState) -> FinderState) {
        _state.update { old ->
            val new = transform(old)
            val closes = new.openRoomName != null && (new.selectedDay != old.selectedDay || new.openRoom == null)
            if (closes) new.copy(openRoomName = null) else new
        }
    }

    override fun onCleared() {
        stopLoading()
    }
}
