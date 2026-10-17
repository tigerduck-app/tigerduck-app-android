package org.ntust.app.tigerduck.data.moodle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.network.MoodleWebserviceError
import org.ntust.app.tigerduck.network.model.moodle.MoodleAssignmentSubmission
import org.ntust.app.tigerduck.network.model.moodle.MoodleSubmissionComment
import org.ntust.app.tigerduck.network.moodle.MoodleCourseApi
import java.io.IOException
import java.lang.reflect.Type
import java.util.Date

/** An assignment's page: what it finds in the cache, and what it fetches of the submission. */
@OptIn(ExperimentalCoroutinesApi::class)
class MoodleAccountAssignmentsTest {

    private class MemoryCache : MoodleCachePort {
        val files = mutableMapOf<String, Pair<Any, Long>>()

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> load(type: Type, name: String): Pair<T, Long>? = files[name] as Pair<T, Long>?

        override suspend fun save(value: Any, fetchedAtMs: Long, name: String) {
            files[name] = value to fetchedAtMs
        }
    }

    private val submitted = MoodleAssignmentSubmission(
        status = "submitted", modified = 1_790_500_000L, files = emptyList(), onlineText = "<p>Done</p>", id = 5501,
        takesComments = true, groupId = 31, groupName = null, comments = null, grade = null,
    )
    private val comment = MoodleSubmissionComment(901, "T00001 @ Duck Teacher", 1_790_505_000L, "<p>Please check page 2.</p>")

    private inner class FakeApi : StubMoodleCourseApi() {
        var allowed = setOf(MoodleCourseApi.COMMENTS_FUNCTION, MoodleCourseApi.GROUPS_FUNCTION)
        var submissionAnswer: () -> MoodleAssignmentSubmission = { submitted }
        var commentsAnswer: () -> List<MoodleSubmissionComment> = { listOf(comment) }
        var groupsAnswer: () -> Map<Int, String> = { mapOf(31 to "Group 3") }
        var submissionCalls = 0
        var commentCalls = mutableListOf<Pair<Int, Int>>()
        var groupCalls = mutableListOf<Int>()
        /** Holds the submission call open while set, so a second refresh meets one in flight. */
        var submissionGate: CompletableDeferred<Unit>? = null

        override suspend fun functions() = allowed
        override suspend fun submission(assignId: Int): MoodleAssignmentSubmission {
            submissionCalls++
            submissionGate?.await()
            return submissionAnswer()
        }
        override suspend fun comments(cmid: Int, submissionId: Int): List<MoodleSubmissionComment> {
            commentCalls += cmid to submissionId
            return commentsAnswer()
        }
        override suspend fun groups(courseId: Int): Map<Int, String> {
            groupCalls += courseId
            return groupsAnswer()
        }
    }

    private val api = FakeApi()
    private val cache = MemoryCache()
    private var signedIn = true
    private var generation = 7
    private var clock = 1_000L
    private val records = MutableSharedFlow<List<org.ntust.app.tigerduck.network.model.moodle.MoodleAssignmentRecord>>(replay = 1)
    private val listed = MutableSharedFlow<List<Assignment>>(replay = 1)
    private val taken = mutableListOf<SubmissionStatusAnswer>()

    private var wall = 1_790_000_000_000L

    private fun TestScope.assignments() = MoodleAccountAssignments(
        api = api,
        context = MoodleCacheContext("B1", cache, isCurrent = { signedIn }, now = { wall }),
        scope = backgroundScope,
        records = records,
        listed = listed,
        takeStatus = { answer -> taken += answer },
        generation = { generation },
        monotonicNowMs = { clock++ },
    )

    private fun row(id: Int, completed: Boolean = false) = Assignment(
        assignmentId = id.toString(), courseNo = "CS3005301", courseName = "DS", title = "A$id",
        dueDate = Date(1_790_600_000_000L), isCompleted = completed,
        moodleUrl = "https://moodle2.ntust.edu.tw/mod/assign/view.php?id=${id * 10}",
    )

    private val team = testRecord(702, courseId = 5, due = 1_791_000_000L, team = true)

    @Test
    fun `a page's entry waits for the cache, then pairs the record with Home's row, and is missing for an id in neither`() =
        runTest(UnconfinedTestDispatcher()) {
            val assignments = assignments()
            val seen = mutableListOf<MoodleAssignmentLookup>()
            assignments.lookup("701").onEach { seen += it }.launchIn(backgroundScope)
            assertEquals(listOf(MoodleAssignmentLookup.Loading), seen)

            records.emit(listOf(testRecord(701, due = 1_790_600_000L)))
            assertEquals("still unread on one side", listOf(MoodleAssignmentLookup.Loading), seen)
            listed.emit(listOf(row(701)))

            val found = seen.last() as MoodleAssignmentLookup.Found
            assertEquals(701, found.entry.record.assignId)
            assertEquals(row(701), found.entry.listed)
            assertEquals(MoodleAssignmentLookup.Missing, assignments.lookup("999").first())
        }

    @Test
    fun `the course's entries follow the records in Moodle's order, with Home's rows`() = runTest(UnconfinedTestDispatcher()) {
        val assignments = assignments()
        records.emit(listOf(testRecord(703), testRecord(701, due = 1_790_600_000L), testRecord(801, courseId = 6)))
        listed.emit(listOf(row(701)))

        val entries = assignments.entries(5).first()

        assertEquals(listOf(703, 701), entries.map { it.record.assignId })
        assertNull(entries[0].listed)
        assertEquals(row(701), entries[1].listed)
    }

    @Test
    fun `comments and the group's name come where the site offers their calls`() = runTest(UnconfinedTestDispatcher()) {
        val assignments = assignments()

        assignments.refreshSubmission(MoodleAssignmentEntry(team, null))

        val shown = assignments.submission(702).value!!
        assertEquals(listOf(comment), shown.comments)
        assertEquals("Group 3", shown.groupName)
        assertEquals(listOf(7020 to 5501), api.commentCalls)
        assertEquals(listOf(5), api.groupCalls)
        assertEquals("the copy is kept for the next opening", shown, cache.files["assignments/702"]!!.first)
    }

    @Test
    fun `a site that does not offer the comments or the groups call shows neither, without asking`() = runTest(UnconfinedTestDispatcher()) {
        api.allowed = emptySet()
        val assignments = assignments()

        assignments.refreshSubmission(MoodleAssignmentEntry(team, null))

        val shown = assignments.submission(702).value!!
        assertNull(shown.comments)
        assertNull(shown.groupName)
        assertTrue(api.commentCalls.isEmpty())
        assertTrue(api.groupCalls.isEmpty())
    }

    @Test
    fun `a comments or groups call Moodle refuses leaves out only its part`() = runTest(UnconfinedTestDispatcher()) {
        api.commentsAnswer = { throw MoodleWebserviceError.AccessDenied() }
        api.groupsAnswer = { throw MoodleWebserviceError.MalformedResponse("groups missing") }
        val assignments = assignments()

        assignments.refreshSubmission(MoodleAssignmentEntry(team, null))

        val shown = assignments.submission(702).value!!
        assertTrue(shown.isSubmitted)
        assertNull(shown.comments)
        assertNull(shown.groupName)
        assertNull(assignments.submission(702).state.value.failure)
    }

    @Test
    fun `a fetch that cannot reach Moodle keeps what the page showed`() = runTest(UnconfinedTestDispatcher()) {
        cache.files["assignments/702"] = submitted to 999L
        api.submissionAnswer = { throw IOException("offline") }
        val assignments = assignments()
        val feed = assignments.submission(702)
        feed.awaitCache()

        assignments.refreshSubmission(MoodleAssignmentEntry(team, null))

        assertEquals(submitted, feed.value)
        assertTrue(feed.state.value.failure is IOException)
        assertTrue("nothing is handed on for an answer that never came", taken.isEmpty())
    }

    @Test
    fun `the status the page shows goes on to the writer with when it was asked`() = runTest(UnconfinedTestDispatcher()) {
        val assignments = assignments()
        val before = clock

        assignments.refreshSubmission(MoodleAssignmentEntry(team, null))

        val answer = taken.single()
        assertEquals("702", answer.assignmentId)
        assertTrue(answer.submitted)
        assertEquals(1_790_500_000_000L, answer.submittedAtMs)
        assertEquals("stamped before the request went out", before, answer.askedAtMs)
        assertEquals(7, answer.generation)
    }

    @Test
    fun `an individual assignment asks for no group, and one that takes no comments asks for none`() = runTest(UnconfinedTestDispatcher()) {
        api.submissionAnswer = { submitted.copy(takesComments = false) }
        val assignments = assignments()

        assignments.refreshSubmission(MoodleAssignmentEntry(testRecord(701, due = 1_790_600_000L), row(701)))

        assertTrue(api.commentCalls.isEmpty())
        assertTrue(api.groupCalls.isEmpty())
    }

    @Test
    fun `an assignment that takes no online submission asks Moodle for none`() = runTest(UnconfinedTestDispatcher()) {
        val assignments = assignments()

        assignments.refreshSubmission(MoodleAssignmentEntry(testRecord(703, noSubmissions = true), null))

        assertEquals(0, api.submissionCalls)
        assertNull(assignments.submission(703).value)
        assertTrue(taken.isEmpty())
    }

    @Test
    fun `a sign-out while the submission is on its way shows nothing, asks for no comments or group and hands nothing on`() =
        runTest(UnconfinedTestDispatcher()) {
            api.submissionAnswer = {
                signedIn = false
                submitted
            }
            val assignments = assignments()

            runCatching { assignments.refreshSubmission(MoodleAssignmentEntry(team, null)) }
            runCurrent()

            assertNull(assignments.submission(702).value)
            assertTrue(api.commentCalls.isEmpty())
            assertTrue(api.groupCalls.isEmpty())
            assertTrue(taken.isEmpty())
            assertTrue(cache.files.isEmpty())
        }

    @Test
    fun `an opening within a minute of a success shows the copy without asking Moodle again`() = runTest(UnconfinedTestDispatcher()) {
        val assignments = assignments()
        val entry = MoodleAssignmentEntry(team, null)
        assignments.refreshSubmission(entry)
        assertEquals(1, api.submissionCalls)

        wall += 30_000
        assignments.refreshSubmission(entry)
        assertEquals("a return to the app half a minute later asks nothing", 1, api.submissionCalls)

        wall += 31_000
        assignments.refreshSubmission(entry)
        assertEquals("once the copy is a minute old it is fetched again", 2, api.submissionCalls)
    }

    @Test
    fun `a return from Moodle or a retry fetches again at once`() = runTest(UnconfinedTestDispatcher()) {
        val assignments = assignments()
        val entry = MoodleAssignmentEntry(team, null)
        assignments.refreshSubmission(entry)
        wall += 5_000

        assignments.refreshSubmission(entry, force = true)

        assertEquals(2, api.submissionCalls)
    }

    @Test
    fun `a failed fetch is not tried again for half a minute, unless asked for`() = runTest(UnconfinedTestDispatcher()) {
        api.submissionAnswer = { throw IOException("offline") }
        val assignments = assignments()
        val entry = MoodleAssignmentEntry(team, null)
        assignments.refreshSubmission(entry)
        assertEquals(1, api.submissionCalls)

        wall += 10_000
        assignments.refreshSubmission(entry)
        assertEquals("a return ten seconds after the failure waits", 1, api.submissionCalls)

        assignments.refreshSubmission(entry, force = true)
        assertEquals("Retry does not", 2, api.submissionCalls)

        wall += 31_000
        assignments.refreshSubmission(entry)
        assertEquals("and the next return after the backoff tries again", 3, api.submissionCalls)
    }

    @Test
    fun `a forced refresh does not join a request sent before the hand-off, while an ordinary one does`() =
        runTest(UnconfinedTestDispatcher()) {
            // The opening fetch is still out when the user submits on Moodle and comes back.
            var calls = 0
            api.submissionAnswer = { if (calls++ == 0) submitted.copy(status = "new", modified = null) else submitted }
            api.submissionGate = CompletableDeferred()
            val assignments = assignments()
            val entry = MoodleAssignmentEntry(team, null)

            val opening = async { assignments.refreshSubmission(entry) }
            val ordinary = async { assignments.refreshSubmission(entry) }
            val forced = async { assignments.refreshSubmission(entry, force = true) }
            runCurrent()
            assertEquals("the opening request alone is out; the forced one waits its turn", 1, api.submissionCalls)
            api.submissionGate!!.complete(Unit)
            opening.await()
            ordinary.await()
            forced.await()

            assertEquals("the forced refresh asked on its own, after the opening fetch", 2, api.submissionCalls)
            assertTrue("and the page shows the answer asked for after the hand-off", assignments.submission(702).value!!.isSubmitted)
            assertEquals(listOf(false, true), taken.map { it.submitted })
            assertTrue("asked for after the first", taken[1].askedAtMs > taken[0].askedAtMs)
        }
}
