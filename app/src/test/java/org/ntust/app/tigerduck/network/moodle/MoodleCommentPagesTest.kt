package org.ntust.app.tigerduck.network.moodle

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.network.model.moodle.MoodleSubmissionComment

/**
 * A submission's comments past Moodle's first page: a page at a time until the total, the first
 * page once more when one came in meanwhile, and a stop on a page with nothing new or at the
 * limit. The fixtures follow the shapes Moodle 4.5 documents, newest first.
 */
class MoodleCommentPagesTest {

    private fun comment(id: Int) = MoodleSubmissionComment(id, "T00001 @ Duck Teacher", 1_790_500_000L + id, "<p>Note $id</p>")

    /** A page as Moodle answers it, newest first, with the submission's total. */
    private fun page(ids: List<Int>, count: Int?) = MoodleCommentPage(ids.map(::comment), count)

    /** Hands out [answers] in turn, recording the pages asked for. */
    private class Script(private val answers: List<MoodleCommentPage>) {
        val asked = mutableListOf<Int>()
        suspend fun page(number: Int): MoodleCommentPage {
            asked += number
            return answers[asked.size - 1]
        }
    }

    @Test
    fun `one page that holds the total is read oldest first`() = runTest {
        val script = Script(listOf(page(listOf(902, 901), count = 2)))

        val comments = MoodleCommentPages.all(script::page)

        assertEquals(listOf(901, 902), comments.map { it.id })
        assertEquals(listOf(0), script.asked)
    }

    @Test
    fun `pages past the first are asked for in turn, and the first once more when one came in meanwhile`() = runTest {
        // Comment 18 came in after the first request and moved comment 3 onto the second page.
        val script = Script(
            listOf(
                page((3..17).reversed().toList(), count = 17),
                page(listOf(3, 2, 1), count = 18),
                page(emptyList(), count = 18),
                page((4..18).reversed().toList(), count = 18),
            ),
        )

        val comments = MoodleCommentPages.all(script::page)

        assertEquals((1..18).toList(), comments.map { it.id })
        assertEquals(listOf(0, 1, 2, 0), script.asked)
    }

    @Test
    fun `a site that answers every page with the first stops the paging at the second`() = runTest {
        val first = page((1..15).reversed().toList(), count = 40)
        val script = Script(List(MoodleCommentPages.PAGE_LIMIT + 1) { first })

        val comments = MoodleCommentPages.all(script::page)

        assertEquals((1..15).toList(), comments.map { it.id })
        assertEquals(listOf(0, 1, 0), script.asked)
    }

    @Test
    fun `a total Moodle never reaches stops the paging at the limit`() = runTest {
        val limit = MoodleCommentPages.PAGE_LIMIT
        val script = Script(List(limit + 2) { index -> page(listOf(1000 - index), count = 500) })

        MoodleCommentPages.all(script::page)

        assertEquals((0 until limit).toList() + 0, script.asked)
    }

    @Test
    fun `a Moodle that leaves the total out gets one page`() = runTest {
        val script = Script(listOf(page(listOf(2, 1), count = null), page(listOf(0), count = null)))

        val comments = MoodleCommentPages.all(script::page)

        assertEquals(listOf(1, 2), comments.map { it.id })
        assertEquals(listOf(0), script.asked)
    }
}
