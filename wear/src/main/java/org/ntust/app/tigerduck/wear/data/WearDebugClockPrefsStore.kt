package org.ntust.app.tigerduck.wear.data

import android.content.Context
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import org.ntust.app.tigerduck.shared.clock.ClockOverrideStore

class WearDebugClockPrefsStore(context: Context) : ClockOverrideStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

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
