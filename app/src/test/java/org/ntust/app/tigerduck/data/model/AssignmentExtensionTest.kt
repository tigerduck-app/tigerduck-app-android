package org.ntust.app.tigerduck.data.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import java.util.Date

/**
 * A teacher's extension (tigerduck-app-android#440): the student's own due date is the later of
 * the assignment's and the extension, and an extension past the cutoff raises the cutoff too.
 */
class AssignmentExtensionTest {

    private val day = 24 * 60 * 60 * 1000L
    private val due = Date(1_800_000_000_000L)
    private val extended = Date(due.time + 3 * day)

    private fun assignment(
        extension: Date? = extended,
        completed: Boolean = false,
        submittedAt: Date? = null,
        cutoff: Date? = null,
    ) = Assignment(
        assignmentId = "900001",
        courseNo = "CS0000001",
        courseName = "Test Course",
        title = "hw",
        dueDate = due,
        isCompleted = completed,
        cutoffDate = cutoff,
        submittedAt = submittedAt,
        extensionDueDate = extension,
    )

    private fun at(ms: Long) = Date(ms)

    @After
    fun tearDown() = AppClock.setOverride(null)

    @Test
    fun `the student's due date is the later of the due date and the extension`() {
        assertEquals(extended, assignment().studentDueDate)
        assertEquals(due, assignment(extension = null).studentDueDate)
        assertEquals(due, assignment(extension = Date(due.time - day)).studentDueDate)
    }

    @Test
    fun `an extension past the cutoff raises it, one before it does not`() {
        assertEquals(extended, assignment(cutoff = Date(due.time + day)).studentCutoffDate)
        val later = Date(due.time + 5 * day)
        assertEquals(later, assignment(cutoff = later).studentCutoffDate)
        assertNull(assignment(cutoff = null).studentCutoffDate)
    }

    @Test
    fun `handed in after the due date but before the extension is on time`() {
        val a = assignment(completed = true, submittedAt = Date(due.time + day))
        assertEquals(AssignmentStatus.SUBMITTED, a.status())
    }

    @Test
    fun `handed in after the extension is late`() {
        val a = assignment(completed = true, submittedAt = Date(extended.time + day))
        assertEquals(AssignmentStatus.SUBMITTED_LATE, a.status())
    }

    @Test
    fun `still open to the student, it is pending and not overdue`() {
        val now = due.time + day
        assertEquals(AssignmentStatus.PENDING, assignment().status(at(now)))
        AppClock.setOverride(ClockOverride(now, frozen = true, savedAtRealMillis = 0L))
        assertFalse(assignment().isOverdue)
    }

    @Test
    fun `past the cutoff but before the extension, it is still open`() {
        val a = assignment(cutoff = Date(due.time + day))
        assertEquals(AssignmentStatus.PENDING, a.status(at(due.time + 2 * day)))
    }

    @Test
    fun `past the extension, it is overdue, and rejected once the cutoff it raised has passed`() {
        val now = at(extended.time + day)
        assertEquals(AssignmentStatus.OVERDUE_ACCEPTABLE, assignment().status(now))
        assertEquals(AssignmentStatus.OVERDUE_REJECTED, assignment(cutoff = Date(due.time + day)).status(now))
        AppClock.setOverride(ClockOverride(now.time, frozen = true, savedAtRealMillis = 0L))
        assertTrue(assignment().isOverdue)
    }

    @Test
    fun `with no extension nothing changes`() {
        val a = assignment(extension = null, cutoff = Date(due.time + day))
        assertEquals(AssignmentStatus.OVERDUE_ACCEPTABLE, a.status(at(due.time + day / 2)))
        assertEquals(AssignmentStatus.OVERDUE_REJECTED, a.status(at(due.time + 2 * day)))
    }
}
