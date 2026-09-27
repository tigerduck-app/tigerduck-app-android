package org.ntust.app.tigerduck.data.cache

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.network.model.PortalLink
import java.io.File
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/** Snapshot of one language variant of the information-system portal scrape. */
data class PortalLinksSnapshot(
    val links: List<PortalLink>?,
    val fetchedAt: Date?,
)

/**
 * Disk-backed cache for the information-system portal link list, one file
 * per language variant (the scrape target differs by locale). Mirrors
 * [BulletinCache]'s shape — its own subdirectory under filesDir, mutex-guarded
 * atomic writes — since this is a single flat snapshot with no cross-refs into
 * other cached models.
 */
@Singleton
class PortalLinksCache @Inject constructor(@ApplicationContext context: Context) {

    private val dir: File = File(context.filesDir, "portal").also { it.mkdirs() }
    private val mutex = Mutex()
    private val gson = Gson()
    private val type = object : TypeToken<PortalLinksSnapshot>() {}.type

    private fun fileFor(lang: String) = File(dir, "links_$lang.json")

    suspend fun load(lang: String): PortalLinksSnapshot? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val file = fileFor(lang)
            try {
                if (!file.exists()) null
                else gson.fromJson<PortalLinksSnapshot>(file.readText(), type)
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun save(snapshot: PortalLinksSnapshot, lang: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            writeAtomically(fileFor(lang), gson.toJson(snapshot))
        }
    }

    suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) {
            dir.listFiles()?.forEach { runCatching { it.delete() } }
        }
    }

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
        const val TAG = "PortalLinksCache"
    }
}
