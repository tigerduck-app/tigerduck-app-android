package org.ntust.app.tigerduck.shared.clock

/**
 * Persistence for [ClockOverride]. Phone and watch each provide their own
 * SharedPreferences-backed implementation against a dedicated debug_clock.xml
 * file (kept separate from other prefs so it never leaks into migrations).
 */
interface ClockOverrideStore {
    fun load(): ClockOverride?
    fun save(override: ClockOverride?)
}
