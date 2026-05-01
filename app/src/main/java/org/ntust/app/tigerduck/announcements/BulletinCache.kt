package org.ntust.app.tigerduck.announcements

import android.content.Context
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
    private val gson = Gson()
    private val listType = object : TypeToken<List<BulletinSummary>>() {}.type

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
            try {
                file.writeText(gson.toJson(items))
            } catch (_: Exception) { }
        }
    }

    /** One JSON file per id mirrors iOS DataCache.bulletinDetailDir(); keeps
     *  writes small and avoids rewriting an aggregated file on every open. */
    suspend fun loadDetail(id: Int): BulletinDetail? = withContext(Dispatchers.IO) {
        val f = File(detailDir, "$id.json")
        try {
            if (f.exists()) gson.fromJson(f.readText(), BulletinDetail::class.java) else null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun saveDetail(detail: BulletinDetail) = withContext(Dispatchers.IO) {
        try {
            File(detailDir, "${detail.id}.json").writeText(gson.toJson(detail))
        } catch (_: Exception) { }
    }
}
