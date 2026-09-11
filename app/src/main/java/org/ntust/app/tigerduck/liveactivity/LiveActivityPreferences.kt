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
class LiveActivityPreferences internal constructor(
    private val prefs: SharedPreferences,
) {
    // The real entry point: Hilt calls this (the @Inject constructor), which
    // resolves the SharedPreferences and delegates to the primary
    // constructor above. Splitting it this way lets tests hand in an
    // in-memory SharedPreferences fake directly — the project has no
    // Robolectric dependency, so a real Context isn't available in a plain
    // JVM unit test.
    @Inject constructor(@ApplicationContext context: Context) : this(
        context.getSharedPreferences("tigerduck_live_activity", Context.MODE_PRIVATE)
    )

    private val _changeEvent =
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
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
        get() = readClampedLong(
            KEY_ASSIGNMENT_LEAD, DEFAULT_ASSIGNMENT_LEAD_SEC,
            MIN_ASSIGNMENT_LEAD_SEC, MAX_ASSIGNMENT_LEAD_SEC,
        )
        set(value) = writeLong(
            KEY_ASSIGNMENT_LEAD,
            value.coerceIn(MIN_ASSIGNMENT_LEAD_SEC, MAX_ASSIGNMENT_LEAD_SEC)
        )

    /** Seconds before class start when the "即將上課" scenario activates. */
    var classPreparingLeadTimeSec: Long
        get() = readClampedLong(
            KEY_CLASS_LEAD, DEFAULT_CLASS_LEAD_SEC,
            MIN_CLASS_LEAD_SEC, MAX_CLASS_LEAD_SEC,
        )
        set(value) = writeLong(
            KEY_CLASS_LEAD,
            value.coerceIn(MIN_CLASS_LEAD_SEC, MAX_CLASS_LEAD_SEC)
        )

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

    /**
     * Reads [key] and clamps it into [min]..[max], the way
     * `LiveActivityPreferencesStore.init()` clamps on load on iOS
     * (`LiveActivityPreferencesStore.swift:116-125`).
     *
     * Needed because the MIN_ and MAX_ constants narrowed in v2.1.0 when the
     * 自訂 escape hatch was removed — assignment lead time used to allow 5 min..7 days,
     * class-preparing 1 min..3 h. An install that persisted a value only
     * reachable through 自訂 (e.g. "1 day before") would otherwise hold a
     * value the new slider can neither display nor produce, forever, since
     * nothing else in this class rewrites an existing value.
     *
     * The clamped value is written straight back rather than only clamping
     * what's returned, for two reasons: so anything that syncs this raw pref
     * (e.g. a cloud copy) doesn't disagree with what the slider displays,
     * and so the clamp only runs once per stale value instead of on every
     * single read.
     */
    private fun readClampedLong(key: String, default: Long, min: Long, max: Long): Long {
        val raw = prefs.getLong(key, default)
        val clamped = raw.coerceIn(min, max)
        if (clamped != raw) writeLong(key, clamped)
        return clamped
    }

    companion object {
        const val DEFAULT_ASSIGNMENT_LEAD_SEC = 8L * 3600
        const val DEFAULT_CLASS_LEAD_SEC = 15L * 60

        // v2.1.0: narrowed to match the iOS slider ranges when the Android
        // 自訂 dialogs were removed (spec §5 W6). See readClampedLong's KDoc
        // for why existing out-of-range values need clamping, not just a UI
        // change, and grep these four constants before touching them again —
        // ClassPreparingNotificationScheduler, LiveActivityResolver and
        // LiveActivityManager all schedule/resolve off the *properties*
        // above (which apply these bounds), not off these constants
        // directly, so they pick up a changed range for free — but any new
        // caller that hardcodes an assumption about the old range wouldn't.
        const val MIN_ASSIGNMENT_LEAD_SEC = 1L * 3600         // 1 hour floor
        const val MAX_ASSIGNMENT_LEAD_SEC = 8L * 3600         // 8 hour ceiling
        const val MIN_CLASS_LEAD_SEC = 5L * 60                // 5 min floor
        const val MAX_CLASS_LEAD_SEC = 4L * 3600              // 4 hour ceiling — matches iOS maximumClassPreparingLeadTime

        private const val KEY_ENABLED = "enabled"
        private const val KEY_SHOW_IN_CLASS = "show_in_class"
        private const val KEY_SHOW_CLASS_PREPARING = "show_class_preparing"
        private const val KEY_SHOW_ASSIGNMENT = "show_assignment"
        private const val KEY_SHOW_ON_LOCK_SCREEN = "show_on_lock_screen"
        private const val KEY_SOUND_IN_CLASS = "sound_in_class"
        private const val KEY_SOUND_CLASS_PREPARING = "sound_class_preparing"
        private const val KEY_SOUND_ASSIGNMENT = "sound_assignment"

        // Not private: LiveActivityPreferencesTest seeds a fake
        // SharedPreferences directly under these keys to simulate a value
        // persisted by an older build, without duplicating the literal
        // strings (which must stay byte-for-byte identical to what a real
        // prior install wrote to disk).
        internal const val KEY_ASSIGNMENT_LEAD = "assignment_lead_sec"
        internal const val KEY_CLASS_LEAD = "class_lead_sec"
    }
}
