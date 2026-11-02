package org.ntust.app.tigerduck.sync.outbox

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The time an edit an older build queued with no time is sent with (X1, xinshou 2026-11-02): never
 * its send time, which on `/v4` would let a stale edit beat a newer one made on another device (a
 * mark queued Monday, cleared on an iPhone Tuesday, sent Wednesday, would come back), but the
 * earliest moment it is known to have existed. That is the time its queue file was last written,
 * if an older build wrote it last, else the time this build first ran: `min(file time, firstRunMs)`,
 * no later than now. With neither known, 0, the oldest stamp there is (tigersync-merge, "Existing
 * device data counts as oldest", LWW D13).
 *
 * The first run is recorded as the process starts, before anything can write a queue; this build
 * gives every edit a time, so an entry with none is an older build's, older than this build's first
 * run. `tigerduck_outbox` holds it, device scope: it is no account's, and outlives a sign-out.
 */
@Singleton
class OldEntryTime internal constructor(
    private val prefs: SharedPreferences,
    private val wallClock: () -> Long,
) {
    /** Built as the process starts, as `TigerDuckApp`'s field, so the first run is recorded before anything writes a queue. */
    @Inject
    constructor(@ApplicationContext context: Context) :
        this(context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE), System::currentTimeMillis) {
        recordFirstRun()
    }

    private val lock = Any()

    // Under lock once known: it never changes after.
    @Volatile
    private var known: Long? = null

    /** When this build first ran, epoch ms; null until [recordFirstRun] has. */
    val firstRunMs: Long?
        get() = known ?: synchronized(lock) {
            known ?: prefs.getLong(KEY_FIRST_RUN_MS, 0L).takeIf { it > 0 }?.also { known = it }
        }

    /** Records now as this build's first run, unless one is recorded; on disk before it returns. */
    internal fun recordFirstRun() {
        synchronized(lock) {
            if (firstRunMs != null) return
            val now = wallClock()
            // Lost to a full disk, the next start records a later time, which bounds the same
            // entries all the same, if less closely.
            if (!prefs.edit().putLong(KEY_FIRST_RUN_MS, now).commit()) Log.w(TAG, "the first run was not written to disk")
            known = now
        }
    }

    /**
     * The old-entry time of an entry in [file], read before this build writes it; null [file] for a
     * store whose time is not the entries' own, as a preferences file other edits write, where only
     * the first run counts.
     */
    fun oldEntryTimeMs(file: File?): Long {
        val fileMs = file?.lastModified()?.takeIf { it > 0 }
        val earliest = listOfNotNull(fileMs, firstRunMs).minOrNull() ?: return 0L
        // A clock set ahead when either was written must not put an old edit after a new one.
        return minOf(earliest, wallClock())
    }

    private companion object {
        const val TAG = "OldEntryTime"
        const val PREFS_FILE = "tigerduck_outbox"
        const val KEY_FIRST_RUN_MS = "firstRunMs"
    }
}
