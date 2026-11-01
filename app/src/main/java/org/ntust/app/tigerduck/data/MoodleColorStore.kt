package org.ntust.app.tigerduck.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.MoodleColorState
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.network.MoodleCourseIds
import org.ntust.app.tigerduck.util.logSummary
import java.io.File
import java.security.MessageDigest
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
    /** The signed-in school student's ID, or null while signed out. */
    private val studentId: () -> String?,
) {
    @Inject
    constructor(@ApplicationContext context: Context, dataCache: DataCache, credentials: CredentialManager) :
        this(File(context.noBackupFilesDir, FILE_NAME), dataCache, MoodleColorBook.shared, { credentials.ntustStudentId })

    // Held by the load, every write and the sign-out's clearing, so a write cannot land after it.
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()

    // Bumped by each clearing; a state queued before it is not written after.
    @Volatile
    private var epoch = 0

    // Only the latest state matters, so a burst of changes is one write.
    private val saves = Channel<Triple<Int, String?, MoodleColorState>>(Channel.CONFLATED)
    private var started = false

    // Who the book in memory belongs to: a hash of the student ID, or null before any sign-in.
    private var owner: String? = null
    private var lastMoodle: Map<String, String> = emptyMap()

    private val _colorsChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Emits when a course's Moodle color changed, for the watch and others to redraw. */
    val colorsChanged: SharedFlow<Unit> = _colorsChanged.asSharedFlow()

    /**
     * Loads the book once, and again for another student: whoever the kept state belongs to must
     * be the student signed in now, else it is deleted. A sign-out a process death cut short
     * leaves the file behind; this is what stops the next student inheriting it.
     */
    suspend fun ensureLoaded() = mutex.withLock {
        start()
        val now = studentId()?.let(::hash)
        if (book.isLoaded()) {
            if (owner != null && owner != now) forget() else if (owner == null) owner = now
            return@withLock
        }
        val saved = withContext(Dispatchers.IO) { read() }
        val foreign = saved != null && (now == null || saved.owner != now)
        if (foreign) deleteFiles()
        owner = now
        book.setIds(dataCache.loadMoodleCourseIds().mapKeys { MoodleCourseIds.normalizedIdnumber(it.key) })
        val kept = saved.takeUnless { foreign }
        // No state yet: an install from before this existed. Every course it has colored already
        // keeps its color, so none of them is new to the book, nor is whatever its first refresh
        // brings when the cache was evicted. A signed-out phone, or one whose file was another
        // student's, starts clean: the courses its next sign-in brings are new.
        val upgrade = kept == null && !foreign && now != null
        val legacy = if (upgrade) {
            dataCache.loadEveryTermCourses().values.flatten().mapTo(mutableSetOf(), MoodleColorBook::identOf)
        } else {
            emptySet()
        }
        // Whatever the first refresh brings is existing, whatever the caches kept of the courses.
        book.load(kept, legacy, upgrading = upgrade)
    }

    /** At a school sign-out: the file goes and the next account starts with no course known. */
    suspend fun forgetAccount() = mutex.withLock {
        // Before the clearing, so what the next session gives is kept even when this runs first.
        start()
        forget()
    }

    private fun forget() {
        epoch++
        book.clear()
        owner = null
        deleteFiles()
    }

    private fun deleteFiles() {
        runCatching { file.delete() }
        runCatching { File(file.parentFile, file.name + TEMP_SUFFIX).delete() }
    }

    private fun start() {
        if (started) return
        started = true
        book.persistWith { state ->
            if (state.moodle.orEmpty() != lastMoodle) {
                lastMoodle = state.moodle.orEmpty()
                _colorsChanged.tryEmit(Unit)
            }
            saves.trySend(Triple(epoch, owner, state))
        }
        scope.launch {
            for ((queuedAt, queuedFor, state) in saves) {
                mutex.withLock {
                    // Nothing known is the state of a cleared book, which has no file.
                    if (queuedAt == epoch && state != MoodleColorState()) write(state.copy(owner = queuedFor))
                }
            }
        }
    }

    // Not the ID itself: the file describes courses, not who took them.
    private fun hash(studentId: String): String =
        MessageDigest.getInstance("SHA-256").digest(studentId.toByteArray()).joinToString("") { "%02x".format(it) }

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
