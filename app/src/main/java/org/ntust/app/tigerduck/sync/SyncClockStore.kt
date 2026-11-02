package org.ntust.app.tigerduck.sync

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * [SyncClock]'s state on disk, `tigerduck_sync_clock`. Device scope: the clock spec keeps it across
 * a sign-out ("The clock state survives relaunch"), as the backend's time and the stamps this
 * device issued are no account's, and a full reset leaves it too. Absent keys read as nothing
 * heard or issued, which is a build before the clock's.
 *
 * Written with `apply()`: a write a process death loses can make a stamp repeat one issued before
 * it only if the wall clock has gone back as well, and the next header heard sets the cap again.
 */
class SyncClockStore(private val prefs: SharedPreferences) {
    @Inject
    constructor(@ApplicationContext context: Context) :
        this(context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE))

    internal fun load(): SyncClockState = SyncClockState(
        lastIssuedMs = prefs.getLong(KEY_LAST_ISSUED_MS, 0L),
        lastSeenUs = prefs.getLong(KEY_LAST_SEEN_US, 0L),
        heardUs = if (prefs.contains(KEY_HEARD_US)) prefs.getLong(KEY_HEARD_US, 0L) else null,
        heardAtMs = prefs.getLong(KEY_HEARD_AT_MS, 0L),
    )

    internal fun save(state: SyncClockState) {
        val edit = prefs.edit()
            .putLong(KEY_LAST_ISSUED_MS, state.lastIssuedMs)
            .putLong(KEY_LAST_SEEN_US, state.lastSeenUs)
            .putLong(KEY_HEARD_AT_MS, state.heardAtMs)
        if (state.heardUs != null) edit.putLong(KEY_HEARD_US, state.heardUs) else edit.remove(KEY_HEARD_US)
        edit.apply()
    }

    private companion object {
        const val PREFS_FILE = "tigerduck_sync_clock"
        const val KEY_LAST_ISSUED_MS = "lastIssuedMs"
        const val KEY_LAST_SEEN_US = "lastSeenUs"
        const val KEY_HEARD_US = "heardUs"
        const val KEY_HEARD_AT_MS = "heardAtMs"
    }
}
