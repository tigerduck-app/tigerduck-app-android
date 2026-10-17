package org.ntust.app.tigerduck.network.moodle

import org.ntust.app.tigerduck.network.model.moodle.MoodleSubmissionComment

/** One page of a submission's comments, newest first, with the submission's total where Moodle gives it. */
data class MoodleCommentPage(
    val comments: List<MoodleSubmissionComment>,
    /** All the submission's comments, not this page's; null from a Moodle that leaves it out. */
    val count: Int?,
)

/**
 * Every comment on a submission, which `core_comment_get_comments` answers a page at a time,
 * newest first, with the total in `count`. Ported from iOS `MoodleAssignmentService.comments`.
 */
object MoodleCommentPages {
    /** A bound on the pages asked for one submission's comments, which stay far fewer than this many pages hold. */
    const val PAGE_LIMIT = 10

    /**
     * Asks [page] for page 0, 1, ... until the comments held reach the total, a page adds no
     * comment (an empty one, or the first page again from a site that ignores `page`) or
     * [PAGE_LIMIT] pages have gone out. A comment posted while paging pushes one already fetched
     * onto the next page and is on none of those asked for, so the first page is asked for once
     * more when fewer than the total came in. Oldest first, as a conversation reads.
     */
    suspend fun all(page: suspend (Int) -> MoodleCommentPage): List<MoodleSubmissionComment> {
        val byId = LinkedHashMap<Int, MoodleSubmissionComment>()
        var total = 0
        for (number in 0 until PAGE_LIMIT) {
            val answer = page(number)
            val known = byId.size
            answer.comments.forEach { byId[it.id] = it }
            total = answer.count ?: 0
            if (byId.size <= known || byId.size >= total) break
        }
        if (byId.size < total) {
            page(0).comments.forEach { byId[it.id] = it }
        }
        return byId.values.sortedWith(compareBy({ it.created }, { it.id }))
    }
}
