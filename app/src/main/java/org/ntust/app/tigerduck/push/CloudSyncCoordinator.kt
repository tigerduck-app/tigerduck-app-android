package org.ntust.app.tigerduck.push

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

sealed class CloudSyncState {
    data object Disabled : CloudSyncState()
    data class Enabling(val step: String) : CloudSyncState()
    data object Active : CloudSyncState()
    data class NeedsReauth(val reason: String) : CloudSyncState()
}

// ---------------------------------------------------------------------------
// CloudSyncCoordinator
// ---------------------------------------------------------------------------

/**
 * Owns the cloud-sync lifecycle: enable/disable state machine, periodic sync
 * ticks, and outbox drain with retry. Delegates actual API calls to
 * [PushApiClient].
 *
 * Instantiated manually in [SettingsViewModel] — does **not** use Hilt
 * `@Inject`. Requires a [CoroutineScope] for the periodic tick timer (pass
 * `viewModelScope`).
 */
class CloudSyncCoordinator(
    private val pushApiClient: PushApiClient,
    private val pushRegistration: PushRegistrationService,
    private val prefs: org.ntust.app.tigerduck.data.preferences.AppPreferences,
    val outbox: SyncOutbox,
    val idMap: SyncIdMap,
    private val scope: CoroutineScope,
) {

    // -- Tunables ---------------------------------------------------------

    companion object {
        private const val TAG = "CloudSync.Coordinator"
        private const val TICK_INTERVAL_MS = 5L * 60 * 1000 // 5 minutes
    }

    // -- Observable state -------------------------------------------------

    private val _state = MutableStateFlow<CloudSyncState>(
        if (prefs.cloudSyncEnabled) CloudSyncState.Active else CloudSyncState.Disabled
    )
    val state: StateFlow<CloudSyncState> = _state.asStateFlow()

    private val _lastSyncedAt = MutableStateFlow<Date?>(null)
    val lastSyncedAt: StateFlow<Date?> = _lastSyncedAt.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    // -- Internal state ---------------------------------------------------

    private val tickInFlight = AtomicBoolean(false)
    private var tickTimerJob: Job? = null
    private var started = false

    init {
        if (prefs.cloudSyncEnabled) {
            start()
        }
    }

    // -- Enable -----------------------------------------------------------

    fun enable() {
        val current = _state.value
        if (current is CloudSyncState.Enabling) return
        _state.value = CloudSyncState.Enabling(step = "register")
        _lastError.value = null

        scope.launch {
            try {
                pushRegistration.updateCloudSyncEnabled(true)
                _state.value = CloudSyncState.Enabling(step = "sync")
            } catch (e: Exception) {
                _lastError.value = e.message ?: e::class.java.simpleName
                _state.value = CloudSyncState.Disabled
                Log.w(TAG, "enable failed", e)
                return@launch
            }

            prefs.cloudSyncEnabled = true
            _lastError.value = null
            _state.value = CloudSyncState.Active
            start()
        }
    }

    // -- Disable ----------------------------------------------------------

    fun disable() {
        stop()

        // Local state must flip synchronously, not inside the launch: scope is
        // a viewModelScope, so the coroutine may be cancelled before it runs
        // (toggle off, then leave the screen) or die on a failed server call —
        // either would leave prefs.cloudSyncEnabled true and silently
        // re-activate sync on next launch.
        prefs.cloudSyncEnabled = false
        idMap.clear()
        _lastSyncedAt.value = null
        _lastError.value = null
        _state.value = CloudSyncState.Disabled

        scope.launch {
            runCatching { pushRegistration.updateCloudSyncEnabled(false) }
                .onFailure { Log.w(TAG, "disable: server update failed (non-fatal)", it) }

            outbox.clearAll()
        }
    }

    // -- Sync tick --------------------------------------------------------

    fun syncTick() {
        if (_state.value != CloudSyncState.Active || !tickInFlight.compareAndSet(false, true)) return

        scope.launch {
            try {
                // Pull phase would go here when full-sync pull is wired up.
            } catch (e: PushApiException) {
                if (isAuthError(e)) {
                    _state.value = CloudSyncState.NeedsReauth(reason = "session_revoked")
                    tickInFlight.set(false)
                    return@launch
                }
                if (e.cause is IOException) {
                    tickInFlight.set(false)
                    return@launch
                }
                _lastError.value = e.message
            } catch (e: IOException) {
                tickInFlight.set(false)
                return@launch
            } catch (e: Exception) {
                _lastError.value = e.message ?: e::class.java.simpleName
            }

            if (_state.value != CloudSyncState.Active) {
                tickInFlight.set(false)
                return@launch
            }

            // Drain outbox.
            outbox.drain(idMap) { resolved -> execute(resolved) }

            if (_state.value == CloudSyncState.Active) {
                _lastSyncedAt.value = Date()
            }

            tickInFlight.set(false)
        }
    }

    /** Called by the revision poller when the server revision is ahead. */
    fun onRevisionChanged() {
        if (_state.value != CloudSyncState.Active) return
        scheduleTick()
    }

    /** Called when a sync_trigger push notification arrives. */
    fun onSyncTrigger() {
        if (_state.value != CloudSyncState.Active) return
        scheduleTick()
    }

    // -- Enqueue helpers --------------------------------------------------

    fun enqueueCourseColorOverride(courseNo: String, semester: String, colorHex: String) {
        if (_state.value != CloudSyncState.Active) return
        val op = SyncOp.CourseOverride(
            semester = semester, courseKey = courseNo,
            customName = null, colorHex = colorHex, stamp = Date(),
        )
        scope.launch { outbox.enqueue(op) }
    }

    fun enqueueCourseNameOverride(courseNo: String, semester: String, customName: String) {
        if (_state.value != CloudSyncState.Active) return
        val op = SyncOp.CourseOverride(
            semester = semester, courseKey = courseNo,
            customName = customName, colorHex = null, stamp = Date(),
        )
        scope.launch { outbox.enqueue(op) }
    }

    fun enqueueAssignmentOverride(moodleCourseId: Int, moodleAssignmentId: Int, localStatus: String) {
        if (_state.value != CloudSyncState.Active) return
        val op = SyncOp.AssignmentOverride(
            moodleCourseId = moodleCourseId,
            moodleAssignmentId = moodleAssignmentId,
            localStatus = localStatus, stamp = Date(),
        )
        scope.launch { outbox.enqueue(op) }
    }

    fun enqueueUploadSnapshot() {
        if (_state.value != CloudSyncState.Active) return
        scope.launch { outbox.enqueue(SyncOp.UploadSnapshot) }
    }

    // -- Execute resolved op ----------------------------------------------

    private suspend fun execute(op: ResolvedSyncOp) {
        try {
            when (op) {
                is ResolvedSyncOp.CourseOverride -> {
                    pushApiClient.patchCourseOverride(
                        courseId = op.courseId,
                        colorHex = op.colorHex,
                        customName = op.customName,
                        locale = op.locale,
                    )
                }

                is ResolvedSyncOp.AssignmentOverride -> {
                    pushApiClient.patchAssignmentOverride(
                        assignmentId = op.assignmentId,
                        localStatus = op.localStatus,
                    )
                }

                is ResolvedSyncOp.UploadSnapshot -> {
                    Log.d(TAG, "UploadSnapshot op executed — caller should trigger upload")
                }
            }
        } catch (e: PushApiException) {
            if (e.message?.contains("401") == true) throw SyncOutboxAuthException(401)
            throw e
        }
    }

    // -- Timer ------------------------------------------------------------

    fun scheduleTick(delayMs: Long = 0) {
        scope.launch {
            if (delayMs > 0) delay(delayMs)
            syncTick()
        }
    }

    private fun start() {
        if (started) return
        started = true
        tickTimerJob = scope.launch {
            while (true) {
                delay(TICK_INTERVAL_MS)
                syncTick()
            }
        }
    }

    private fun stop() {
        tickTimerJob?.cancel()
        tickTimerJob = null
        started = false
    }

    // -- Helpers ----------------------------------------------------------

    private fun isAuthError(e: PushApiException): Boolean {
        val msg = e.message ?: return false
        return msg.contains("HTTP 401")
    }
}
