package org.ntust.app.tigerduck.network

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.network.model.MoodleSubmission
import java.util.Date

class MoodleSubmissionStateTest {

    private fun assignment(id: String, completed: Boolean, submittedAt: Date? = null) = Assignment(
        assignmentId = id,
        courseNo = "CS101",
        courseName = "CS",
        title = "HW $id",
        dueDate = Date(0),
        isCompleted = completed,
        submittedAt = submittedAt,
    )

    @Test
    fun `only submitted assignments count as confirmed, with their time`() {
        val at = Date(5_000)
        val confirmed = MoodleService.confirmedSubmissions(
            listOf(
                assignment("1", completed = true, submittedAt = at),
                assignment("2", completed = false),
                assignment("3", completed = true),
            )
        )
        assertEquals(mapOf(1 to at, 3 to null), confirmed)
    }

    @Test
    fun `an id that is not a Moodle number is not confirmed`() {
        assertEquals(
            emptyMap<Int, Date?>(),
            MoodleService.confirmedSubmissions(listOf(assignment("manual-1", completed = true))),
        )
    }

    @Test
    fun `a confirmed submission keeps the time the cache recorded`() {
        val at = Date(5_000)
        assertEquals(
            true to at,
            MoodleService.submissionState(1, confirmed = mapOf(1 to at), submission = null),
        )
    }

    @Test
    fun `anything else is as Moodle answered`() {
        assertEquals(
            true to Date(7_000),
            MoodleService.submissionState(
                2, confirmed = emptyMap(), MoodleSubmission(status = "submitted", timemodified = 7),
            ),
        )
        assertEquals(
            false to null,
            MoodleService.submissionState(
                2, confirmed = emptyMap(), MoodleSubmission(status = "draft", timemodified = 0),
            ),
        )
    }

    @Test
    fun `a status call that failed reads as not submitted`() {
        assertEquals(
            false to null,
            MoodleService.submissionState(2, confirmed = mapOf(1 to null), submission = null),
        )
    }
}
