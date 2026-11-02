package org.ntust.app.tigerduck.sync.outbox

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.di.IoDispatcher
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
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val wallClock: () -> Long,
) {
    /** Built as the process starts, as `TigerDuckApp`'s field, so the first run is recorded before anything writes a queue. */
    @Inject
    constructor(@ApplicationContext context: Context, @IoDispatcher io: CoroutineDispatcher) :
        this(context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE), io, System::currentTimeMillis) {
        recordFirstRun()
    }

    private val lock = Any()

    // Under lock: the first run, once read or recorded; it never changes after.
    private var known: Long? = null

    // Under lock: whether the disk has yet to take the first run this process recorded (a full
    // disk). Each later call writes it again until one write lands, so the next start reads this
    // build's first run rather than recording its own, later one, which an untimed entry would
    // then take, putting a stale edit after another device's newer one.
    private var owesDisk = false

    /** When this build first ran, epoch ms; null until [recordFirstRun] has. */
    val firstRunMs: Long?
        get() = synchronized(lock) { knownOrStored() }

    /**
     * Records now as this build's first run, unless one is recorded; on disk before it returns, or
     * owed. On the caller's thread: it runs once, as the process starts, before anything can write a
     * queue, and that order counts for more than the thread.
     */
    internal fun recordFirstRun() {
        synchronized(lock) {
            if (knownOrStored() == null) {
                known = wallClock()
                owesDisk = true
            }
            payDisk()
        }
    }

    /**
     * The old-entry time of an entry in [file], read before this build writes it; null [file] for a
     * store whose time is not the entries' own, as a preferences file other edits write, where only
     * the first run counts. The file's time and a first run still owed to the disk are disk work, done
     * on [io]: a queue reads this as a tap or a page's load reads it, on the main thread.
     */
    suspend fun oldEntryTimeMs(file: File?): Long = withContext(io) {
        val fileMs = file?.lastModified()?.takeIf { it > 0 }
        val firstRun = synchronized(lock) {
            payDisk()
            knownOrStored()
        }
        val earliest = listOfNotNull(fileMs, firstRun).minOrNull() ?: return@withContext 0L
        // A clock set ahead when either was written must not put an old edit after a new one.
        minOf(earliest, wallClock())
    }

    // Under lock.
    private fun knownOrStored(): Long? =
        known ?: prefs.getLong(KEY_FIRST_RUN_MS, 0L).takeIf { it > 0 }?.also { known = it }

    // Under lock: the first run the disk still owes, written again; it stays owed if this write fails too.
    private fun payDisk() {
        val owed = known?.takeIf { owesDisk } ?: return
        owesDisk = !prefs.edit().putLong(KEY_FIRST_RUN_MS, owed).commit()
        if (owesDisk) Log.w(TAG, "the first run was not written to disk; the next call tries again")
    }

    private companion object {
        const val TAG = "OldEntryTime"
        const val PREFS_FILE = "tigerduck_outbox"
        const val KEY_FIRST_RUN_MS = "firstRunMs"
    }
}
