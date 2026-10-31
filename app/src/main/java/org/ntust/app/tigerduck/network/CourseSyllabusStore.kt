package org.ntust.app.tigerduck.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * Courses' syllabi, kept for the app's run, since a syllabus rarely changes within a term, and
 * shared by callers while one loads; a failure is not kept. One per term, course and language,
 * none kept as such too. As the iPhone's `CourseLookupService.syllabus`.
 */
class CourseSyllabusStore(
    private val scope: CoroutineScope,
    /** The service's answer for the term, course and language, as text. */
    private val fetch: suspend (semester: String, courseNo: String, language: String) -> String,
) {
    private data class Key(val semester: String, val courseNo: String, val language: String)

    // Each load holds its answer or its failure, so a failed fetch fails its callers, never the
    // scope it runs on.
    private val kept = HashMap<Key, Deferred<Result<CourseSyllabus?>>>()

    /** The syllabus, null when the school has none. Throws when the fetch or its answer fails. */
    suspend fun syllabus(semester: String, courseNo: String, language: String): CourseSyllabus? {
        val key = Key(semester, courseNo, language)
        // On the store's scope, so one caller leaving does not cancel the fetch another waits on.
        val load = synchronized(kept) {
            kept[key] ?: run {
                var self: Deferred<Result<CourseSyllabus?>>? = null
                val started = scope.async(start = CoroutineStart.LAZY) {
                    try {
                        Result.success(CourseSyllabus.decode(fetch(key.semester, key.courseNo, key.language)))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Let go of here, whether or not anyone still waits: a caller that left
                        // must not leave the failure for the next to find.
                        synchronized(kept) { if (kept[key] === self) kept.remove(key) }
                        Result.failure(e)
                    }
                }
                self = started
                kept[key] = started
                started
            }
        }
        val answer = try {
            load.await()
        } catch (e: CancellationException) {
            // The load itself cancelled, with its scope: let go of, for the next caller to start.
            if (load.isCancelled) synchronized(kept) { if (kept[key] === load) kept.remove(key) }
            throw e
        }
        // A failure is let go of, for the next caller to fetch again.
        if (answer.isFailure) synchronized(kept) { if (kept[key] === load) kept.remove(key) }
        return answer.getOrThrow()
    }
}
