package org.ntust.app.tigerduck.debug

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates debug clock override changes: writes to AppClock, persists,
 * pushes to the watch (no-op on fdroid), and triggers a reschedule of all
 * AlarmManager-backed services so existing alarms align with the new clock.
 *
 * Reschedule wiring is added in Task 11.
 */
@Singleton
class DebugClockController @Inject constructor(
    private val store: DebugClockPrefsStore,
    private val wearBridge: WearDebugClockBridge,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Load persisted override into AppClock at process start. */
    fun bootstrap() {
        val persisted = store.load()
        if (persisted != null) {
            AppClock.setOverride(persisted)
        }
    }

    fun setOverride(override: ClockOverride?) {
        AppClock.setOverride(override)
        store.save(override)
        scope.launch { wearBridge.push(override) }
        // Reschedule wiring lands in Task 11.
    }

    fun currentOverride(): ClockOverride? = AppClock.currentOverride()
}
