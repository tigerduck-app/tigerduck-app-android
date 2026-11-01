package org.ntust.app.tigerduck.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.MoodleColorState
import org.ntust.app.tigerduck.network.MoodleCourseIds
import org.ntust.app.tigerduck.util.logSummary
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads [MoodleColorBook.shared] from its file and keeps the file written. Loaded once per
 * process, before anything gives a course its color: a screen, the widgets and the reset each
 * call [ensureLoaded] first.
 *
 * The file is `noBackupFilesDir/moodle_course_colors.json`: not the evictable cache, since it
 * records which courses were colored before and losing it would move their colors, and not
 * backed up, since it describes this phone's colors. A sign-out deletes it ([forgetAccount]).
 */
@Singleton
class MoodleColorStore internal constructor(
    private val file: File,
    private val dataCache: DataCache,
    private val book: MoodleColorBook,
) {
    @Inject
    constructor(@ApplicationContext context: Context, dataCache: DataCache) :
        this(File(context.noBackupFilesDir, FILE_NAME), dataCache, MoodleColorBook.shared)

    // Held by the load, every write and the sign-out's clearing, so a write cannot land after it.
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()

    // Bumped by each clearing; a state queued before it is not written after.
    @Volatile
    private var epoch = 0

    // Only the latest state matters, so a burst of changes is one write.
    private val saves = Channel<Pair<Int, MoodleColorState>>(Channel.CONFLATED)
    private var writing = false

    suspend fun ensureLoaded() = mutex.withLock {
        if (book.isLoaded()) return@withLock
        book.persistWith { saves.trySend(epoch to it) }
        startWriter()
        book.setIds(dataCache.loadMoodleCourseIds().mapKeys { MoodleCourseIds.normalizedIdnumber(it.key) })
        val saved = withContext(Dispatchers.IO) { read() }
        // No state yet: an install from before this existed. Every course it has colored already
        // keeps its color, so none of them is new to the book.
        val legacy = if (saved == null) {
            dataCache.loadEveryTermCourses().values.flatten().mapTo(mutableSetOf()) { it.courseNo }
        } else {
            emptySet()
        }
        book.load(saved, legacy)
    }

    /** At a school sign-out: the file goes and the next account starts with no course known. */
    suspend fun forgetAccount() = mutex.withLock {
        epoch++
        book.clear()
        withContext(Dispatchers.IO) {
            runCatching { file.delete() }
            runCatching { File(file.parentFile, file.name + TEMP_SUFFIX).delete() }
        }
    }

    private fun startWriter() {
        if (writing) return
        writing = true
        scope.launch {
            for ((queuedAt, state) in saves) {
                mutex.withLock {
                    // Nothing known is the state of a cleared book, which has no file.
                    if (queuedAt == epoch && state != MoodleColorState()) write(state)
                }
            }
        }
    }

    private fun read(): MoodleColorState? = try {
        if (file.exists()) gson.fromJson(file.readText(), MoodleColorState::class.java) else null
    } catch (e: Exception) {
        Log.w(TAG, "Could not read the Moodle colors: ${e.logSummary()}")
        null
    }

    private fun write(state: MoodleColorState) {
        val tmp = File(file.parentFile, file.name + TEMP_SUFFIX)
        try {
            tmp.writeText(gson.toJson(state))
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) file.writeText(gson.toJson(state))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not write the Moodle colors: ${e.logSummary()}")
            runCatching { tmp.delete() }
        }
    }

    internal companion object {
        private const val TAG = "MoodleColorStore"
        const val FILE_NAME = "moodle_course_colors.json"
        private const val TEMP_SUFFIX = ".tmp"
    }
}
