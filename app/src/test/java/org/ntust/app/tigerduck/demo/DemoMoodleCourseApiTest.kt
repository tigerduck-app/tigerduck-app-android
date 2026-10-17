package org.ntust.app.tigerduck.demo

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.network.moodle.MoodleCourseApi
import java.io.File

class DemoMoodleCourseApiTest {

    private val moodle = DemoFixture.parse(File("src/main/assets/demo.json").readText(), DemoFixture.LANG_EN).moodle!!
    private val course = moodle.courses.first { it.announcements.any { a -> a.unreadCount > 0 } }
    private val unreadId = course.announcements.first { it.unreadCount > 0 }.id

    @Test
    fun `an announcement read once stays read for the next start`() = runTest {
        val stored = mutableSetOf<Int>()
        DemoMoodleCourseApi(moodle, readIds = { stored }, markRead = { stored += it })
            .markViewed(unreadId) { true }

        // A new API over the same store, as after the app restarts.
        val next = DemoMoodleCourseApi(moodle, readIds = { stored }, markRead = { stored += it })
        val page = next.discussions(course.newsForumId, 0)

        assertEquals(0, page.first { it.id == unreadId }.unreadCount)
        val forum = next.forums(listOf(course.moodleId)).single { it.isNews }
        assertEquals(course.announcements.count { it.unreadCount > 0 } - 1, forum.unreadPostCount)
    }

    @Test
    fun `a view logged after a sign-out is not kept`() = runTest {
        val stored = mutableSetOf<Int>()
        DemoMoodleCourseApi(moodle, readIds = { stored }, markRead = { stored += it }).markViewed(unreadId) { false }
        assertEquals(emptySet<Int>(), stored)
    }

    @Test
    fun `the demo student's submissions, comments and groups answer from the file, as Moodle would`() = runTest {
        val api = DemoMoodleCourseApi(moodle, readIds = { emptySet() }, markRead = {})
        val handedIn = moodle.courses.flatMap { c -> c.assignments.map { c to it } }
            .first { (_, a) -> a.submission?.isSubmitted == true && !a.submission.comments.isNullOrEmpty() }
        val (course, page) = handedIn

        assertTrue(api.functions().containsAll(listOf(MoodleCourseApi.COMMENTS_FUNCTION, MoodleCourseApi.GROUPS_FUNCTION)))
        val submission = api.submission(page.id)
        assertTrue(submission.isSubmitted)
        assertEquals("the comments and the group's name come from their own calls", null, submission.comments)
        assertEquals(null, submission.groupName)
        assertEquals(page.submission!!.comments, api.comments(page.cmid, submission.id!!))
        page.submission.groupId?.let { assertEquals(page.submission.groupName, api.groups(course.moodleId)[it]) }

        val notHandedIn = moodle.courses.flatMap { it.assignments }.first { it.submission?.isSubmitted != true }
        assertFalse(api.submission(notHandedIn.id).isSubmitted)
        // One with no record on Moodle, as an assignment the file does not describe.
        assertFalse(api.submission(1).isSubmitted)
        assertEquals(null, api.submission(1).id)
    }

    private val dataStructures = moodle.courses.first { it.forums.isNotEmpty() }

    @Test
    fun `the demo course has a forum with a thread to read, its replies nested and one deleted`() = runTest {
        val api = DemoMoodleCourseApi(moodle, readIds = { emptySet() }, markRead = {})
        val functions = api.functions()
        assertTrue(org.ntust.app.tigerduck.network.moodle.MoodleCourseApi.DISCUSSION_POSTS_FUNCTION in functions)

        val general = api.forums(listOf(dataStructures.moodleId)).single { !it.isNews }
        val discussions = api.discussions(general.id, 0)
        assertEquals(general.discussionCount, discussions.size)
        val thread = discussions.maxByOrNull { it.replyCount }!!
        val posts = api.posts(thread.id)

        assertEquals(thread.replyCount, posts.size - 1)
        assertEquals(thread.message, posts.first().message)
        assertEquals("the first post carries the discussion's title", thread.title, posts.first().subject)
        assertEquals(posts.maxOf { it.created!! }, thread.modified)
        assertTrue(posts.any { it.isDeleted })
        assertTrue("replies nest deeper than the three levels shown", org.ntust.app.tigerduck.data.moodle.MoodleThreads
            .entries(thread, org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussionThread(thread.id, posts))
            .any { it.replyingTo != null })
        assertTrue(posts.flatMap { it.attachments }.all { org.ntust.app.tigerduck.network.moodle.MoodleFileUrls.isMoodleFile(it.url) })
        assertEquals(posts.count { it.isUnread }, thread.unreadCount)
        assertEquals(discussions.sumOf { it.unreadCount }, general.unreadPostCount)
    }

    @Test
    fun `a thread read once stays read, its posts and the forum's count with it`() = runTest {
        val stored = mutableSetOf<Int>()
        val api = DemoMoodleCourseApi(moodle, readIds = { stored }, markRead = { stored += it })
        val general = api.forums(listOf(dataStructures.moodleId)).single { !it.isNews }
        val thread = api.discussions(general.id, 0).first { it.unreadCount > 0 }

        api.markViewed(thread.id) { true }

        assertTrue(api.posts(thread.id).none { it.isUnread })
        assertEquals(0, api.discussions(general.id, 0).first { it.id == thread.id }.unreadCount)
        assertEquals(general.unreadPostCount - thread.unreadCount, api.forums(listOf(dataStructures.moodleId)).single { !it.isNews }.unreadPostCount)
    }

    @Test
    fun `the demo course's materials hold files, a folder and links`() = runTest {
        val api = DemoMoodleCourseApi(moodle, readIds = { emptySet() }, markRead = {})
        val materials = api.materials(dataStructures.moodleId)
        val all = materials.sections.flatMap { it.materials }

        assertTrue(all.any { it.isFolder && it.files.size > 1 })
        assertTrue(all.any { it.isLink && it.link!!.startsWith("https://") })
        assertTrue(all.filter { !it.isLink }.flatMap { it.files }.all {
            org.ntust.app.tigerduck.network.moodle.MoodleFileUrls.isMoodleFile(it.url) && it.modified != null
        })
        assertTrue(org.ntust.app.tigerduck.network.moodle.MoodleCourseApi.CONTENTS_FUNCTION in api.functions())
        assertTrue("another course has none", api.materials(-1).sections.isEmpty())
    }
}
