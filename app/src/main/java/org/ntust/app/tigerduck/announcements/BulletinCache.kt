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
    // Bounded LRU so a long session opening many bulletins doesn't accumulate
    // a permanent Mutex per id. Evicting an unused lock is safe — re-creating
    // it later costs nothing when there's no in-flight access.
    private val detailLocks = object : LinkedHashMap<Int, Mutex>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Int, Mutex>): Boolean = size > 32
    }
    private val gson = Gson()
    private val listType = object : TypeToken<List<BulletinSummary>>() {}.type

    private fun detailLock(id: Int): Mutex = synchronized(detailLocks) {
        detailLocks.getOrPut(id) { Mutex() }
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
        detailLock(id).withLock {
            val f = File(detailDir, "$id.json")
            try {
                if (f.exists()) gson.fromJson(f.readText(), BulletinDetail::class.java) else null
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun saveDetail(detail: BulletinDetail) = withContext(Dispatchers.IO) {
        detailLock(detail.id).withLock {
            writeAtomically(File(detailDir, "${detail.id}.json"), gson.toJson(detail))
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
