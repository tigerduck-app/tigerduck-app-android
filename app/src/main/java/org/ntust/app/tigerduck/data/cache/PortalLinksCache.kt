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
import org.ntust.app.tigerduck.network.model.PortalLink
import org.ntust.app.tigerduck.util.logSummary
import java.io.File
import java.io.IOException
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Snapshot of one language variant of the information-system portal scrape. Every field is
 * nullable: Gson fills a key missing from an older file with null, and a non-null field would
 * surface that as an NPE downstream (see the upgrade-safe-persistence skill). Field names are
 * pinned by the R8 keep rule in proguard-rules.pro.
 */
data class PortalLinksSnapshot(
    val links: List<PortalLink>?,
    val fetchedAt: Date?,
    /** Whose portal this is. A snapshot without one, or for someone else, is never served. */
    val studentId: String?,
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

    // The last clear [clearIn] started, after any before it, which every read and write waits
    // for: started, the files it clears are no longer this cache's, even before they are gone.
    @Volatile
    private var clearing: Deferred<Unit>? = null

    // Written as [clearIn] starts a clear and removed once everything is gone: a process ended
    // in between clears at the next start, and while a delete fails nothing is read from the files.
    private val clearOwed = File(dir, "clear_owed")

    init {
        if (clearOwed.exists() && deleteAll().isEmpty()) runCatching { clearOwed.delete() }
    }

    private fun fileFor(lang: String) = File(dir, "links_$lang.json")

    /**
     * Returns the snapshot only if it was saved for [studentId], once any clear
     * [clearIn] started is done. A delete can fail, so account isolation can't rest on the file
     * being gone by the time the next account reads it.
     */
    suspend fun load(lang: String, studentId: String): PortalLinksSnapshot? {
        clearing?.await()
        return mutex.withLock {
        withContext(Dispatchers.IO) {
            val file = fileFor(lang)
            try {
                if (!file.exists() || clearOwed.exists()) null
                else gson.fromJson<PortalLinksSnapshot>(file.readText(), type)
                    ?.takeIf { it.studentId.equals(studentId.trim(), ignoreCase = true) }
            } catch (_: Exception) {
                null
            }
        }
        }
    }

    suspend fun save(snapshot: PortalLinksSnapshot, lang: String) {
        clearing?.await()
        mutex.withLock {
            withContext(Dispatchers.IO) {
                writeAtomically(fileFor(lang), gson.toJson(snapshot))
            }
        }
    }

    /** Throws [IOException] naming any file it could not delete, after trying them all. */
    suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val undeleted = deleteAll()
            if (undeleted.isNotEmpty()) {
                throw IOException("could not delete ${undeleted.joinToString { it.name }}")
            }
            // Last, once everything is gone: a process ended before this clears again at its next start.
            runCatching { clearOwed.delete() }
        }
    }

    /**
     * Starts [clear] in [scope], for a sign-out: every read and write from now on waits for it, so
     * none lands in the files it clears, and a sign-in right after cannot lose what it saves.
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

    /** The files it could not delete, the owed marker apart. */
    private fun deleteAll(): List<File> =
        dir.listFiles().orEmpty().filter { it != clearOwed && it.exists() && !it.delete() }

    private fun writeAtomically(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
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
        const val TAG = "PortalLinksCache"
    }
}
