package org.ntust.app.tigerduck.data.moodle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.model.Assignment
import java.util.Date
import org.junit.Assert.assertNull

/**
 * Each assignment keeps the newest answer accepted for it, so an answer asked for before it, a
 * round's or a page's, never overwrites it: a round that asked before a page and writes after it,
 * even after a later round wrote, cannot take the newer status back. Only what Moodle answered is
 * accepted, under the account that asked.
 */
class AssignmentStatusLedgerTest {

    private var generation = 1
    private val ledger = AssignmentStatusLedger { generation }

    private fun row(id: String, completed: Boolean = false, submittedAtMs: Long? = null) = Assignment(
        assignmentId = id, courseNo = "CS3005301", courseName = "DS", title = "A$id", dueDate = Date(1_790_600_000_000L),
        isCompleted = completed, submittedAt = submittedAtMs?.let(::Date),
    )

    private fun answer(
        id: String,
        askedAtMs: Long,
        submitted: Boolean = true,
        submittedAtMs: Long? = 1_790_500_000_000L,
        generation: Int = this.generation,
    ) = SubmissionStatusAnswer(id, submitted, submittedAtMs, askedAtMs, generation)

    /** A round's successful status answers, by id. */
    private fun answered(vararg answers: SubmissionStatusAnswer) = answers.associateBy { it.assignmentId }

    @Test
    fun `a page answer newer than the round's own request replaces the round's status at the write`() {
        assertTrue(ledger.offer(answer("701", askedAtMs = 150L)))

        val written = ledger.reconcile(
            listOf(row("701"), row("702")),
            answered(answer("701", 100L, submitted = false, submittedAtMs = null), answer("702", 100L, submitted = false, submittedAtMs = null)),
            generation,
        )

        assertTrue(written[0].isCompleted)
        assertEquals(Date(1_790_500_000_000L), written[0].submittedAt)
        assertFalse("an assignment with no page answer keeps the round's status", written[1].isCompleted)
    }

    @Test
    fun `a round that asked before a page and writes after a later round keeps the newest status`() {
        // Round 1 asked at 100 and found nothing handed in; its screen writes last, once its
        // course lookups are done. Meanwhile the page asked at 150 and round 2 at 200, both of
        // which found the submission.
        assertTrue(ledger.offer(answer("701", askedAtMs = 150L)))
        val second = ledger.reconcile(
            listOf(row("701", completed = true, submittedAtMs = 1_790_500_000_000L)),
            answered(answer("701", 200L)),
            generation,
        )
        assertTrue(second.single().isCompleted)

        val first = ledger.reconcile(listOf(row("701")), answered(answer("701", 100L, submitted = false, submittedAtMs = null)), generation)

        assertTrue("round 1's older answer does not take the status back", first.single().isCompleted)
        assertEquals(Date(1_790_500_000_000L), first.single().submittedAt)
    }

    @Test
    fun `a round's answer is accepted at its write, and a page answer asked for before it is dropped`() {
        ledger.reconcile(listOf(row("701")), answered(answer("701", 200L, submitted = false, submittedAtMs = null)), generation)

        assertFalse("older than the round's request", ledger.offer(answer("701", askedAtMs = 150L)))
        assertFalse("the same reading is not newer", ledger.offer(answer("701", askedAtMs = 200L)))
        assertTrue(ledger.offer(answer("701", askedAtMs = 250L, submitted = false, submittedAtMs = null)))
        assertFalse("older than another page's", ledger.offer(answer("701", askedAtMs = 220L)))

        val written = ledger.reconcile(
            listOf(row("701", completed = true, submittedAtMs = 1L)),
            answered(answer("701", 230L, submittedAtMs = 1L)),
            generation,
        )
        assertFalse("the newest page answer stands over a round asked for before it", written.single().isCompleted)
    }

    @Test
    fun `only what Moodle answered is accepted, not a status the caller kept from the cache`() {
        // The page found the submission withdrawn. The calendar's round, asked for after that,
        // found the same, but its caller restored "submitted" from a snapshot of the cache taken
        // before the page wrote.
        assertTrue(ledger.offer(answer("701", askedAtMs = 150L, submitted = false, submittedAtMs = null)))

        val written = ledger.reconcile(
            listOf(row("701", completed = true, submittedAtMs = 1_790_000_000_000L)),
            answered(answer("701", 200L, submitted = false, submittedAtMs = null)),
            generation,
        )

        assertFalse("the round's own answer is written, not the restored status", written.single().isCompleted)
        assertFalse("and it is what stands against the next page answer", ledger.offer(answer("701", askedAtMs = 180L)))
        assertTrue(ledger.offer(answer("701", askedAtMs = 210L)))
    }

    @Test
    fun `an assignment with no successful answer keeps the caller's status and records no time`() {
        // The round's status request for 701 failed, or the round did not ask (confirmed, closed),
        // so the caller kept the cache's status. A page answer, of any time, stands over it.
        assertTrue(ledger.offer(answer("701", askedAtMs = 150L)))
        val written = ledger.reconcile(listOf(row("701"), row("702", completed = true)), answered(), generation)

        assertTrue(written[0].isCompleted)
        assertTrue("a kept status is written as kept", written[1].isCompleted)
        assertTrue("nothing stands in the way of the next page answer", ledger.offer(answer("701", askedAtMs = 160L)))
        assertTrue("a kept status is never stamped", ledger.offer(answer("702", askedAtMs = 1L, submitted = false, submittedAtMs = null)))
    }

    @Test
    fun `answers of an account that signed out are neither accepted nor kept for the next account`() {
        // The departing account's round writes after the sign-out cleared the ledger and before
        // the wipe moved the generation: its answers go in under generation 1.
        ledger.reconcile(listOf(row("701", completed = true)), answered(answer("701", 100L)), generation)
        generation = 2

        // The next account's round got no answer for 701, so it keeps what its cache says.
        val next = ledger.reconcile(listOf(row("701")), answered(), generation)
        assertFalse("the departing account's answer is not inherited", next.single().isCompleted)

        // A late write and a late page answer of the departing account change nothing.
        val late = ledger.reconcile(listOf(row("701")), answered(answer("701", 300L, generation = 1)), generation = 1)
        assertFalse(late.single().isCompleted)
        assertFalse(ledger.offer(answer("701", askedAtMs = 400L, generation = 1)))
        assertFalse("nothing of theirs stands against the next account's own page", ledger.reconcile(listOf(row("701")), answered(), generation).single().isCompleted)
        assertTrue(ledger.offer(answer("701", askedAtMs = 50L)))
    }

    @Test
    fun `a sign-out forgets every answer`() {
        ledger.reconcile(listOf(row("701")), answered(answer("701", 300L, submitted = false, submittedAtMs = null)), generation)
        assertTrue(ledger.offer(answer("702", askedAtMs = 300L)))

        ledger.clear()

        assertTrue(ledger.offer(answer("701", askedAtMs = 100L)))
        assertFalse(
            "the departing account's page answer is not applied to the next account's row",
            ledger.reconcile(listOf(row("702")), answered(), generation).single().isCompleted,
        )
    }

    @Test
    fun `an extension goes by the same order, a newer page answer's winning and a later round taking a revoked one off`() {
        val extended = 1_790_900_000_000L
        assertTrue(ledger.offer(answer("701", askedAtMs = 150L, submitted = false, submittedAtMs = null).copy(extensionDueMs = extended)))

        val written = ledger.reconcile(listOf(row("701")), answered(answer("701", 100L, submitted = false, submittedAtMs = null)), generation)
        assertEquals(Date(extended), written.single().extensionDueDate)

        val later = ledger.reconcile(written, answered(answer("701", 200L, submitted = false, submittedAtMs = null)), generation)
        assertNull(later.single().extensionDueDate)
    }
}
