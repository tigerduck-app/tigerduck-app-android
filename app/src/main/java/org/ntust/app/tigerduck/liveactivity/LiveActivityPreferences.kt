package org.ntust.app.tigerduck.liveactivity

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Preferences for the Android Live Update feature — the dynamic-island-style
 * ongoing notification that mirrors the iOS Live Activity.
 *
 * Defaults intentionally match the iOS target so behavior stays consistent
 * between platforms.
 */
@Singleton
class LiveActivityPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("tigerduck_live_activity", Context.MODE_PRIVATE)

    private val _changeEvent =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changeEvent: SharedFlow<Unit> = _changeEvent.asSharedFlow()

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = writeBool(KEY_ENABLED, value)

    var showInClass: Boolean
        get() = prefs.getBoolean(KEY_SHOW_IN_CLASS, true)
        set(value) = writeBool(KEY_SHOW_IN_CLASS, value)

    var showClassPreparing: Boolean
        get() = prefs.getBoolean(KEY_SHOW_CLASS_PREPARING, true)
        set(value) = writeBool(KEY_SHOW_CLASS_PREPARING, value)

    var showAssignment: Boolean
        get() = prefs.getBoolean(KEY_SHOW_ASSIGNMENT, true)
        set(value) = writeBool(KEY_SHOW_ASSIGNMENT, value)

    /** Seconds before an assignment due date when the Live Update starts showing. Capped at 8h. */
    var assignmentLeadTimeSec: Long
        get() = prefs.getLong(KEY_ASSIGNMENT_LEAD, DEFAULT_ASSIGNMENT_LEAD_SEC)
        set(value) = writeLong(KEY_ASSIGNMENT_LEAD, value.coerceIn(3600L, MAX_ASSIGNMENT_LEAD_SEC))

    /** Seconds before class start when the "即將上課" scenario activates. */
    var classPreparingLeadTimeSec: Long
        get() = prefs.getLong(KEY_CLASS_LEAD, DEFAULT_CLASS_LEAD_SEC)
        set(value) = writeLong(KEY_CLASS_LEAD, value.coerceIn(300L, 3600L))

    fun resetToDefaults() {
        prefs.edit().clear().apply()
        _changeEvent.tryEmit(Unit)
    }

    private fun writeBool(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
        _changeEvent.tryEmit(Unit)
    }

    private fun writeLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
        _changeEvent.tryEmit(Unit)
    }

    companion object {
        const val DEFAULT_ASSIGNMENT_LEAD_SEC = 8L * 3600
        const val MAX_ASSIGNMENT_LEAD_SEC = 8L * 3600
        const val DEFAULT_CLASS_LEAD_SEC = 15L * 60

        private const val KEY_ENABLED = "enabled"
        private const val KEY_SHOW_IN_CLASS = "show_in_class"
        private const val KEY_SHOW_CLASS_PREPARING = "show_class_preparing"
        private const val KEY_SHOW_ASSIGNMENT = "show_assignment"
        private const val KEY_ASSIGNMENT_LEAD = "assignment_lead_sec"
        private const val KEY_CLASS_LEAD = "class_lead_sec"
    }
}
