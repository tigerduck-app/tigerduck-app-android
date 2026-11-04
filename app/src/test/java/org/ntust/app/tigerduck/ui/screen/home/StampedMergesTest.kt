package org.ntust.app.tigerduck.ui.screen.home

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.push.CourseOverrideResult
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.sync.AckClock
import org.ntust.app.tigerduck.sync.SyncField
import org.ntust.app.tigerduck.sync.reconcile.PullHolds
import org.ntust.app.tigerduck.util.testSignIn

/**
 * A pull's two course-override merges ([StampedMerges]) over an older snapshot, while this phone's
 * change of the field lands as the merge reads what the channel holds: its PATCH answered, its
 * acknowledgment recorded ([AckClock]) and its entry gone from the lane, as `OutboxLane.settle`
 * does it, in that order. Each merge reads the waiting changes before the acknowledgments, so the
 * field is held by one or the other, and the snapshot's older value is not taken.
 */
class StampedMergesTest {

    private val signIn = testSignIn()
    private val ack = AckClock()
    private val key = SyncField.courseKey(TERM, NO)

    private val course = Course(courseNo = NO, courseName = "Data Structures", moodleIdNumber = "$TERM$NO", customColorHex = "#FF0000")

    // [waiting] as the course overrides' channel answers it: the change lands as it is read, acknowledged and let go.
    private fun merges(snapshotRow: CourseOverrideResult, landing: String) = StampedMerges(
        overrides = listOf(snapshotRow),
        term = TERM,
        holds = PullHolds(SNAPSHOT_HLC, ack),
        waiting = {
            ack.acknowledged(landing, SNAPSHOT_HLC + 1_000, signIn)
            emptySet()
        },
        everyCourse = { mapOf(TERM to listOf(course)) },
        legacyTerms = { TERM to emptyList() },
    )

    private fun row(colorHex: String? = null, names: Map<String, String> = emptyMap()) = CourseOverrideResult(
        moodleCourseId = "$TERM$NO",
        courseNo = NO,
        colorHex = colorHex,
        customNames = names,
        semester = TERM,
        courseKey = key,
        colorHexHlc = colorHex?.let { 1L },
        customNamesHlc = names.mapValues { 1L },
    )

    @Test
    fun `a rename that lands while the names merge reads the waiting changes keeps its name over an older snapshot`() = runTest {
        val current = mapOf(NO to mapOf(LOCALE to "Mine"))
        val merges = merges(row(names = mapOf(LOCALE to "Older")), landing = SyncField.name(key, LOCALE))

        val merged = merges.names(current) ?: current

        assertEquals("Mine", merged[NO]?.get(LOCALE))
    }

    @Test
    fun `a pick that lands while the colors merge reads the waiting changes keeps its color over an older snapshot`() = runTest {
        val merges = merges(row(colorHex = "#00FF00"), landing = SyncField.color(key))

        val colored = merges.colors(listOf(course))?.courses ?: listOf(course)

        assertEquals("#FF0000", colored.single().customColorHex)
    }

    private companion object {
        const val TERM = "1141"
        const val NO = "CS1001"
        const val LOCALE = "zh"
        const val SNAPSHOT_HLC = 1_793_000_000_000_000L
    }
}
