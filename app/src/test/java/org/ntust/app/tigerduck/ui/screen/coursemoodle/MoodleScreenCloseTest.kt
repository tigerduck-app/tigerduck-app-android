package org.ntust.app.tigerduck.ui.screen.coursemoodle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.moodle.MoodleCacheContext
import org.ntust.app.tigerduck.data.moodle.MoodleCachePort
import org.ntust.app.tigerduck.data.moodle.MoodleCourseStore
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseSummary
import org.ntust.app.tigerduck.network.model.moodle.MoodleFile
import org.ntust.app.tigerduck.network.model.moodle.MoodleGradeItem
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberList
import org.ntust.app.tigerduck.network.moodle.MoodleCourseApi
import java.lang.reflect.Type

/** A Moodle page that comes back into view after the account changed must not show the last one's data. */
class MoodleScreenCloseTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    private var current = true

    private val store = MoodleCourseStore(
        courseId = 5, termCourseIds = emptyList(), courseCodes = emptyList(),
        api = object : MoodleCourseApi {
            override suspend fun functions() = emptySet<String>()
            override suspend fun forums(courseIds: List<Int>) = emptyList<org.ntust.app.tigerduck.network.model.moodle.MoodleForum>()
            override suspend fun discussions(forumId: Int, page: Int) = emptyList<org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussion>()
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
            isCurrent = { current },
        ),
        scope = scope,
    )

    @Test
    fun `a page closes once its account has signed out or another signed in`() {
        assertFalse(moodleScreenShouldClose(signedIn = true, store = store))
        assertFalse("no store yet is not a reason to close", moodleScreenShouldClose(signedIn = true, store = null))
        assertTrue(moodleScreenShouldClose(signedIn = false, store = store))

        current = false
        assertTrue("signed back in as someone else", moodleScreenShouldClose(signedIn = true, store = store))
    }
}
