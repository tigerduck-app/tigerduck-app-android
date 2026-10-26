package org.ntust.app.tigerduck.network

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.CourseRosterMerge
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.moodle.AssignmentStatusLedger
import org.ntust.app.tigerduck.data.moodle.SubmissionStatusAnswer
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import java.util.Date
import org.ntust.app.tigerduck.util.JvmContext

/**
 * What a round's writer gets back to show, upload and arm reminders from: the list as written,
 * which the reconciliation may have changed from the one it merged, and nothing once the write
 * was refused.
 */
class MoodleAssignmentsStoreTest {

    private lateinit var cache: DataCache
    private lateinit var ledger: AssignmentStatusLedger

    @Before
    fun setUp() {
        val context = JvmContext()
        cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), AppPreferences(context)))
        ledger = AssignmentStatusLedger { cache.userDataGeneration }
    }

    private fun row(id: String, completed: Boolean = false, submittedAtMs: Long? = null) = Assignment(
        assignmentId = id, courseNo = "CS3005301", courseName = "DS", title = "A$id",
        dueDate = Date(1_790_600_000_000L), isCompleted = completed, submittedAt = submittedAtMs?.let(::Date),
    )

    private fun answer(assignments: List<Assignment>, vararg statuses: SubmissionStatusAnswer, generation: Int = cache.userDataGeneration) =
        MoodleService.AssignmentsAnswer(assignments, statuses.associateBy { it.assignmentId }, generation)

    @Test
    fun `the writer gets the list as written, not the one it merged`() = runTest {
        // The cache says submitted; a forced round found the submission withdrawn, but the
        // caller's merge kept "submitted" from the cache, as it does for an unanswered one.
        cache.saveAssignments(listOf(row("701", completed = true, submittedAtMs = 1_790_000_000_000L), row("702")))
        val round = listOf(row("701"), row("702"))
        val merged = CourseRosterMerge.assignmentsToStore(round, cache.loadAssignments())!!
        assertEquals("the merge restores the cached status", true, merged[0].isCompleted)

        val written = MoodleService.storeAssignments(
            cache, ledger, merged,
            answer(round, SubmissionStatusAnswer("701", submitted = false, submittedAtMs = null, askedAtMs = 100L, generation = cache.userDataGeneration)),
        )

        // Home and the worker upload this list, and the calendar and class table show it.
        assertFalse("Moodle's own answer is what the writer gets", written!!.first { it.assignmentId == "701" }.isCompleted)
        assertNull(written.first { it.assignmentId == "701" }.submittedAt)
        assertEquals(written, cache.loadAssignments())
    }

    @Test
    fun `a round whose account signed out gets nothing back, and writes nothing`() = runTest {
        val generation = cache.userDataGeneration
        cache.clearAllUserData()
        cache.saveAssignments(listOf(row("701")))
        val round = listOf(row("701", completed = true))

        val written = MoodleService.storeAssignments(cache, ledger, round, answer(round, generation = generation))

        assertNull(written)
        assertFalse(cache.loadAssignments().single().isCompleted)
    }
}
