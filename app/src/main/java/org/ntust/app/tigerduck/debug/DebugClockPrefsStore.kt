package org.ntust.app.tigerduck.debug

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import org.ntust.app.tigerduck.shared.clock.ClockOverrideStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phone-side persistence for the debug clock override. Backed by a dedicated
 * SharedPreferences file so it never leaks into AppPreferences migrations.
 */
@Singleton
class DebugClockPrefsStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ClockOverrideStore {

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    }

    override fun load(): ClockOverride? {
        if (!prefs.contains(KEY_INSTANT)) return null
        return ClockOverride(
            instantMillis = prefs.getLong(KEY_INSTANT, 0L),
            frozen = prefs.getBoolean(KEY_FROZEN, true),
            savedAtRealMillis = prefs.getLong(KEY_SAVED_AT, 0L),
        )
    }

    override fun save(override: ClockOverride?) {
        prefs.edit().apply {
            if (override == null) {
                clear()
            } else {
                putLong(KEY_INSTANT, override.instantMillis)
                putBoolean(KEY_FROZEN, override.frozen)
                putLong(KEY_SAVED_AT, override.savedAtRealMillis)
            }
        }.apply()
    }

    private companion object {
        const val PREFS_FILE = "debug_clock"
        const val KEY_INSTANT = "instant_millis"
        const val KEY_FROZEN = "frozen"
        const val KEY_SAVED_AT = "saved_at_real_millis"
    }
}
