package org.ntust.app.tigerduck.ui.screen.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.CalendarEvent
import org.ntust.app.tigerduck.data.model.EventSource
import org.ntust.app.tigerduck.data.model.MockData
import org.ntust.app.tigerduck.network.CalendarService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val calendarService: CalendarService,
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

    val eventsForSelectedDate: List<CalendarEvent>
        get() = _events.value.filter { it.date.isSameDay(_selectedDate.value) }.sortedBy { it.date }

    fun eventsOnDate(date: Date): List<CalendarEvent> =
        _events.value.filter { it.date.isSameDay(date) }

    fun selectDate(date: Date) { _selectedDate.value = date }

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
        _events.value = dataCache.loadCalendarEvents().ifEmpty { MockData.calendarEvents }
        viewModelScope.launch { fetchData() }
    }

    fun refresh() {
        viewModelScope.launch { fetchData() }
    }

    private suspend fun fetchData() {
        _isLoading.value = true
        try {
            val schoolEventsJob = viewModelScope.async { calendarService.fetchAndParseICS() }
            val schoolEvents = schoolEventsJob.await()

            if (schoolEvents.isNotEmpty()) {
                val current = _events.value.toMutableList()
                current.removeAll { it.sourceRaw == EventSource.SCHOOL.raw }
                current.addAll(schoolEvents)
                _events.value = current
                dataCache.saveCalendarEvents(current)
            }
        } catch (e: Exception) {
            // Keep existing events
        } finally {
            _isLoading.value = false
        }
    }

    private fun Date.isSameDay(other: Date): Boolean {
        val cal1 = Calendar.getInstance().apply { time = this@isSameDay }
        val cal2 = Calendar.getInstance().apply { time = other }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
               cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }
}
