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

    /** If true, the live-update notification shows its full content on the lock screen. */
    var showOnLockScreen: Boolean
        get() = prefs.getBoolean(KEY_SHOW_ON_LOCK_SCREEN, false)
        set(value) = writeBool(KEY_SHOW_ON_LOCK_SCREEN, value)

    /** Chip makes a sound when the scenario first transitions into 上課中. Default off. */
    var soundInClass: Boolean
        get() = prefs.getBoolean(KEY_SOUND_IN_CLASS, false)
        set(value) = writeBool(KEY_SOUND_IN_CLASS, value)

    /** Chip makes a sound when the scenario first transitions into 即將上課. Default on. */
    var soundClassPreparing: Boolean
        get() = prefs.getBoolean(KEY_SOUND_CLASS_PREPARING, true)
        set(value) = writeBool(KEY_SOUND_CLASS_PREPARING, value)

    /** Chip makes a sound when the scenario first transitions into 作業警告. Default on. */
    var soundAssignment: Boolean
        get() = prefs.getBoolean(KEY_SOUND_ASSIGNMENT, true)
        set(value) = writeBool(KEY_SOUND_ASSIGNMENT, value)

    /** Seconds before an assignment due date when the Live Update starts showing. */
    var assignmentLeadTimeSec: Long
        get() = prefs.getLong(KEY_ASSIGNMENT_LEAD, DEFAULT_ASSIGNMENT_LEAD_SEC)
        set(value) = writeLong(KEY_ASSIGNMENT_LEAD, value.coerceIn(MIN_ASSIGNMENT_LEAD_SEC, MAX_ASSIGNMENT_LEAD_SEC))

    /** Seconds before class start when the "即將上課" scenario activates. */
    var classPreparingLeadTimeSec: Long
        get() = prefs.getLong(KEY_CLASS_LEAD, DEFAULT_CLASS_LEAD_SEC)
        set(value) = writeLong(KEY_CLASS_LEAD, value.coerceIn(MIN_CLASS_LEAD_SEC, MAX_CLASS_LEAD_SEC))

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
        const val DEFAULT_CLASS_LEAD_SEC = 15L * 60
        const val MIN_ASSIGNMENT_LEAD_SEC = 5L * 60          // 5 min floor
        const val MAX_ASSIGNMENT_LEAD_SEC = 7L * 24 * 3600    // 7 days ceiling
        const val MIN_CLASS_LEAD_SEC = 60L                   // 1 min floor
        const val MAX_CLASS_LEAD_SEC = 3L * 3600              // 3 hours ceiling

        private const val KEY_ENABLED = "enabled"
        private const val KEY_SHOW_IN_CLASS = "show_in_class"
        private const val KEY_SHOW_CLASS_PREPARING = "show_class_preparing"
        private const val KEY_SHOW_ASSIGNMENT = "show_assignment"
        private const val KEY_SHOW_ON_LOCK_SCREEN = "show_on_lock_screen"
        private const val KEY_SOUND_IN_CLASS = "sound_in_class"
        private const val KEY_SOUND_CLASS_PREPARING = "sound_class_preparing"
        private const val KEY_SOUND_ASSIGNMENT = "sound_assignment"
        private const val KEY_ASSIGNMENT_LEAD = "assignment_lead_sec"
        private const val KEY_CLASS_LEAD = "class_lead_sec"
    }
}
