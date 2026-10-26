package org.ntust.app.tigerduck.data.sharing

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.network.NetworkChecker
import org.ntust.app.tigerduck.network.model.CourseSearchResult
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.util.logSummary
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The courses of a class table imported from a share code, which carries course codes only,
 * looked up in NTUST's course query as the user's own are.
 *
 * A share code may carry hundreds of course codes. They go into one queue, which four workers
 * drain for imports and refreshes alike, as the iOS app keeps to its `maxConcurrentRequests`: the
 * school sees four requests at a time, and a lookup starts no coroutine of its own per course.
 */
@Singleton
class ImportedCourseLookup internal constructor(
    /** QueryCourse's rows for one course code, past the cache when `fresh`; throws when the query fails. */
    private val query: suspend (semester: String, courseNo: String, fresh: Boolean) -> List<CourseSearchResult>,
    /** One course from the rows the query returned for it. */
    private val courseFrom: (List<CourseSearchResult>) -> Course,
    private val isOnline: () -> Boolean,
    /** Where the workers run, for as long as the app does. */
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(
        courseService: CourseService,
        networkChecker: NetworkChecker,
        @ApplicationScope appScope: CoroutineScope,
    ) : this(
        query = { semester, courseNo, fresh -> courseService.lookupCourse(semester, courseNo, fresh = fresh) },
        courseFrom = { courseService.courseFrom(it, moodle = null) },
        isOnline = networkChecker::isAvailable,
        scope = appScope,
    )

    private val queue = Channel<Request>(Channel.UNLIMITED)

    // The first lookup starts the workers; the queue is theirs from then on.
    private val workersStarted: Unit by lazy {
        repeat(WORKERS) {
            scope.launch {
                for (request in queue) request.batch.answer(request.index, request.run())
            }
        }
    }

    /**
     * For an import: each course the query knows, in the code's order, and none it does not
     * know. Throws when a query fails, so an import never keeps half a class table; the codes
     * still queued then go unasked.
     */
    suspend fun courses(semester: String, courseNos: List<String>): List<Course> =
        askAll(semester, courseNos, fresh = false, failFast = true)
            .mapNotNull { answer -> answer.getOrThrow().takeIf { it.isNotEmpty() }?.let(courseFrom) }

    /**
     * For a pull to refresh on someone else's class table: the term looked up again, past the
     * cache. A course the query does not answer keeps its row in [current]. Null when no lookup
     * went through, offline included, so the page can say the refresh failed.
     */
    suspend fun refreshed(semester: String, courseNos: List<String>, current: List<Course>): List<Course>? {
        if (!isOnline()) return null
        val currentByNo = current.associateBy { it.courseNo }
        val answers = askAll(semester, courseNos, fresh = true, failFast = false)
        if (answers.none { it.isSuccess }) return null
        return courseNos.zip(answers).mapNotNull { (courseNo, answer) ->
            val rows = answer.getOrNull()
            if (rows.isNullOrEmpty()) currentByNo[courseNo] else courseFrom(rows)
        }
    }

    /** Every code's answer, in order, from the workers. */
    private suspend fun askAll(
        semester: String,
        courseNos: List<String>,
        fresh: Boolean,
        failFast: Boolean,
    ): List<Result<List<CourseSearchResult>>> {
        if (courseNos.isEmpty()) return emptyList()
        workersStarted
        val batch = Batch(courseNos.size, failFast)
        courseNos.forEachIndexed { index, courseNo ->
            queue.trySend(Request(batch, index, semester, courseNo, fresh))
        }
        try {
            batch.done.await()
        } catch (e: CancellationException) {
            // The caller left: its codes still queued go unasked.
            batch.abandoned = true
            throw e
        }
        return batch.answers.map { it ?: Result.failure(IllegalStateException("No answer")) }
    }

    private inner class Request(
        val batch: Batch,
        val index: Int,
        val semester: String,
        val courseNo: String,
        val fresh: Boolean,
    ) {
        suspend fun run(): Result<List<CourseSearchResult>> {
            if (batch.abandoned) return Result.failure(CancellationException("The lookup was called off"))
            return try {
                Result.success(query(semester, courseNo, fresh))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not look up an imported course: ${e.logSummary()}")
                Result.failure(e)
            }
        }
    }

    /** One lookup's codes, answered by whichever worker takes each. */
    private class Batch(size: Int, private val failFast: Boolean) {
        val answers = arrayOfNulls<Result<List<CourseSearchResult>>>(size)
        private val left = AtomicInteger(size)
        val done = CompletableDeferred<Unit>()

        @Volatile
        var abandoned = false

        fun answer(index: Int, answer: Result<List<CourseSearchResult>>) {
            answers[index] = answer
            val failure = answer.exceptionOrNull()
            if (failFast && failure != null && failure !is CancellationException) {
                // One failure fails the whole import; the rest need not be asked.
                abandoned = true
                done.completeExceptionally(failure)
            }
            if (left.decrementAndGet() == 0) done.complete(Unit)
        }
    }

    private companion object {
        const val TAG = "ImportedCourseLookup"
        const val WORKERS = 4
    }
}
