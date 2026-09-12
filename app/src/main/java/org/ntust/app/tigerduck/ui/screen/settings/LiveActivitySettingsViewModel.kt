// State holder for the Live Activity settings screen. Every setter writes
// straight through to LiveActivityPreferences and then asks the manager to
// refresh, so a toggle is reflected in the live notification without waiting
// for the next scheduled tick.
//
// The five settings the `notification` settings document's `live_activity`
// section carries (spec §4.6) additionally enqueue a push to the backend, so
// they follow the user to their other devices — see NotificationSettingsSync.
// The rest (the master switch, lock-screen visibility, the three per-scenario
// sounds) are device-local: they are not in that section, and this task must
// not invent document fields for them.

package org.ntust.app.tigerduck.ui.screen.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.liveactivity.LiveActivityManager
import org.ntust.app.tigerduck.liveactivity.LiveActivityPreferences
import org.ntust.app.tigerduck.notification.SystemPermissions
import org.ntust.app.tigerduck.push.NotificationSettingsSync
import javax.inject.Inject

@HiltViewModel
class LiveActivitySettingsViewModel @Inject constructor(
    val prefs: LiveActivityPreferences,
    val systemPermissions: SystemPermissions,
    private val manager: LiveActivityManager,
    private val notificationSettingsSync: NotificationSettingsSync,
) : ViewModel() {

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        // Read-and-apply half of the notification-document sync: pick up
        // whatever another device (most likely iOS) has written to the
        // shared live_activity section before this screen shows anything,
        // so an iOS-side lead-time change isn't invisible here.
        //
        // Must not let a transport failure escape: SettingsDocumentApiClient.read()
        // throws on an IOException (offline, DNS, timeout) and on any non-2xx,
        // non-404 status, and viewModelScope has no CoroutineExceptionHandler --
        // an uncaught throw here would reach the default handler and kill the
        // process, turning "open this screen on a train" into a crash. A failed
        // pull degrades to "keep local values" exactly like a malformed document
        // does; it just also has to cover a failed *transport*, not only a
        // parsed-but-unusable response.
        viewModelScope.launch {
            runCatching { notificationSettingsSync.pullNow() }
                .onSuccess { applied -> if (applied) _state.value = snapshot() }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    Log.w(TAG, "pulling live_activity settings failed", e)
                }
        }
    }

    fun setEnabled(v: Boolean) {
        prefs.isEnabled = v; emit()
    }

    fun setShowInClass(v: Boolean) {
        prefs.showInClass = v; emitAndSync()
    }

    fun setShowClassPreparing(v: Boolean) {
        prefs.showClassPreparing = v; emitAndSync()
    }

    fun setShowAssignment(v: Boolean) {
        prefs.showAssignment = v; emitAndSync()
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
        emitAndSync()
    }

    fun setClassLeadMinutes(m: Int) {
        val floor = (LiveActivityPreferences.MIN_CLASS_LEAD_SEC / 60).toInt().coerceAtLeast(1)
        val ceiling = (LiveActivityPreferences.MAX_CLASS_LEAD_SEC / 60).toInt()
        prefs.classPreparingLeadTimeSec = m.coerceIn(floor, ceiling).toLong() * 60
        emitAndSync()
    }

    fun resetDefaults() {
        prefs.resetToDefaults()
        // Resets all five synced values (and every device-local one) to
        // their defaults, which is a change the user's other devices have to
        // hear about like any other.
        emitAndSync()
    }

    /** Called when the screen resumes so the permission rows reflect reality. */
    fun refreshPermissions() {
        _state.value = _state.value.copy(permissions = systemPermissions.states())
    }

    private fun emit() {
        _state.value = snapshot()
        viewModelScope.launch { manager.refresh() }
    }

    /**
     * [emit], plus a push of the `live_activity` section to the backend —
     * for the five settings that section actually carries.
     *
     * The push deliberately does not run on [viewModelScope]: leaving the
     * screen right after flipping a toggle would cancel it and lose the
     * write. [NotificationSettingsSync] owns an application-scoped, coalesced
     * queue instead, so this call just marks the preferences dirty and
     * returns.
     */
    private fun emitAndSync() {
        emit()
        notificationSettingsSync.markUnconfirmedAndPush()
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

    private companion object {
        const val TAG = "LiveActivitySettings"
    }
}
