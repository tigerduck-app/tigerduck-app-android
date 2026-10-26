package org.ntust.app.tigerduck.ui.screen.coursemoodle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.data.moodle.MoodleCacheContext
import org.ntust.app.tigerduck.data.moodle.MoodleCachePort
import org.ntust.app.tigerduck.data.moodle.MoodleCourseStore
import org.ntust.app.tigerduck.data.moodle.MoodleSectionRow
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseSummary
import org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussion
import org.ntust.app.tigerduck.network.model.moodle.MoodleFile
import org.ntust.app.tigerduck.network.model.moodle.MoodleForum
import org.ntust.app.tigerduck.network.model.moodle.MoodleGradeItem
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberList
import org.ntust.app.tigerduck.network.moodle.MoodleCourseApi
import java.lang.reflect.Type

/** Where each row of a course's Moodle section leads. */
class MoodleSectionRouteTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    private var forums = listOf<MoodleForum>()

    private fun forum(id: Int, type: String = "general", count: Int = 2) =
        MoodleForum(id, courseId = 5, type = type, name = "F$id", cmid = id * 10, discussionCount = count, unreadPostCount = 0, intro = null)

    private val store = MoodleCourseStore(
        courseId = 5, termCourseIds = emptyList(), courseCodes = emptyList(),
        api = object : MoodleCourseApi {
            override suspend fun functions() = setOf(
                MoodleCourseApi.FORUMS_FUNCTION, MoodleCourseApi.DISCUSSIONS_FUNCTION, MoodleCourseApi.DISCUSSION_POSTS_FUNCTION,
            )
            override suspend fun forums(courseIds: List<Int>) = forums
            override suspend fun discussions(forumId: Int, page: Int) = emptyList<MoodleDiscussion>()
            override suspend fun markViewed(discussionId: Int, isCurrent: () -> Boolean) = Unit
            override suspend fun members(courseId: Int) = MoodleMemberList(null, emptyList())
            override suspend fun picture(url: String) = ByteArray(0)
            override suspend fun gradeItems(courseId: Int) = emptyList<MoodleGradeItem>()
            override suspend fun summary(courseId: Int) = MoodleCourseSummary.EMPTY
            override suspend fun download(file: MoodleFile, target: java.io.File, maxBytes: Long, progress: (Float) -> Unit) = Unit
        },
        context = MoodleCacheContext(
            "B1",
            object : MoodleCachePort {
                override suspend fun <T> load(type: Type, name: String): Pair<T, Long>? = null
                override suspend fun save(value: Any, fetchedAtMs: Long, name: String) = Unit
            },
            isCurrent = { true },
        ),
        scope = scope,
    )

    @Test
    fun `the forums row opens a course's only forum itself, and the list of several`() = runBlocking {
        forums = listOf(forum(1, type = "news"), forum(2), forum(3, count = 0))
        store.refreshForums()
        assertEquals("courseMoodle/5/forums/2", route(MoodleSectionRow.Kind.FORUMS, store))

        forums = listOf(forum(1, type = "news"), forum(2), forum(4))
        store.refreshForums()
        assertEquals("courseMoodle/5/forums", route(MoodleSectionRow.Kind.FORUMS, store))
    }

    @Test
    fun `the materials row opens the course's materials`() {
        assertEquals("courseMoodle/5/materials", route(MoodleSectionRow.Kind.MATERIALS, store))
    }
}
