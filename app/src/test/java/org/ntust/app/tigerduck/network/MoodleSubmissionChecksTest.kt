package org.ntust.app.tigerduck.network

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.network.model.MoodleAssignmentNode
import org.ntust.app.tigerduck.network.model.MoodleSubmission
import org.ntust.app.tigerduck.network.model.MoodleSubmissionLastAttempt
import org.ntust.app.tigerduck.network.model.MoodleSubmissionStatusEnvelope
import java.util.Date

/**
 * Which assignments a round asks Moodle about. Every one not confirmed
 * submitted was asked, without a due date or taking no submissions included,
 * every 15 minutes from the worker (tigerduck-app-android#221). One Moodle
 * says the student can no longer hand in is closed for a day.
 */
class MoodleSubmissionChecksTest {

    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L

    private fun node(id: Int, due: Long = now - hour, opensAt: Long? = null, noSubmissions: Boolean = false) =
        MoodleAssignmentNode(
            id = id,
            cmid = id,
            name = "A$id",
            duedate = due / 1000,
            allowsubmissionsfromdate = opensAt?.let { it / 1000 },
            nosubmissions = if (noSubmissions) 1 else 0,
        )

    private fun status(handedIn: Boolean = false, canEdit: Boolean?, canSubmit: Boolean? = false) =
        MoodleSubmissionStatusEnvelope(
            lastattempt = MoodleSubmissionLastAttempt(
                submission = MoodleSubmission(status = if (handedIn) "submitted" else "new", timemodified = 1),
                canedit = canEdit,
                cansubmit = canSubmit,
            ),
        )

    @Test
    fun `no due date, no submissions and a confirmed submission are not asked about`() {
        val asked = MoodleService.assignmentsToAsk(
            listOf(node(1), node(2, due = 0), node(3, noSubmissions = true), node(4)),
            confirmed = mapOf(4 to Date(now)),
            closed = emptyMap(),
            nowMs = now,
        )

        assertEquals(listOf(1), asked.map { it.id })
    }

    @Test
    fun `a closed assignment is not asked about for a day, then is asked again`() {
        val asked = MoodleService.assignmentsToAsk(
            listOf(node(1), node(2), node(3)),
            confirmed = emptyMap(),
            // 2's closure is a day old; 3's was stamped by a clock that has since gone back.
            closed = mapOf(1 to now - hour, 2 to now - 25 * hour, 3 to now + hour),
            nowMs = now,
        )

        assertEquals(listOf(2, 3), asked.map { it.id })
    }

    @Test
    fun `a round closes only what Moodle says can no longer be handed in`() {
        val assignments = listOf(
            node(1),
            node(2),
            node(3),
            node(4),
            node(5, opensAt = now + hour),
            node(6),
            node(7),
        )

        val closed = MoodleService.closedUnsubmitted(
            assignments,
            statuses = mapOf(
                1 to status(canEdit = false),
                2 to status(handedIn = true, canEdit = false),
                // Still open for this student: an override, an extension, or no cutoff.
                3 to status(canEdit = true),
                // An older Moodle that does not say.
                4 to status(canEdit = null),
                // Not open yet.
                5 to status(canEdit = false),
                6 to status(canEdit = false, canSubmit = true),
            ),
            askedAtMs = now,
            // 7 was closed earlier and not asked this time; 8 is gone from the list.
            kept = mapOf(7 to now - hour, 8 to now - hour),
        )

        assertEquals(mapOf(1 to now, 7 to now - hour), closed)
    }

    @Test
    fun `a closure a day old is not kept`() {
        val closed = MoodleService.closedUnsubmitted(
            listOf(node(7)),
            statuses = emptyMap(),
            askedAtMs = now,
            kept = mapOf(7 to now - 25 * hour),
        )

        assertEquals(emptyMap<Int, Long>(), closed)
    }
}
