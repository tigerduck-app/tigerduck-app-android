package org.ntust.app.tigerduck.demo

import org.ntust.app.tigerduck.data.moodle.MoodleMaterials
import org.ntust.app.tigerduck.network.model.moodle.MoodleAssignmentSubmission
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseMaterials
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseSummary
import org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussion
import org.ntust.app.tigerduck.network.model.moodle.MoodleFile
import org.ntust.app.tigerduck.network.model.moodle.MoodleForum
import org.ntust.app.tigerduck.network.model.moodle.MoodleGradeItem
import org.ntust.app.tigerduck.network.model.moodle.MoodleMaterial
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberList
import org.ntust.app.tigerduck.network.model.moodle.MoodlePost
import org.ntust.app.tigerduck.network.model.moodle.MoodleSubmissionComment
import org.ntust.app.tigerduck.network.moodle.MoodleCourseApi
import org.ntust.app.tigerduck.network.moodle.MoodleHtmlText

/**
 * The demo account's Moodle: answers from [moodle] and nothing from the network. What was opened
 * stays read, across restarts too, as Moodle would keep it: [readIds] and [markRead] keep the
 * discussions read with the rest of the demo account's state, which sign-out clears.
 */
class DemoMoodleCourseApi(
    private val moodle: DemoMoodle,
    private val readIds: () -> Set<Int>,
    private val markRead: (Int) -> Unit,
) : MoodleCourseApi {

    private fun course(moodleId: Int) = moodle.courses.firstOrNull { it.moodleId == moodleId }

    private fun unread(discussion: MoodleDiscussion) = if (discussion.id in readIds()) 0 else discussion.unreadCount

    override suspend fun functions(): Set<String> = setOf(
        MoodleCourseApi.FORUMS_FUNCTION,
        MoodleCourseApi.DISCUSSIONS_FUNCTION,
        MoodleCourseApi.DISCUSSION_POSTS_FUNCTION,
        MoodleCourseApi.VIEW_DISCUSSION_FUNCTION,
        MoodleCourseApi.MEMBERS_FUNCTION,
        MoodleCourseApi.GRADE_ITEMS_FUNCTION,
        MoodleCourseApi.COURSES_FUNCTION,
        MoodleCourseApi.SUBMISSION_STATUS_FUNCTION,
        MoodleCourseApi.COMMENTS_FUNCTION,
        MoodleCourseApi.GROUPS_FUNCTION,
        MoodleCourseApi.CONTENTS_FUNCTION,
    ) + MoodleMaterials.VIEW_CALLS.values.map { it.first }

    override suspend fun forums(courseIds: List<Int>): List<MoodleForum> =
        moodle.courses.filter { it.moodleId in courseIds }.flatMap { c ->
            listOf(
                MoodleForum(
                    id = c.newsForumId,
                    courseId = c.moodleId,
                    type = "news",
                    name = "",
                    cmid = c.moodleId * 10 + 2,
                    discussionCount = c.announcements.size,
                    unreadPostCount = c.announcements.sumOf(::unread),
                    intro = null,
                ),
            ) + c.forums.map { f ->
                MoodleForum(
                    id = f.id,
                    courseId = c.moodleId,
                    type = "general",
                    name = f.name,
                    cmid = f.cmid,
                    discussionCount = f.discussions.size,
                    unreadPostCount = f.discussions.sumOf(::unread),
                    intro = f.intro,
                )
            }
        }

    override suspend fun discussions(forumId: Int, page: Int): List<MoodleDiscussion> {
        val list = moodle.courses.firstOrNull { it.newsForumId == forumId }?.announcements
            ?: moodle.courses.flatMap { it.forums }.firstOrNull { it.id == forumId }?.discussions
            ?: return emptyList()
        return list
            .sortedByDescending { it.modified }
            .drop(page * MoodleCourseApi.DISCUSSIONS_PER_PAGE)
            .take(MoodleCourseApi.DISCUSSIONS_PER_PAGE)
            .map { it.copy(unreadCount = unread(it)) }
    }

    /** A discussion opened once has every post read, as Moodle marks the whole of it. */
    override suspend fun posts(discussionId: Int): List<MoodlePost> {
        val posts = moodle.courses.flatMap { it.forums }.firstNotNullOfOrNull { it.posts[discussionId] } ?: return emptyList()
        return if (discussionId in readIds()) posts.map { it.copy(isUnread = false) } else posts
    }

    override suspend fun materials(courseId: Int): MoodleCourseMaterials =
        course(courseId)?.materials ?: MoodleCourseMaterials(emptyList())

    /** Nothing to count a view toward without a server. */
    override suspend fun markMaterialViewed(material: MoodleMaterial, isCurrent: () -> Boolean) = Unit

    override suspend fun markViewed(discussionId: Int, isCurrent: () -> Boolean) {
        if (isCurrent()) markRead(discussionId)
    }

    override suspend fun members(courseId: Int): MoodleMemberList =
        MoodleMemberList(moodle.ownMemberId, course(courseId)?.members.orEmpty())

    override suspend fun picture(url: String): ByteArray =
        throw java.io.IOException("demo mode has no member pictures")

    override suspend fun gradeItems(courseId: Int): List<MoodleGradeItem> = course(courseId)?.grades.orEmpty()

    override suspend fun summary(courseId: Int): MoodleCourseSummary {
        val course = course(courseId) ?: return MoodleCourseSummary.EMPTY
        return MoodleCourseSummary(
            html = course.summaryHtml,
            text = MoodleHtmlText.plainText(course.summaryHtml),
            names = listOf(course.fullname).filter { it.isNotEmpty() },
            format = null,
            startDate = null,
        )
    }

    private fun assignment(assignId: Int): DemoMoodleAssignment? =
        moodle.courses.asSequence().flatMap { it.assignments }.firstOrNull { it.id == assignId }

    /** As Moodle answers it: the comments and the group's name come from their own calls. */
    override suspend fun submission(assignId: Int): MoodleAssignmentSubmission =
        assignment(assignId)?.submission?.copy(comments = null, groupName = null)
            ?: MoodleAssignmentSubmission(
                status = null, modified = null, files = emptyList(), onlineText = null, id = null,
                takesComments = false, groupId = null, groupName = null, comments = null, grade = null,
            )

    override suspend fun comments(cmid: Int, submissionId: Int): List<MoodleSubmissionComment> =
        moodle.courses.asSequence().flatMap { it.assignments }
            .firstOrNull { it.cmid == cmid && it.submission?.id == submissionId }
            ?.submission?.comments.orEmpty()

    override suspend fun groups(courseId: Int): Map<Int, String> =
        course(courseId)?.assignments.orEmpty().mapNotNull { a ->
            val submission = a.submission ?: return@mapNotNull null
            val id = submission.groupId ?: return@mapNotNull null
            id to submission.groupName.orEmpty()
        }.toMap()

    /**
     * The demo account has no network to fetch a file on: a file the fixture gave content is
     * written from it, and any other fails as before.
     */
    override suspend fun download(file: MoodleFile, target: java.io.File, maxBytes: Long, progress: (Float) -> Unit) {
        val bytes = moodle.content[file.url] ?: throw java.io.IOException("demo mode has no files")
        if (bytes.size > maxBytes) throw java.io.IOException("demo file is larger than $maxBytes bytes")
        target.writeBytes(bytes)
        progress(1f)
    }
}
