package org.ntust.app.tigerduck.data.moodle

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.network.model.moodle.MoodleFile
import org.ntust.app.tigerduck.util.logSummary
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** A Moodle file's state on the device, for its row's mark. */
sealed interface MoodleDownloadState {
    data object NotDownloaded : MoodleDownloadState

    /** [progress] from 0 to 1, in whole percents. */
    data class Downloading(val progress: Float) : MoodleDownloadState

    data class Downloaded(val file: File) : MoodleDownloadState
    data class Failed(val error: Throwable) : MoodleDownloadState
}

/**
 * The Moodle files downloaded on this device: under [root], a folder per account (named so it
 * does not spell the student ID, as it shows in the content URI another app is handed), then per
 * course, owner and file, then the version Moodle lists, which a newer one replaces. A file
 * downloads once at a time however many screens ask, and goes on when they close; a sign-out, as
 * the cache wipe, stops every download and deletes the files. Ported from iOS
 * `MoodleFileDownloads`.
 */
@Singleton
class MoodleFileDownloads internal constructor(
    private val root: () -> File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    @Inject
    constructor(dataCache: DataCache, @ApplicationScope appScope: CoroutineScope) :
        this(root = dataCache::moodleDownloadsRoot, scope = appScope)

    data class Snapshot(
        val progress: Map<String, Float> = emptyMap(),
        val failures: Map<String, Throwable> = emptyMap(),
        /** The copies known to be on the device, by path. */
        val copies: Set<String> = emptySet(),
    )

    private val _snapshot = MutableStateFlow(Snapshot())

    /** What every file's state is read from; it moves when a download starts, moves on or ends. */
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    /** The download on its way for each copy, by path. Every read and write holds this map's lock. */
    private val running = HashMap<String, Deferred<File?>>()

    /**
     * Held while a download keeps its file under its final name, and while a wipe deletes the
     * folders: a wipe lists a folder once, so a file renamed as it lists would stay, under the
     * name it did not see.
     */
    private val keeping = Mutex()

    /**
     * Where [file] of [owner] is kept in [courseDir], the account's folder for the course: one
     * folder per file, by its link, place and name, so two files of one name stay apart, then one
     * per version, so a replaced file gets a copy of its own. The copy keeps the file's name, made
     * safe, for the app it is opened in.
     */
    fun location(courseDir: File, file: MoodleFile, owner: MoodleFileOwner): File {
        val slot = java.security.MessageDigest.getInstance("SHA-256")
            .digest("${file.url}|${file.path ?: "/"}|${file.name}".toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }
        return File(courseDir, "${owner.folderName}/$slot/${file.modified ?: 0}/${safeFileName(file.name)}")
    }

    fun state(location: File?, snapshot: Snapshot = _snapshot.value): MoodleDownloadState {
        location ?: return MoodleDownloadState.NotDownloaded
        val key = location.path
        snapshot.progress[key]?.let { return MoodleDownloadState.Downloading(it) }
        if (key in snapshot.copies) return MoodleDownloadState.Downloaded(location)
        snapshot.failures[key]?.let { return MoodleDownloadState.Failed(it) }
        return MoodleDownloadState.NotDownloaded
    }

    /**
     * Learns the copies already in [dir], as a course's screens open, so their rows show them, and
     * deletes the partial files a download left when the app was ended mid-way.
     */
    suspend fun index(dir: File) {
        val found = withContext(io) {
            sweepPartials(dir)
            dir.walkTopDown()
                .filter { it.isFile && !isPartial(it) }
                .map { it.path }
                .toSet()
        }
        if (found.isNotEmpty()) _snapshot.update { it.copy(copies = it.copies + found) }
    }

    /** Partial files in [dir] no download on its way is writing, as an app ended mid-way leaves. */
    private fun sweepPartials(dir: File) {
        dir.walkTopDown().filter { it.isFile && isPartial(it) }.toList().forEach { part ->
            // Asked as each is deleted, not once before the walk: a download started during the
            // walk is on the map before it makes its file, so one whose file the walk found is.
            synchronized(running) {
                if (running.keys.none { File(it).parentFile == part.parentFile }) part.delete()
            }
        }
    }

    /**
     * The copy at [location], downloading it through [fetch] first when there is none. A download
     * of it already on its way is joined. Null when it fails, is stopped, or ends after
     * [isCurrent] turns false, which keeps nothing. The download runs in the app's scope: leaving
     * the screen that asked does not stop it.
     */
    suspend fun download(
        location: File,
        fetch: suspend (target: File, progress: (Float) -> Unit) -> Unit,
        isCurrent: () -> Boolean,
    ): File? {
        val key = location.path
        if (withContext(io) { location.isFile }) {
            _snapshot.update { it.copy(copies = it.copies + key) }
            return location
        }
        val job = synchronized(running) {
            running[key]?.takeIf { it.isActive } ?: run {
                _snapshot.update { it.copy(progress = it.progress + (key to 0f), failures = it.failures - key) }
                val owner = Owner()
                scope.async { transfer(location, fetch, isCurrent) { share -> report(key, share, owner) } }.also { started ->
                    owner.job = started
                    running[key] = started
                    started.invokeOnCompletion {
                        // Only its own: a download stopped and tapped again has another on its way
                        // by the time this one has wound down.
                        synchronized(running) {
                            if (running[key] === started) running.remove(key)
                            if (running[key] == null) _snapshot.update { it.copy(progress = it.progress - key) }
                        }
                    }
                }
            }
        }
        return try {
            job.await()
        } catch (e: CancellationException) {
            // Stopped by a tap or a sign-out, not by the caller going away.
            currentCoroutineContext().ensureActive()
            null
        }
    }

    /** Stops [location]'s download; what it had written goes. */
    fun cancel(location: File) {
        val key = location.path
        val job = synchronized(running) { running.remove(key) }
        _snapshot.update { it.copy(progress = it.progress - key) }
        job?.cancel()
    }

    /** Stops every download, so none writes for an account that left, and returns them winding down. */
    private fun stopAll(): List<Job> {
        val jobs = synchronized(running) { running.values.toList().also { running.clear() } }
        _snapshot.value = Snapshot()
        jobs.forEach { it.cancel() }
        return jobs
    }

    /** The bytes the downloaded files take, once the partial files left behind are swept. */
    suspend fun measure(): Long = withContext(io) {
        sweepPartials(root())
        totalBytes(root())
    }

    /**
     * Deletes every downloaded file, stopping the downloads on their way, as a sign-out and the
     * cache wipe do, and returns the bytes left.
     */
    suspend fun clear(): Long {
        // Stopped and wound down before the folder goes: a download past its last check would
        // otherwise keep its file under the wipe, and one stopped deletes its partial file itself.
        stopAll().joinAll()
        return withContext(io) {
            keeping.withLock { root().deleteRecursively() }
            _snapshot.update { it.copy(copies = emptySet()) }
            totalBytes(root())
        }
    }

    /**
     * Deletes the copies of a version Moodle no longer lists, of each file at its [listed]
     * location, and stops the downloads of one, so an older version neither stays nor opens.
     */
    suspend fun removeOutdated(listed: List<File>) {
        val stale = withContext(io) {
            listed.flatMap { location ->
                val version = location.parentFile ?: return@flatMap emptyList()
                version.parentFile?.listFiles()?.filter { it.isDirectory && it != version }.orEmpty()
            }.distinct()
        }
        if (stale.isEmpty()) return
        val prefixes = stale.map { it.path + File.separator }
        synchronized(running) {
            running.keys.filter { key -> prefixes.any { key.startsWith(it) } }.mapNotNull { running.remove(it) }
        }.forEach { it.cancel() }
        // Under the lock: a stopped download keeps no file past this, and one kept before is listed.
        withContext(io) { keeping.withLock { stale.forEach { it.deleteRecursively() } } }
        _snapshot.update { snap ->
            snap.copy(
                copies = snap.copies.filterTo(mutableSetOf()) { key -> prefixes.none { key.startsWith(it) } },
                failures = snap.failures.filterKeys { key -> prefixes.none { key.startsWith(it) } },
            )
        }
    }

    /** Which download a share of progress is from, set once that download is made. */
    private class Owner {
        @Volatile
        var job: Deferred<File?>? = null
    }

    private suspend fun transfer(
        location: File,
        fetch: suspend (File, (Float) -> Unit) -> Unit,
        isCurrent: () -> Boolean,
        progress: (Float) -> Unit,
    ): File? {
        val key = location.path
        var part: File? = null
        try {
            // The copy is looked for again here, where a file has one download at a time: one
            // that kept it may have wound down between the caller's look for the copy and its
            // look for a download to join. The partial file is noted as soon as it is made: a
            // stop that lands before this returns still deletes it.
            val onDevice = withContext(io) {
                if (location.isFile) return@withContext true
                val dir = location.parentFile ?: throw IOException("no folder for the file")
                dir.mkdirs()
                part = File.createTempFile(PART_PREFIX, PART_SUFFIX, dir)
                false
            }
            if (onDevice) {
                _snapshot.update { it.copy(copies = it.copies + key, failures = it.failures - key) }
                return location
            }
            val target = part ?: throw IOException("no partial file")
            fetch(target, progress)
            // Checked and kept under the lock a wipe holds: either the wipe lists this file by
            // its final name, or a stop or sign-out landed first and the partial file goes below.
            // Landed after a sign-out, the next account must not find it.
            val kept = withContext(io) {
                keeping.withLock {
                    currentCoroutineContext().ensureActive()
                    if (!isCurrent()) return@withLock false
                    location.delete()
                    if (!target.renameTo(location)) throw IOException("could not keep the file")
                    true
                }
            }
            if (!kept) return null
            _snapshot.update { it.copy(copies = it.copies + key, failures = it.failures - key) }
            return location
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isCurrent()) {
                Log.w(TAG, "file not downloaded: ${e.logSummary()}")
                _snapshot.update { it.copy(failures = it.failures + (key to e)) }
            }
            return null
        } finally {
            withContext(NonCancellable + io) {
                part?.delete()
                // Folders a failed or stopped download made, left empty.
                var dir = location.parentFile
                repeat(3) {
                    if (dir?.list()?.isEmpty() == true) {
                        dir.delete()
                        dir = dir.parentFile
                    }
                }
            }
        }
    }

    /**
     * Whole percents, so a large file redraws its row a hundred times at most; only from the
     * download now on its way for [key], not one stopped before it.
     */
    private fun report(key: String, share: Float, owner: Owner) {
        val percent = kotlin.math.floor(share.coerceIn(0f, 1f) * 100) / 100
        synchronized(running) {
            if (running[key] !== owner.job) return
            _snapshot.update { snap ->
                if (key !in snap.progress || snap.progress[key] == percent) snap
                else snap.copy(progress = snap.progress + (key to percent))
            }
        }
    }

    companion object {
        private const val TAG = "MoodleFileDownloads"

        /** How a partial file is named, around the number the system gives it. */
        private const val PART_PREFIX = ".dl"
        private const val PART_SUFFIX = ".part"

        /** Under the 255 bytes most file systems allow a name, with room for the partial file's. */
        private const val MAX_NAME_BYTES = 200

        private fun totalBytes(dir: File): Long =
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

        /**
         * Whether [file] is a partial file: by the name [transfer] gives them, not its ending
         * alone, which a file Moodle lists can share. The leading dot sets them apart, since
         * [safeFileName] leaves no downloaded file's name beginning with one.
         */
        private fun isPartial(file: File): Boolean =
            file.name.startsWith(PART_PREFIX) && file.name.endsWith(PART_SUFFIX)

        /**
         * [name] as one file name: no slash, backslash or NUL, no leading dot to hide or climb
         * with, and at most [MAX_NAME_BYTES] bytes, its extension kept, where a file system allows
         * 255. A cut name whose extension could not be kept has no dot left, so it cannot end in
         * another extension that another app would open it by.
         */
        internal fun safeFileName(name: String): String {
            val cleaned = name.replace(Regex("""[/\\\u0000]"""), "_").trim().trimStart('.').ifEmpty { "file" }
            if (cleaned.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES) return cleaned
            val dot = cleaned.lastIndexOf('.')
            val extension = if (dot > 0 && cleaned.length - dot <= 16) cleaned.substring(dot) else ""
            val budget = MAX_NAME_BYTES - extension.toByteArray(Charsets.UTF_8).size
            val stem = StringBuilder()
            var used = 0
            var i = 0
            val body = cleaned.substring(0, cleaned.length - extension.length)
            while (i < body.length) {
                val codePoint = body.codePointAt(i)
                val bytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
                if (used + bytes > budget) break
                stem.appendCodePoint(codePoint)
                used += bytes
                i += Character.charCount(codePoint)
            }
            val cut = stem.toString().ifEmpty { "file" }
            return if (extension.isEmpty()) cut.replace('.', '_') else cut + extension
        }
    }
}
