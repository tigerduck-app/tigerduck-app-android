package org.ntust.app.tigerduck.data.cache

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.SelectionAnswer
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.util.JvmContext
import java.io.File

/**
 * When each term's course selection answer arrived, kept beside the answer, and the courses the
 * last full sync found a roster dropped on the server (`server_dropped.json`, as the iPhone's,
 * tigerduck-app#914): a course selection answer exempts a course from a server drop only when it
 * is newer than the drop. Made-up course numbers.
 */
class DataCacheSelectionAnswerTest {

    private val context = JvmContext()
    private lateinit var cache: DataCache

    private val data get() = File(context.filesDir, "TigerDuckData")

    @Before
    fun setUp() {
        cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), AppPreferences(context)))
    }

    @Test
    fun `a course selection answer is kept with when it arrived`() = runTest {
        val before = System.currentTimeMillis()
        cache.recordSelectionRoster(TERM, listOf(CS1, CS2))
        val after = System.currentTimeMillis()

        val answer = cache.selectionAnswerIn(TERM)!!
        assertEquals(setOf(CS1, CS2), answer.courseNos)
        assertTrue("${answer.atMs} in $before..$after", answer.atMs!! in before..after)
        assertNull(cache.selectionAnswerIn(OTHER_TERM))
    }

    @Test
    fun `an empty answer keeps the term's answer and its time`() = runTest {
        cache.recordSelectionRoster(TERM, listOf(CS1))
        val kept = cache.selectionAnswerIn(TERM)

        cache.recordSelectionRoster(TERM, emptyList())

        assertEquals(kept, cache.selectionAnswerIn(TERM))
    }

    /**
     * An install upgraded from a build that kept the answers without their times: the answer
     * reads with no time, and exempts no course from any server drop, which the next course
     * selection answer, with its time, settles.
     */
    @Test
    fun `an answer kept before the times were reads with none, and exempts no server drop`() = runTest {
        File(data, "selection_rosters.json").writeText("""{"$TERM":["$CS1"],"$OTHER_TERM":null}""")

        val answer = cache.selectionAnswerIn(TERM)!!

        assertEquals(setOf(CS1), answer.courseNos)
        assertNull(answer.atMs)
        assertFalse(answer.listsSince(CS1, droppedAtMs = 0L))
        cache.recordServerDrops(TERM, mapOf(CS1 to 0L))
        assertEquals(setOf(CS1), cache.serverDroppedIn(TERM))
    }

    @Test
    fun `an unreadable time reads as none`() = runTest {
        File(data, "selection_rosters.json").writeText("""{"$TERM":["$CS1"],"$OTHER_TERM":["$CS2"]}""")
        File(data, "selection_answered_at.json").writeText("""{"$TERM":null,"$OTHER_TERM":"soon"}""")

        assertNull(cache.selectionAnswerIn(TERM)!!.atMs)
        assertNull(cache.selectionAnswerIn(OTHER_TERM)?.atMs)

        File(data, "selection_answered_at.json").writeText("""{"$TERM":"""")
        assertEquals(setOf(CS1), cache.selectionAnswerIn(TERM)!!.courseNos)
        assertNull(cache.selectionAnswerIn(TERM)!!.atMs)
    }

    @Test
    fun `an answer exempts a course from a drop only when newer than it, by more than the clock margin`() {
        val droppedAt = 1_800_000_000_000L
        val margin = SelectionAnswer.CLOCK_MARGIN_MS
        fun answeredAt(at: Long?) = SelectionAnswer(setOf(CS1), at)

        assertTrue(answeredAt(droppedAt + margin + 1).listsSince(CS1, droppedAt))
        assertFalse(answeredAt(droppedAt + margin).listsSince(CS1, droppedAt))
        assertFalse(answeredAt(droppedAt - 60_000).listsSince(CS1, droppedAt))
        // Not listed, no time of its own, or a drop of no known time.
        assertFalse(answeredAt(droppedAt + 3_600_000).listsSince(CS2, droppedAt))
        assertFalse(answeredAt(null).listsSince(CS1, droppedAt))
        assertFalse(answeredAt(droppedAt + 3_600_000).listsSince(CS1, droppedAtMs = null))
    }

    @Test
    fun `a sync's server drops are kept but those a newer answer lists, and an answer since takes its courses off`() = runTest {
        cache.recordSelectionRoster(TERM, listOf(CS1, CS3))
        val answeredAt = cache.selectionAnswerIn(TERM)!!.atMs!!
        val hour = 3_600_000L

        cache.recordServerDrops(TERM, mapOf(CS1 to answeredAt - hour, CS2 to answeredAt - hour, CS3 to answeredAt + hour))
        cache.recordServerDrops(OTHER_TERM, mapOf(CS4 to null))

        assertEquals(setOf(CS2, CS3), cache.serverDroppedIn(TERM))
        assertEquals(setOf(CS4), cache.serverDroppedIn(OTHER_TERM))

        cache.recordSelectionRoster(TERM, listOf(CS2))
        assertEquals(setOf(CS3), cache.serverDroppedIn(TERM))

        // A sync whose snapshot drops none of the term's courses clears it.
        cache.recordServerDrops(TERM, emptyMap())
        assertEquals(emptySet<String>(), cache.serverDroppedIn(TERM))
        assertEquals(setOf(CS4), cache.serverDroppedIn(OTHER_TERM))
    }

    @Test
    fun `what a fetch leaves out is the drop list and the server drops together`() = runTest {
        cache.saveCourses(listOf(Course(courseNo = CS1, courseName = CS1)), TERM)
        cache.recordSelectionRoster(TERM, listOf(CS2))
        cache.recordServerDrops(TERM, mapOf(CS3 to null))

        assertEquals(setOf(CS1, CS3), cache.fetchDropsIn(TERM))
    }

    @Test
    fun `a sign-out takes the answer times and the server drops with the account`() = runTest {
        cache.recordSelectionRoster(TERM, listOf(CS1))
        cache.recordServerDrops(TERM, mapOf(CS2 to null))

        cache.clearAllUserData()

        assertNull(cache.selectionAnswerIn(TERM))
        assertEquals(emptySet<String>(), cache.serverDroppedIn(TERM))
        assertFalse(File(data, "selection_answered_at.json").exists())
        assertFalse(File(data, "server_dropped.json").exists())
    }

    @Test
    fun `nothing is kept once the data was wiped since the work began`() = runTest {
        val generation = cache.userDataGeneration
        cache.clearAllUserData()

        cache.recordSelectionRoster(TERM, listOf(CS1), generation)
        cache.recordServerDrops(TERM, mapOf(CS2 to null), generation)

        assertNull(cache.selectionAnswerIn(TERM))
        assertEquals(emptySet<String>(), cache.serverDroppedIn(TERM))
    }

    private companion object {
        const val TERM = "1151"
        const val OTHER_TERM = "1142"
        const val CS1 = "XA0000001"
        const val CS2 = "XB0000002"
        const val CS3 = "XC0000003"
        const val CS4 = "XD0000004"
    }
}
