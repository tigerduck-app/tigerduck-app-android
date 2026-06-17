package org.ntust.app.tigerduck.ui.screen.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.debug.DebugClockController
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject

data class DebugUiState(
    val overrideEnabled: Boolean,
    val draftYear: Int,
    val draftMonth: Int,
    val draftDay: Int,
    val draftHour: Int,
    val draftMinute: Int,
    val draftFrozen: Boolean,
    val effectiveNow: LocalDateTime,
)

@HiltViewModel
class DebugViewModel @Inject constructor(
    private val controller: DebugClockController,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.of("Asia/Taipei")
    private val _uiState = MutableStateFlow(initialState())
    val uiState: StateFlow<DebugUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                delay(1000)
                _uiState.value = _uiState.value.copy(effectiveNow = AppClock.localDateTime(zone))
            }
        }
    }

    fun setOverrideEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(overrideEnabled = enabled)
        if (enabled) {
            pushOverride()
        } else {
            controller.setOverride(null)
        }
    }

    fun setDate(year: Int, month: Int, day: Int) {
        _uiState.value = _uiState.value.copy(draftYear = year, draftMonth = month, draftDay = day)
        if (_uiState.value.overrideEnabled) pushOverride()
    }

    fun setTime(hour: Int, minute: Int) {
        _uiState.value = _uiState.value.copy(draftHour = hour, draftMinute = minute)
        if (_uiState.value.overrideEnabled) pushOverride()
    }

    fun setFrozen(frozen: Boolean) {
        _uiState.value = _uiState.value.copy(draftFrozen = frozen)
        if (_uiState.value.overrideEnabled) pushOverride()
    }

    private fun pushOverride() {
        val s = _uiState.value
        val instant = LocalDateTime
            .of(s.draftYear, s.draftMonth, s.draftDay, s.draftHour, s.draftMinute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        controller.setOverride(
            ClockOverride(
                instantMillis = instant,
                frozen = s.draftFrozen,
                savedAtRealMillis = System.currentTimeMillis(),
            )
        )
    }

    private fun initialState(): DebugUiState {
        val current = controller.currentOverride()
        val effective = AppClock.localDateTime(zone)
        val draft = if (current != null) {
            LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(current.instantMillis), zone)
        } else {
            effective
        }
        return DebugUiState(
            overrideEnabled = current != null,
            draftYear = draft.year,
            draftMonth = draft.monthValue,
            draftDay = draft.dayOfMonth,
            draftHour = draft.hour,
            draftMinute = draft.minute,
            draftFrozen = current?.frozen ?: true,
            effectiveNow = effective,
        )
    }
}
