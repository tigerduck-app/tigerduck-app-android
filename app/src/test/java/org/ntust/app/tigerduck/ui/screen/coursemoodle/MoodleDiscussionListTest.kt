package org.ntust.app.tigerduck.ui.screen.coursemoodle

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.moodle.MoodleCacheContext
import org.ntust.app.tigerduck.data.moodle.MoodleCachePort
import org.ntust.app.tigerduck.data.moodle.MoodleCourseStore
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseSummary
import org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussion
import org.ntust.app.tigerduck.network.model.moodle.MoodleForum
import org.ntust.app.tigerduck.network.model.moodle.MoodleGradeItem
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberList
import org.ntust.app.tigerduck.network.moodle.MoodleCourseApi
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Type

/** The announcements list asks for each next page as its end comes on screen, not only the first. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MoodleDiscussionListTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val news = MoodleForum(1, 5, "news", "News", 10, discussionCount = 60, unreadPostCount = 0, intro = null)

    private val asked = mutableListOf<Int>()

    private val api = object : MoodleCourseApi {
        override suspend fun functions() = setOf(MoodleCourseApi.FORUMS_FUNCTION, MoodleCourseApi.DISCUSSIONS_FUNCTION)
        override suspend fun forums(courseIds: List<Int>) = listOf(news)
        override suspend fun discussions(forumId: Int, page: Int): List<MoodleDiscussion> {
            asked += page
            // Three full pages, each newer than the one after it, so the list keeps its order.
            return if (page > 2) emptyList() else (1..20).map { i ->
                val id = page * 20 + i
                MoodleDiscussion(
                    id, id * 10, "D$id", "", "", created = 10_000L - id, modified = 10_000L - id,
                    isPinned = false, unreadCount = 0, replyCount = 0, authorName = null, authorId = null,
                    attachments = emptyList(),
                )
            }
        }
        override suspend fun markViewed(discussionId: Int, isCurrent: () -> Boolean) = Unit
        override suspend fun members(courseId: Int) = MoodleMemberList(null, emptyList())
        override suspend fun picture(url: String) = ByteArray(0)
        override suspend fun gradeItems(courseId: Int) = emptyList<MoodleGradeItem>()
        override suspend fun summary(courseId: Int) = MoodleCourseSummary.EMPTY
        override suspend fun download(file: org.ntust.app.tigerduck.network.model.moodle.MoodleFile, target: java.io.File, maxBytes: Long, progress: (Float) -> Unit) = Unit
    }

    private val cache = object : MoodleCachePort {
        override suspend fun <T> load(type: Type, name: String): Pair<T, Long>? = null
        override suspend fun save(value: Any, fetchedAtMs: Long, name: String) = Unit
    }

    @Test
    fun `each next page loads as the list's end comes on screen`() {
        val store = MoodleCourseStore(5, emptyList(), emptyList(), api, MoodleCacheContext("B1", cache, { true }), scope)
        val pages = store.discussions(news)
        composeRule.setContent { DiscussionList(pages = pages, onOpen = {}) }
        composeRule.waitUntil(5_000) { pages.list.value?.discussions?.size == 20 }

        composeRule.onNodeWithTag(DISCUSSION_LIST_TAG).performScrollToIndex(19)
        composeRule.waitUntil(5_000) { pages.list.value?.discussions?.size == 40 }

        composeRule.onNodeWithTag(DISCUSSION_LIST_TAG).performScrollToIndex(39)
        composeRule.waitUntil(5_000) { pages.list.value?.discussions?.size == 60 }

        assertEquals(listOf(0, 1, 2), asked)
    }
}
