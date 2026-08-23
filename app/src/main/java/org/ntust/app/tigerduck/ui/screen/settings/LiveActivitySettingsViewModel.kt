// State holder for the Live Activity settings screen. Every setter writes
// straight through to LiveActivityPreferences and then asks the manager to
// refresh, so a toggle is reflected in the live notification without waiting
// for the next scheduled tick.

package org.ntust.app.tigerduck.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.liveactivity.LiveActivityManager
import org.ntust.app.tigerduck.liveactivity.LiveActivityPreferences
import org.ntust.app.tigerduck.notification.SystemPermissions
import javax.inject.Inject

@HiltViewModel
class LiveActivitySettingsViewModel @Inject constructor(
    val prefs: LiveActivityPreferences,
    val systemPermissions: SystemPermissions,
    private val manager: LiveActivityManager,
) : ViewModel() {

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<State> = _state.asStateFlow()

    fun setEnabled(v: Boolean) {
        prefs.isEnabled = v; emit()
    }

    fun setShowInClass(v: Boolean) {
        prefs.showInClass = v; emit()
    }

    fun setShowClassPreparing(v: Boolean) {
        prefs.showClassPreparing = v; emit()
    }

    fun setShowAssignment(v: Boolean) {
        prefs.showAssignment = v; emit()
    }

    fun setShowOnLockScreen(v: Boolean) {
        prefs.showOnLockScreen = v; emit()
    }

    fun setSoundInClass(v: Boolean) {
        prefs.soundInClass = v; emit()
    }

    fun setSoundClassPreparing(v: Boolean) {
        prefs.soundClassPreparing = v; emit()
    }

    fun setSoundAssignment(v: Boolean) {
        prefs.soundAssignment = v; emit()
    }

    fun setAssignmentLeadMinutes(minutes: Int) {
        val floor = (LiveActivityPreferences.MIN_ASSIGNMENT_LEAD_SEC / 60).toInt()
        val ceiling = (LiveActivityPreferences.MAX_ASSIGNMENT_LEAD_SEC / 60).toInt()
        prefs.assignmentLeadTimeSec = minutes.coerceIn(floor, ceiling).toLong() * 60
        emit()
    }

    fun setClassLeadMinutes(m: Int) {
        val floor = (LiveActivityPreferences.MIN_CLASS_LEAD_SEC / 60).toInt().coerceAtLeast(1)
        val ceiling = (LiveActivityPreferences.MAX_CLASS_LEAD_SEC / 60).toInt()
        prefs.classPreparingLeadTimeSec = m.coerceIn(floor, ceiling).toLong() * 60
        emit()
    }

    fun resetDefaults() {
        prefs.resetToDefaults()
        emit()
    }

    /** Called when the screen resumes so the permission rows reflect reality. */
    fun refreshPermissions() {
        _state.value = _state.value.copy(permissions = systemPermissions.states())
    }

    private fun emit() {
        _state.value = snapshot()
        viewModelScope.launch { manager.refresh() }
    }

    private fun snapshot() = State(
        enabled = prefs.isEnabled,
        showInClass = prefs.showInClass,
        showClassPreparing = prefs.showClassPreparing,
        showAssignment = prefs.showAssignment,
        showOnLockScreen = prefs.showOnLockScreen,
        soundInClass = prefs.soundInClass,
        soundClassPreparing = prefs.soundClassPreparing,
        soundAssignment = prefs.soundAssignment,
        assignmentLeadMinutes = (prefs.assignmentLeadTimeSec / 60).toInt(),
        classLeadMinutes = (prefs.classPreparingLeadTimeSec / 60).toInt(),
        permissions = systemPermissions.states(),
    )

    data class State(
        val enabled: Boolean,
        val showInClass: Boolean,
        val showClassPreparing: Boolean,
        val showAssignment: Boolean,
        val showOnLockScreen: Boolean,
        val soundInClass: Boolean,
        val soundClassPreparing: Boolean,
        val soundAssignment: Boolean,
        val assignmentLeadMinutes: Int,
        val classLeadMinutes: Int,
        val permissions: List<org.ntust.app.tigerduck.notification.PermissionState>,
    )
}
