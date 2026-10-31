package org.ntust.app.tigerduck.data.cache

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.network.model.BulletinDetail
import org.ntust.app.tigerduck.network.model.BulletinSummary
import org.ntust.app.tigerduck.util.logSummary
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Disk-backed cache for the announcements list. Persists the merged summary
 * snapshot under filesDir so the user sees their last known list immediately
 * on cold start, before any network call resolves. Mirrors DataCache.swift's
 * BulletinsCache contract on iOS.
 */
@Singleton
class BulletinCache @Inject constructor(@ApplicationContext context: Context) {

    private val dir: File = File(context.filesDir, "bulletins").also { it.mkdirs() }
    private val file = File(dir, "summaries.json")
    private val fetchedAtFile = File(dir, "fetched_at")
    private val detailDir: File = File(dir, "details").also { it.mkdirs() }
    private val mutex = Mutex()

    // The last clear [clearIn] started, after any before it, which every read and write waits
    // for: started, the files it clears are no longer this cache's, even before they are gone.
    @Volatile
    private var clearing: Deferred<Unit>? = null

    // Written as [clearIn] starts a clear and removed once everything it clears is gone: a
    // process ended in between clears at the next start, and while a delete fails nothing is
    // read from the files.
    private val clearOwed = File(dir, "clear_owed")

    init {
        if (clearOwed.exists() && deleteList() && deleteDetails { _, delete -> delete() }) {
            runCatching { clearOwed.delete() }
        }
    }

    // Refcounted per-id locks: entries are removed only when no coroutine is
    // holding or waiting on them, so two callers for the same id always share
    // the same Mutex instance. A naive LRU could evict an in-use entry between
    // the lookup and withLock, letting two coroutines race the same file.
    private class LockEntry(val mutex: Mutex = Mutex(), var refs: Int = 0)

    private val detailLocks = HashMap<Int, LockEntry>()
    private val gson = Gson()
    private val listType = object : TypeToken<List<BulletinSummary>>() {}.type

    private fun acquireDetailLock(id: Int): LockEntry = synchronized(detailLocks) {
        detailLocks.getOrPut(id) { LockEntry() }.also { it.refs++ }
    }

    private fun releaseDetailLock(id: Int) = synchronized(detailLocks) {
        val entry = detailLocks[id] ?: return@synchronized
        if (--entry.refs <= 0) detailLocks.remove(id)
    }

    private suspend fun <T> withDetailLock(id: Int, block: suspend () -> T): T {
        val entry = acquireDetailLock(id)
        try {
            return entry.mutex.withLock { block() }
        } finally {
            releaseDetailLock(id)
        }
    }

    suspend fun load(): List<BulletinSummary> {
        settled()
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    if (!file.exists() || clearOwed.exists()) emptyList()
                    else gson.fromJson<List<BulletinSummary>>(file.readText(), listType) ?: emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }
    }

    suspend fun save(items: List<BulletinSummary>) {
        settled()
        mutex.withLock {
            withContext(Dispatchers.IO) {
                writeAtomically(file, gson.toJson(items))
            }
        }
    }

    /**
     * When a refresh last brought the list's first page from the server,
     * wall clock ms; 0 for never. Not a later page, nor the cached list a
     * visit opens on. The page's "Last synced" row, and it goes with the
     * list in [clear].
     */
    suspend fun loadFetchedAt(): Long {
        settled()
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    if (!fetchedAtFile.exists() || clearOwed.exists()) 0L else fetchedAtFile.readText().trim().toLong()
                } catch (_: Exception) {
                    0L
                }
            }
        }
    }

    suspend fun saveFetchedAt(atMs: Long) {
        settled()
        mutex.withLock {
            withContext(Dispatchers.IO) {
                writeAtomically(fetchedAtFile, atMs.toString())
            }
        }
    }

    /**
     * Saves [items], and the list's date when [fetchedAt] is given, unless [stillCurrent] says
     * the account or backend they were fetched for is gone: a sign-out, or an API endpoint move.
     * Asked under the cache's lock, which [clear] takes too -- and each of them moves what
     * [stillCurrent] reads before it starts the clear -- so the answer cannot be overtaken between
     * the asking and the write: either this write lands first and the clear removes it, or the
     * clear has run and nothing is written. The same shape as
     * [org.ntust.app.tigerduck.mail.store.MailCache.saveBodyIf]. Returns whether it wrote.
     */
    suspend fun saveIf(items: List<BulletinSummary>, fetchedAt: Long?, stillCurrent: () -> Boolean): Boolean {
        settled()
        return mutex.withLock {
            if (!stillCurrent()) return@withLock false
            withContext(Dispatchers.IO) {
                writeAtomically(file, gson.toJson(items))
                if (fetchedAt != null) writeAtomically(fetchedAtFile, fetchedAt.toString())
            }
            true
        }
    }

    /** One JSON file per id mirrors iOS DataCache.bulletinDetailDir(); keeps
     *  writes small and avoids rewriting an aggregated file on every open. */
    suspend fun loadDetail(id: Int): BulletinDetail? = withContext(Dispatchers.IO) {
        settled()
        withDetailLock(id) {
            val f = File(detailDir, "$id.json")
            try {
                if (f.exists() && !clearOwed.exists()) gson.fromJson(f.readText(), BulletinDetail::class.java) else null
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Saves [detail] unless [stillCurrent] says the backend it came from is gone, asked under the
     * id's lock, which [clear] takes for the id too after the move that [stillCurrent] reads:
     * either this write lands first and the clear removes it, or nothing is written.
     */
    suspend fun saveDetail(detail: BulletinDetail, stillCurrent: () -> Boolean = { true }) = withContext(Dispatchers.IO) {
        settled()
        withDetailLock(detail.id) {
            if (!stillCurrent()) return@withDetailLock
            writeAtomically(File(detailDir, "${detail.id}.json"), gson.toJson(detail))
        }
    }

    /** Delete detail files for ids no longer in [keep]. Called when the cursor
     *  chain is exhausted so we know [keep] is the complete known set, mirroring
     *  [BulletinReadStateStore.prune]. Per-id locks prevent racing a concurrent
     *  saveDetail/loadDetail for the same id. */
    suspend fun pruneDetails(keep: Set<Int>) = withContext(Dispatchers.IO) {
        val files = detailDir.listFiles() ?: return@withContext
        for (f in files) {
            val id = f.nameWithoutExtension.toIntOrNull() ?: continue
            if (id in keep) continue
            withDetailLock(id) {
                // Re-check existence under the lock — saveDetail may have just
                // (re)created the file for an id that's about to be re-added.
                if (f.exists() && id !in keep) f.delete()
            }
        }
    }

    /** Wipe the whole bulletins store (summary snapshot + every cached detail).
     *  Called on logout so the next session's first paint can't seed from the
     *  previous session's snapshot — mirrors DataCache.clearAllUserData().
     *
     *  The summary file deletion is mutex-guarded; detail file deletion happens
     *  outside the mutex (using per-id detail locks) to avoid holding `mutex`
     *  while iterating an unbounded number of files — the per-id `withDetailLock`
     *  tries to acquire its own mutex, and holding the outer `mutex` across the
     *  loop could deadlock if another coroutine holds a detail lock while
     *  waiting on `mutex`. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        val listGone = mutex.withLock { deleteList() }
        // The ids with a save or a read under way, whose file may not exist yet: a save that
        // asked whether to write before the move writes after this looks, so each is waited for.
        val underWay = synchronized(detailLocks) { detailLocks.keys.toList() }
        // Detail file deletion is intentionally outside the mutex lock.
        val detailsGone = deleteDetails(underWay) { id, delete -> withDetailLock(id) { delete() } }
        // Last, once everything is gone: a process ended before this clears again at its next start.
        if (listGone && detailsGone) runCatching { clearOwed.delete() }
    }

    /**
     * Deletes every detail file, and the file of each id in [underWay], each through [locked] with
     * its id, and says whether none is left. A stray non-detail file (an orphaned .tmp, say) goes
     * without a lock, best effort.
     */
    private inline fun deleteDetails(underWay: List<Int> = emptyList(), locked: (Int, () -> Boolean) -> Boolean): Boolean {
        val files = detailDir.listFiles().orEmpty().toList()
        var gone = true
        val listed = mutableSetOf<Int>()
        for (f in files) {
            val id = f.nameWithoutExtension.toIntOrNull()
            if (id == null) {
                runCatching { f.delete() }
                continue
            }
            listed += id
            if (!locked(id) { !f.exists() || runCatching { f.delete() }.getOrDefault(false) }) gone = false
        }
        for (id in underWay) {
            if (id in listed) continue
            val f = File(detailDir, "$id.json")
            if (!locked(id) { !f.exists() || runCatching { f.delete() }.getOrDefault(false) }) gone = false
        }
        return gone
    }

    /**
     * Starts [clear] in [scope], for a sign-out or an API endpoint move: every read and write from
     * now on waits for it, so none reads or writes the files it clears in the meantime.
     */
    fun clearIn(scope: CoroutineScope) {
        runCatching { clearOwed.createNewFile() }
        synchronized(this) {
            // After the one before, so it never removes what is written after this one.
            val before = clearing
            clearing = scope.async {
                before?.await()
                runCatching { clear() }
                    .onFailure { Log.w(TAG, "clear failed: ${it.logSummary()}") }
                Unit
            }
        }
    }

    // The list and its date, and whether they are gone; under [mutex] but at construction.
    private fun deleteList(): Boolean {
        val targets = listOf(file, fetchedAtFile, temporary(file), temporary(fetchedAtFile))
        // A write a process death cut short leaves its temporary file beside them.
        targets.forEach { runCatching { it.delete() } }
        return targets.none { it.exists() }
    }

    // Waits for the clear an endpoint move started, if one is still running.
    private suspend fun settled() {
        clearing?.await()
    }

    /** Where [writeAtomically] writes [target] before it renames it into place. */
    private fun temporary(target: File) = File(target.parentFile, "${target.name}.tmp")

    /** Truncating writeText leaves a partial file if the process dies mid-write,
     *  which fails JSON parse on next launch and discards the whole cache. */
    private fun writeAtomically(target: File, content: String) {
        val tmp = temporary(target)
        try {
            tmp.writeText(content)
            if (!tmp.renameTo(target)) {
                Log.w(TAG, "atomic write failed: rename ${tmp.name} -> ${target.name}")
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "atomic write failed for ${target.name}: ${e.logSummary()}")
            tmp.delete()
        }
    }

    private companion object {
        const val TAG = "BulletinCache"
    }
}
