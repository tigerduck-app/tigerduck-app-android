package org.ntust.app.tigerduck.data.moodle

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import java.util.Date
import org.ntust.app.tigerduck.util.JvmContext

/** A status an assignment's page fetched reaches the cached list Home, the Live Update and the reminders read. */
class AssignmentStatusWriterTest {

    private lateinit var cache: DataCache
    private lateinit var ledger: AssignmentStatusLedger
    private val published = mutableListOf<List<Assignment>>()
    private lateinit var writer: AssignmentStatusWriter

    @Before
    fun setUp() {
        val context = JvmContext()
        cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), AppPreferences(context)))
        ledger = AssignmentStatusLedger { cache.userDataGeneration }
        writer = AssignmentStatusWriter(ledger, cache) { published += it }
    }

    private fun row(id: String, completed: Boolean = false) = Assignment(
        assignmentId = id, courseNo = "CS3005301", courseName = "DS", title = "A$id",
        dueDate = Date(1_790_600_000_000L), isCompleted = completed,
    )

    private fun answer(id: String, askedAtMs: Long, submitted: Boolean = true, generation: Int = cache.userDataGeneration) =
        SubmissionStatusAnswer(id, submitted, if (submitted) 1_790_500_000_000L else null, askedAtMs, generation)

    @Test
    fun `a status the page fetched reaches the cached assignment and is published, and the same one again writes nothing`() = runTest {
        cache.saveAssignments(listOf(row("701"), row("702")))

        assertTrue(writer.take(answer("701", askedAtMs = 10)))

        val written = cache.loadAssignments()
        assertTrue(written.first { it.assignmentId == "701" }.isCompleted)
        assertEquals(Date(1_790_500_000_000L), written.first { it.assignmentId == "701" }.submittedAt)
        assertFalse(written.first { it.assignmentId == "702" }.isCompleted)
        assertEquals(listOf(written), published)

        assertFalse(writer.take(answer("701", askedAtMs = 20)))
        assertEquals("a status the cache already has is not published again", 1, published.size)
    }

    @Test
    fun `an answer asked for before the round's request is dropped`() = runTest {
        cache.saveAssignments(listOf(row("701")))
        ledger.reconcile(listOf(row("701")), mapOf("701" to answer("701", 100L, submitted = false)), cache.userDataGeneration)

        assertFalse(writer.take(answer("701", askedAtMs = 50)))

        assertFalse(cache.loadAssignments().single().isCompleted)
        assertTrue(published.isEmpty())
    }

    @Test
    fun `nothing is written for an account that signed out while Moodle answered`() = runTest {
        cache.saveAssignments(listOf(row("701")))
        val generation = cache.userDataGeneration
        cache.clearAllUserData()
        // The next account has an assignment of the same id, as a teammate would.
        cache.saveAssignments(listOf(row("701")))

        assertFalse(writer.take(answer("701", askedAtMs = 10, generation = generation)))

        assertFalse(cache.loadAssignments().single().isCompleted)
        assertTrue(published.isEmpty())
    }

    @Test
    fun `an assignment the cache does not list writes nothing`() = runTest {
        cache.saveAssignments(listOf(row("701")))

        assertFalse(writer.take(answer("999", askedAtMs = 10)))

        assertTrue(published.isEmpty())
    }

    @Test
    fun `an extension the page found, and nothing else, is written and published`() = runTest {
        cache.saveAssignments(listOf(row("701")))

        assertTrue(writer.take(answer("701", askedAtMs = 10, submitted = false).copy(extensionDueMs = 1_790_900_000_000L)))

        assertEquals(Date(1_790_900_000_000L), cache.loadAssignments().single().extensionDueDate)
        assertEquals(1, published.size)
    }
}
