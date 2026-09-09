package org.ntust.app.tigerduck.ui.screen.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.model.CalendarEvent
import org.ntust.app.tigerduck.data.model.EventSource
import org.ntust.app.tigerduck.network.CalendarService
import org.ntust.app.tigerduck.network.MoodleService
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.NetworkChecker
import org.ntust.app.tigerduck.notification.SyncSource
import org.ntust.app.tigerduck.shared.clock.AppClock
import java.util.Calendar
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val networkChecker: NetworkChecker,
    private val calendarService: CalendarService,
    private val moodleService: MoodleService,
    private val authService: AuthService,
    private val dataCache: DataCache,
    private val prefs: AppPreferences,
    @param:dagger.hilt.android.qualifiers.ApplicationContext
    private val context: android.content.Context,
    private val academicCalendar: org.ntust.app.tigerduck.academic.AcademicCalendarStore,
    private val pushApiClient: org.ntust.app.tigerduck.push.PushApiClient,
) : ViewModel() {


    private val _events = MutableStateFlow<List<CalendarEvent>>(emptyList())
    val events: StateFlow<List<CalendarEvent>> = _events

    private val _selectedDate = MutableStateFlow(Date(AppClock.nowMillis()))
    val selectedDate: StateFlow<Date> = _selectedDate

    private val _displayedMonth = MutableStateFlow(Date(AppClock.nowMillis()))
    val displayedMonth: StateFlow<Date> = _displayedMonth

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    val isLoggedIn: StateFlow<Boolean> = authService.authState

    private var hasLoaded = false

    /**
     * Semester boundaries and school holidays, from the published academic
     * calendar.
     *
     * Rebuilt on every read rather than cached because the holiday name is
     * locale-dependent and the app language can change under us. Cheap —
     * a few dozen rows off an in-memory list.
     *
     * Kept separate from the fetched sources so it survives the sign-out
     * path that empties the rest: the school calendar is public information
     * the user can still use while logged out.
     */
    private fun academicEvents(): List<CalendarEvent> =
        org.ntust.app.tigerduck.academic.AcademicCalendarEvents.eventsFor(
            calendar = academicCalendar.current(),
            languageTag = java.util.Locale.getDefault().toLanguageTag(),
            startTitle = { context.getString(R.string.calendar_semester_start, it) },
            endTitle = { context.getString(R.string.calendar_semester_end, it) },
            formatCode = { code ->
                if (code.length == 4) "${code.take(3)}-${code.drop(3)}" else code
            },
            zone = org.ntust.app.tigerduck.AppConstants.TAIPEI_ZONE,
        )

    /**
     * The holiday a row belongs to, or null when it is a term boundary or an
     * ordinary event. Drives whether the "still remind me" toggle appears.
     */
    fun holidayIdFor(event: CalendarEvent): Int? =
        org.ntust.app.tigerduck.academic.AcademicCalendarEvents.holidayIdFor(event)

    private val _holidayOverrides =
        MutableStateFlow(academicCalendar.optedInHolidayIds)

    /** Holidays the user has opted back into, as a flow so a row redraws the
     *  moment its own toggle flips. */
    val holidayOverrides: StateFlow<Set<Int>> = _holidayOverrides

    /**
     * Flip the exception for one holiday.
     *
     * The local write is what makes the guard behave; the upload only makes
     * the user's other devices agree, so a failure there is logged and
     * swallowed rather than rolled back — the setting the user just made on
     * this device should stand either way.
     */
    fun setNotifyOnHoliday(holidayId: Int, notify: Boolean) {
        if (!academicCalendar.setNotifyOnHoliday(holidayId, notify)) return
        _holidayOverrides.value = academicCalendar.optedInHolidayIds
        viewModelScope.launch {
            runCatching { pushApiClient.putHolidayOverride(holidayId, notify) }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    android.util.Log.w(
                        "CalendarViewModel", "holiday override upload failed", e
                    )
                }
        }
    }

    /** Replaces the academic rows in [base] with freshly-built ones. */
    private fun withAcademicEvents(base: List<CalendarEvent>): List<CalendarEvent> =
        base.filterNot { it.sourceRaw in ACADEMIC_SOURCES } + academicEvents()

    init {
        viewModelScope.launch {
            // The published calendar arrives asynchronously: `MainActivity`
            // starts the fetch on resume, which routinely lands after this
            // screen has already built its rows from the on-disk copy. A
            // snapshot read would leave a newly published semester or
            // holiday invisible until the next cold launch.
            academicCalendar.calendar.collect {
                _events.value = withAcademicEvents(_events.value)
            }
        }
        viewModelScope.launch {
            // Clear / refresh in sync with auth changes.
            authService.authState.collect { isAuthed ->
                if (!isAuthed) {
                    // Holidays are public school information, so they stay
                    // on the calendar after a sign-out; only the account's
                    // own events go.
                    _events.value = academicEvents()
                    hasLoaded = false
                } else {
                    fetchData()
                }
            }
        }
    }

    val selectedDateEvents: StateFlow<List<CalendarEvent>> =
        combine(_events, _selectedDate) { events, selectedDate ->
            events
                .filter { it.date.isSameDay(selectedDate) }
                .sortedBy { it.date }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun eventsOnDate(date: Date): List<CalendarEvent> =
        _events.value.filter { it.date.isSameDay(date) }

    fun selectDate(date: Date) {
        _selectedDate.value = date
        _displayedMonth.value = date
    }

    fun previousMonth() {
        val cal = Calendar.getInstance(org.ntust.app.tigerduck.AppConstants.TAIPEI_TZ).apply {
            time = _displayedMonth.value
            add(Calendar.MONTH, -1)
        }
        _displayedMonth.value = cal.time
    }

    fun nextMonth() {
        val cal = Calendar.getInstance(org.ntust.app.tigerduck.AppConstants.TAIPEI_TZ).apply {
            time = _displayedMonth.value
            add(Calendar.MONTH, 1)
        }
        _displayedMonth.value = cal.time
    }

    fun setDisplayedMonth(date: Date) {
        _displayedMonth.value = date
    }

    fun goToToday() {
        val today = Date(AppClock.nowMillis())
        _selectedDate.value = today
        _displayedMonth.value = today
    }

    fun load() {
        if (hasLoaded) return
        hasLoaded = true
        viewModelScope.launch {
            _events.value = withAcademicEvents(dataCache.loadCalendarEvents())
            // The school ICS is public, but the user expects a logged-out
            // calendar to stay completely idle (no spinner, no network).
            if (authService.authState.value) fetchData()
        }
    }

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

    fun refresh() {
        // Unconditional, and ahead of the auth guard: the academic calendar
        // is the one source on this screen that needs no account, so a pull
        // must still refresh it for a signed-out user.
        viewModelScope.launch { academicCalendar.refresh() }
        if (!authService.authState.value) return
        viewModelScope.launch {
            _isLoading.value = true
            if (!networkChecker.isAvailable()) {
                _noNetworkEvent.tryEmit(Unit)
                _isLoading.value = false
                return@launch
            }
            fetchData()
        }
    }

    private suspend fun fetchData() {
        _isLoading.value = true
        try {
            val (schoolEvents, moodleEvents) = coroutineScope {
                val schoolEventsJob = async { calendarService.fetchAndParseICS() }
                val moodleEventsJob = async { fetchMoodleCalendarEvents() }
                schoolEventsJob.await() to moodleEventsJob.await()
            }

            val current = _events.value.toMutableList()

            if (schoolEvents.isNotEmpty()) {
                current.removeAll { it.sourceRaw == EventSource.SCHOOL.raw }
                current.addAll(schoolEvents)
            }
            if (moodleEvents.isNotEmpty()) {
                current.removeAll { it.sourceRaw == EventSource.MOODLE.raw }
                current.addAll(moodleEvents)
            }
            if (schoolEvents.isNotEmpty() || moodleEvents.isNotEmpty()) {
                _events.value = withAcademicEvents(current)
                dataCache.saveCalendarEvents(current)
                _syncCompleteEvent.tryEmit(Unit)
            }
        } catch (e: Exception) {
            // Keep existing events
        } finally {
            _isLoading.value = false
        }
    }

    private suspend fun fetchMoodleCalendarEvents(): List<CalendarEvent> {
        val studentId = authService.storedStudentId
        val password = authService.storedPassword

        if (studentId.isNullOrBlank() || password.isNullOrBlank()) {
            return dataCache.loadAssignments().toCalendarEvents()
        }

        return try {
            // Re-establish NTUST session before Moodle call, so calendar refresh works standalone.
            if (!authService.ensureAuthenticated()) {
                return dataCache.loadAssignments().toCalendarEvents()
            }
            val enrolled = moodleService.fetchEnrolledCourses()
            val assignments = moodleService.fetchAssignments(enrolled)
            // Same guard as the class table's: an empty answer is upstream
            // failing quietly, and overwriting with it empties the calendar
            // on every other screen too.
            if (assignments.isEmpty()) return dataCache.loadAssignments().toCalendarEvents()
            dataCache.saveAssignments(assignments)
            assignments.toCalendarEvents()
        } catch (_: Exception) {
            // Keep calendar useful when Moodle auth/network is temporarily unavailable.
            dataCache.loadAssignments().toCalendarEvents()
        }
    }

    private fun List<Assignment>.toCalendarEvents(): List<CalendarEvent> =
        map { assignment ->
            CalendarEvent(
                eventId = "moodle-${assignment.assignmentId}",
                title = assignment.title,
                date = assignment.dueDate,
                sourceRaw = EventSource.MOODLE.raw
            )
        }

    /**
     * Both sources the academic feed produces. Rows carrying either are
     * rebuilt from the feed on every merge, so a boundary left over from a
     * build that still filed them under `holiday` is dropped rather than
     * kept forever from the on-disk cache.
     */
    private companion object {
        val ACADEMIC_SOURCES =
            setOf(EventSource.HOLIDAY.raw, EventSource.SEMESTER.raw)
    }

    private fun Date.isSameDay(other: Date): Boolean {
        val cal1 = Calendar.getInstance(org.ntust.app.tigerduck.AppConstants.TAIPEI_TZ)
            .apply { time = this@isSameDay }
        val cal2 = Calendar.getInstance(org.ntust.app.tigerduck.AppConstants.TAIPEI_TZ)
            .apply { time = other }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }
}
