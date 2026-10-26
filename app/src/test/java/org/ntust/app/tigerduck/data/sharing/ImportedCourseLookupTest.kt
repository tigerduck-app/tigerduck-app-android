package org.ntust.app.tigerduck.data.sharing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.network.model.CourseSearchResult
import org.ntust.app.tigerduck.shared.Course
import java.io.IOException

/**
 * Looking an imported class table up in NTUST's course query: four workers drain one queue of
 * course codes, however many a code carries and whether an import or a refresh asks, and unknown
 * codes stay apart from failed requests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportedCourseLookupTest {

    private var inFlight = 0
    private var mostInFlight = 0
    private val gate = CompletableDeferred<Unit>()
    private val failing = mutableSetOf<String>()

    private fun row(courseNo: String) = CourseSearchResult(
        semester = "1151", courseNo = courseNo, courseName = "Course $courseNo", courseTeacher = "",
        dimension = null, creditPoint = "3", requireOption = null, allYear = null, chooseStudent = null,
        restrict1 = null, restrict2 = null, courseTimes = null, practicalTimes = null, classRoomNo = null,
        node = null, contents = null,
    )

    /** The lookup's workers run here, on the test's clock, and stop with the test. */
    private fun TestScope.lookup(): Pair<ImportedCourseLookup, CoroutineScope> {
        val workers = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val lookup = ImportedCourseLookup(
            query = { _, courseNo, _ ->
                inFlight++
                mostInFlight = maxOf(mostInFlight, inFlight)
                try {
                    gate.await()
                    if (courseNo in failing) throw IOException("query failed")
                    if (courseNo.startsWith("XX")) emptyList() else listOf(row(courseNo))
                } finally {
                    inFlight--
                }
            },
            courseFrom = { rows -> Course(courseNo = rows.first().courseNo, courseName = rows.first().courseName) },
            isOnline = { true },
            scope = workers,
        )
        return lookup to workers
    }

    /** Every coroutine under [job], at any depth. */
    private fun descendants(job: Job): Int = job.children.sumOf { 1 + descendants(it) }

    private fun codes(count: Int) = (1..count).map { "CS1%06d".format(it) }

    @Test
    fun `a code of many courses is looked up four at a time`() = runTest {
        val (lookup, workers) = lookup()
        var result: List<Course>? = null
        launch { result = lookup.courses("1151", codes(30)) }
        advanceUntilIdle()

        assertEquals(4, mostInFlight)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(codes(30), result?.map { it.courseNo })
        assertTrue(mostInFlight <= 4)
        workers.cancel()
    }

    @Test
    fun `three hundred codes start four coroutines, not one per course`() = runTest {
        val (lookup, workers) = lookup()
        val caller = launch { lookup.courses("1151", codes(300)) }
        advanceUntilIdle()

        assertEquals(4, descendants(caller) + descendants(workers.coroutineContext.job))
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(caller.isCompleted)
        workers.cancel()
    }

    @Test
    fun `an import and a refresh at once share the same four`() = runTest {
        val (lookup, workers) = lookup()
        launch { lookup.courses("1151", codes(20)) }
        launch { lookup.refreshed("1142", codes(20), emptyList()) }
        advanceUntilIdle()

        assertEquals(4, mostInFlight)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(mostInFlight <= 4)
        workers.cancel()
    }

    @Test
    fun `an unknown code is left out, and a failed request fails the import`() = runTest {
        val (lookup, workers) = lookup()
        gate.complete(Unit)

        assertEquals(listOf("CS1000001"), lookup.courses("1151", listOf("CS1000001", "XX1000002")).map { it.courseNo })

        failing += "CS1000001"
        val failure = runCatching { lookup.courses("1151", listOf("CS1000001", "XX1000002")) }.exceptionOrNull()
        assertTrue(failure is IOException)
        workers.cancel()
    }

    @Test
    fun `a refresh keeps the rows of failed and unknown codes, and with every request failed is no answer`() = runTest {
        val (lookup, workers) = lookup()
        gate.complete(Unit)
        val kept = listOf(Course(courseNo = "CS1000001", courseName = "Kept"), Course(courseNo = "XX1000002", courseName = "Old"))
        failing += "CS1000001"

        val refreshed = lookup.refreshed("1151", listOf("CS1000001", "XX1000002", "CS1000003"), kept)
        assertEquals(listOf("Kept", "Old", "Course CS1000003"), refreshed?.map { it.courseName })

        failing += listOf("XX1000002", "CS1000003")
        assertNull(lookup.refreshed("1151", listOf("CS1000001", "XX1000002", "CS1000003"), kept))
        workers.cancel()
    }

    @Test
    fun `a cancelled import leaves its queued codes unasked`() = runTest {
        val (lookup, workers) = lookup()
        val caller = launch { lookup.courses("1151", codes(40)) }
        advanceUntilIdle()
        val asked = mostInFlight

        caller.cancel()
        gate.complete(Unit)
        advanceUntilIdle()

        // The four in flight finish; none of the other thirty-six goes out.
        assertEquals(4, asked)
        assertEquals(0, inFlight)
        workers.cancel()
    }
}
