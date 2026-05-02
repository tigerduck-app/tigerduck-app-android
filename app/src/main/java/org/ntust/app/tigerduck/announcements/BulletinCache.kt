package org.ntust.app.tigerduck.announcements

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    private val detailDir: File = File(dir, "details").also { it.mkdirs() }
    private val mutex = Mutex()
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

    suspend fun load(): List<BulletinSummary> = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                if (!file.exists()) emptyList()
                else gson.fromJson<List<BulletinSummary>>(file.readText(), listType) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    suspend fun save(items: List<BulletinSummary>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            writeAtomically(file, gson.toJson(items))
        }
    }

    /** One JSON file per id mirrors iOS DataCache.bulletinDetailDir(); keeps
     *  writes small and avoids rewriting an aggregated file on every open. */
    suspend fun loadDetail(id: Int): BulletinDetail? = withContext(Dispatchers.IO) {
        withDetailLock(id) {
            val f = File(detailDir, "$id.json")
            try {
                if (f.exists()) gson.fromJson(f.readText(), BulletinDetail::class.java) else null
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun saveDetail(detail: BulletinDetail) = withContext(Dispatchers.IO) {
        withDetailLock(detail.id) {
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

    /** Truncating writeText leaves a partial file if the process dies mid-write,
     *  which fails JSON parse on next launch and discards the whole cache. */
    private fun writeAtomically(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            tmp.writeText(content)
            if (!tmp.renameTo(target)) {
                Log.w(TAG, "atomic write failed: rename ${tmp.name} -> ${target.name}")
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "atomic write failed for ${target.name}", e)
            tmp.delete()
        }
    }

    private companion object {
        const val TAG = "BulletinCache"
    }
}
