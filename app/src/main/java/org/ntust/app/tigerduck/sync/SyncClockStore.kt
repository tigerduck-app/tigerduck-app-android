package org.ntust.app.tigerduck.sync

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * [SyncClock]'s state on disk, `tigerduck_sync_clock`. Device scope: the clock spec keeps it across
 * a sign-out ("The clock state survives relaunch"), as the backend's time and the stamps this
 * device issued are no account's, and a full reset leaves it too. Absent keys read as nothing
 * heard or issued, which is a build before the clock's; so do values past any plausible time
 * ([isPlausibleClockUs]), which only a broken file holds. The backend the values are of
 * (`backendKey`) is absent from a file a build before it wrote, whose values are taken as the
 * backend the endpoint names.
 *
 * A write is [save]d with `commit()` when its value is more than
 * [SyncClockState.DURABLE_LEAD_MS] ahead of the wall clock, and with `apply()` otherwise. A lost
 * write of a value nearer than that costs nothing: by the next launch the wall clock is past it,
 * unless the wall clock has gone back as well. One further ahead, lost, would let the next launch
 * stamp at or below a stamp already issued, or below a backend time already seen, which happens
 * only while the backend's clock or the last stamp runs that far ahead.
 */
class SyncClockStore(private val prefs: SharedPreferences) {
    @Inject
    constructor(@ApplicationContext context: Context) :
        this(context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE))

    internal fun load(): SyncClockState = SyncClockState(
        lastIssuedMs = prefs.getLong(KEY_LAST_ISSUED_MS, 0L).takeIf { it in 0..LATEST_CLOCK_US / 1_000 } ?: 0L,
        lastSeenUs = prefs.getLong(KEY_LAST_SEEN_US, 0L).takeIf(::isPlausibleClockUs) ?: 0L,
        heardUs = if (prefs.contains(KEY_HEARD_US)) prefs.getLong(KEY_HEARD_US, 0L).takeIf(::isPlausibleClockUs) else null,
        heardAtMs = prefs.getLong(KEY_HEARD_AT_MS, 0L),
        backendKey = if (prefs.contains(KEY_BACKEND)) runCatching { prefs.getString(KEY_BACKEND, null) }.getOrNull() else null,
    )

    // Under the clock's lock: a write that had to reach the disk and didn't (a full disk). The
    // clock can't refuse the stamp without losing the student's edit, so it goes on, and every
    // later write is committed until one lands, carrying the value the failed one held.
    private var owesDisk = false

    /** Writes [state], on disk before it returns when [durable], or while an earlier one failed to. */
    internal fun save(state: SyncClockState, durable: Boolean) {
        val edit = prefs.edit()
            .putLong(KEY_LAST_ISSUED_MS, state.lastIssuedMs)
            .putLong(KEY_LAST_SEEN_US, state.lastSeenUs)
            .putLong(KEY_HEARD_AT_MS, state.heardAtMs)
        if (state.heardUs != null) edit.putLong(KEY_HEARD_US, state.heardUs) else edit.remove(KEY_HEARD_US)
        if (state.backendKey != null) edit.putString(KEY_BACKEND, state.backendKey) else edit.remove(KEY_BACKEND)
        if (!durable && !owesDisk) {
            edit.apply()
            return
        }
        owesDisk = !edit.commit()
        if (owesDisk) Log.w(TAG, "the sync clock was not written to disk; the next write tries again")
    }

    private companion object {
        const val TAG = "SyncClockStore"
        const val PREFS_FILE = "tigerduck_sync_clock"
        const val KEY_LAST_ISSUED_MS = "lastIssuedMs"
        const val KEY_LAST_SEEN_US = "lastSeenUs"
        const val KEY_HEARD_US = "heardUs"
        const val KEY_HEARD_AT_MS = "heardAtMs"
        const val KEY_BACKEND = "backendKey"
    }
}
