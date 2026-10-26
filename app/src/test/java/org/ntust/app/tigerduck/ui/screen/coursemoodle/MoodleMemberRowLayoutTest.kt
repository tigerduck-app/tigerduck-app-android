package org.ntust.app.tigerduck.ui.screen.coursemoodle

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.moodle.MoodleCacheContext
import org.ntust.app.tigerduck.data.moodle.MoodleCachePort
import org.ntust.app.tigerduck.data.moodle.MoodleCourseStore
import org.ntust.app.tigerduck.data.moodle.name
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseSummary
import org.ntust.app.tigerduck.network.model.moodle.MoodleFile
import org.ntust.app.tigerduck.network.model.moodle.MoodleGradeItem
import org.ntust.app.tigerduck.network.model.moodle.MoodleMember
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberList
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberRole
import org.ntust.app.tigerduck.network.moodle.MoodleCourseApi
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.reflect.Type
import kotlin.math.abs

/**
 * A members list row whose line under the name holds a department: in Chinese the ID's digits and
 * the department's characters come from fonts of different heights, and the row must still be a
 * two-line row like the others, its picture and arrow centered, not a three-line one laid out from
 * the top.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
// Real fonts, so the digits and the Chinese characters each get their own font's metrics.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MoodleMemberRowLayoutTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private fun member(id: Int, studentId: String, name: String) = MoodleMember(
        id = id, fullName = "$studentId @ $name", idNumber = null, email = null,
        groups = emptyList(), roles = listOf(MoodleMemberRole("student", "", 0)), pictureUrl = null,
        description = "", interests = emptyList(), fields = emptyList(),
    )

    // Code 15 names a department; 99 names none, so that row's line is the ID alone.
    private val me = member(3, "B00015999", "測試學生")
    private val classmate = member(2, "B00099998", "測試同學")

    private val api = object : MoodleCourseApi {
        override suspend fun functions() = setOf(MoodleCourseApi.MEMBERS_FUNCTION)
        override suspend fun forums(courseIds: List<Int>) = emptyList<org.ntust.app.tigerduck.network.model.moodle.MoodleForum>()
        override suspend fun discussions(forumId: Int, page: Int) = emptyList<org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussion>()
        override suspend fun markViewed(discussionId: Int, isCurrent: () -> Boolean) = Unit
        override suspend fun members(courseId: Int) = MoodleMemberList(me.id, listOf(classmate, me))
        override suspend fun picture(url: String) = ByteArray(0)
        override suspend fun gradeItems(courseId: Int) = emptyList<MoodleGradeItem>()
        override suspend fun summary(courseId: Int) = MoodleCourseSummary.EMPTY
        override suspend fun download(file: MoodleFile, target: java.io.File, maxBytes: Long, progress: (Float) -> Unit) = Unit
    }

    private val cache = object : MoodleCachePort {
        override suspend fun <T> load(type: Type, name: String): Pair<T, Long>? = null
        override suspend fun save(value: Any, fetchedAtMs: Long, name: String) = Unit
    }

    private fun showList() {
        val store = MoodleCourseStore(5, emptyList(), emptyList(), api, MoodleCacheContext("B1", cache, { true }), scope)
        composeRule.setContent { MembersList(store = store, query = "", onQueryChange = {}, onOpen = {}) }
        composeRule.waitUntil(5_000) { store.members.value?.members?.size == 2 }
        composeRule.waitForIdle()
    }

    private fun ownRow() = composeRule.onNodeWithContentDescription(
        "${me.name}, ${composeRule.activity.getString(R.string.course_member_me)}",
    )

    private fun classmateRow() = composeRule.onNodeWithContentDescription(classmate.name)

    /** The rows between [fromX] and [toX] that hold anything but the row's background. */
    private fun PixelMap.drawnRows(fromX: Int, toX: Int): IntRange {
        val background = this[2, height / 2]
        val rows = (0 until height).filter { y -> (fromX until toX).any { x -> this[x, y] != background } }
        return rows.first()..rows.last()
    }

    private fun assertCentered(what: String, drawn: IntRange, height: Int) {
        val center = (drawn.first + drawn.last) / 2f
        assertTrue("$what is centered in the $height-high row, not at $drawn", abs(center - (height - 1) / 2f) <= 1.5f)
    }

    private fun assertOwnRowLikeTheOthers() {
        showList()
        val own = ownRow().fetchSemanticsNode().boundsInRoot
        val other = classmateRow().fetchSemanticsNode().boundsInRoot
        assertEquals("the own row is as high as a two-line row", other.height, own.height, 0.5f)

        val row = ownRow().captureToImage().toPixelMap()
        // The picture is 40 wide after the row's 16 of padding; the arrow is 24 wide before its 16
        // (left to right, as these locales are).
        val px = { dp: Int -> with(composeRule.density) { dp.dp.roundToPx() } }
        assertCentered("the picture", row.drawnRows(px(16), px(56)), row.height)
        assertCentered("the arrow", row.drawnRows(row.width - px(40), row.width - px(16)), row.height)
    }

    @Test
    @Config(qualifiers = "zh-rTW")
    fun `in Chinese the own row with a department is a two-line row, its picture and arrow centered`() =
        assertOwnRowLikeTheOthers()

    @Test
    fun `in English the own row with a department is a two-line row, its picture and arrow centered`() =
        assertOwnRowLikeTheOthers()

    @Test
    @Config(qualifiers = "zh-rTW")
    fun `the line under a name has one baseline when its department is Chinese`() {
        var first = -1
        var last = -2
        composeRule.setContent {
            Layout(content = { MemberSubline("B00015999", "資訊工程系") }) { measurables, constraints ->
                val placeable = measurables.single().measure(constraints)
                first = placeable[FirstBaseline]
                last = placeable[LastBaseline]
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
        }
        composeRule.waitForIdle()

        // Material's ListItem takes a supporting line whose first and last baselines differ for
        // two lines of text, and lays the row out as a three-line one.
        assertEquals("one line of text has one baseline", first, last)
    }
}
