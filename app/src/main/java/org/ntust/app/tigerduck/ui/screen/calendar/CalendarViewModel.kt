package org.ntust.app.tigerduck.ui.screen.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.model.CalendarEvent
import org.ntust.app.tigerduck.data.model.EventSource
import org.ntust.app.tigerduck.network.CalendarService
import org.ntust.app.tigerduck.network.MoodleService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val calendarService: CalendarService,
    private val moodleService: MoodleService,
    private val authService: AuthService,
    private val dataCache: DataCache
) : ViewModel() {

    private val _events = MutableStateFlow<List<CalendarEvent>>(emptyList())
    val events: StateFlow<List<CalendarEvent>> = _events

    private val _selectedDate = MutableStateFlow(Date())
    val selectedDate: StateFlow<Date> = _selectedDate

    private val _displayedMonth = MutableStateFlow(Date())
    val displayedMonth: StateFlow<Date> = _displayedMonth

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private var hasLoaded = false

    val selectedDateEvents: StateFlow<List<CalendarEvent>> = combine(_events, _selectedDate) { events, selectedDate ->
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
        val cal = Calendar.getInstance().apply {
            time = _displayedMonth.value
            add(Calendar.MONTH, -1)
        }
        _displayedMonth.value = cal.time
    }

    fun nextMonth() {
        val cal = Calendar.getInstance().apply {
            time = _displayedMonth.value
            add(Calendar.MONTH, 1)
        }
        _displayedMonth.value = cal.time
    }

    fun goToToday() {
        val today = Date()
        _selectedDate.value = today
        _displayedMonth.value = today
    }

    fun load() {
        if (hasLoaded) return
        hasLoaded = true
        _events.value = dataCache.loadCalendarEvents()
        viewModelScope.launch { fetchData() }
    }

    fun refresh() {
        viewModelScope.launch { fetchData() }
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
            var changed = false

            if (schoolEvents.isNotEmpty()) {
                current.removeAll { it.sourceRaw == EventSource.SCHOOL.raw }
                current.addAll(schoolEvents)
                changed = true
            }

            if (moodleEvents.isNotEmpty()) {
                current.removeAll { it.sourceRaw == EventSource.MOODLE.raw }
                current.addAll(moodleEvents)
                changed = true
            }

            if (changed) {
                _events.value = current
                dataCache.saveCalendarEvents(current)
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
            val assignments = moodleService.fetchAssignments(studentId, password)
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

    private fun Date.isSameDay(other: Date): Boolean {
        val cal1 = Calendar.getInstance().apply { time = this@isSameDay }
        val cal2 = Calendar.getInstance().apply { time = other }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
               cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }
}
