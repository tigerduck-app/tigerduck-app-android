package org.ntust.app.tigerduck.ui.screen.coursemoodle

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.moodle.MoodleCacheContext
import org.ntust.app.tigerduck.data.moodle.MoodleCachePort
import org.ntust.app.tigerduck.data.moodle.MoodleCourseStore
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseSummary
import org.ntust.app.tigerduck.network.model.moodle.MoodleFile
import org.ntust.app.tigerduck.network.model.moodle.MoodleGradeItem
import org.ntust.app.tigerduck.network.model.moodle.MoodleMember
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberList
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberRole
import org.ntust.app.tigerduck.network.moodle.MoodleCourseApi
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Type

/**
 * The signed-in student among a course's members (#446, as the iPhone's tigerduck-app#802): their
 * row carries a "Me" tag, read with the name, and only their own page has the pencil that opens
 * Moodle's profile editor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MoodleMembersMeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private fun member(id: Int, studentId: String, name: String, role: String) = MoodleMember(
        id = id, fullName = "$studentId @ $name", idNumber = null, email = null,
        groups = emptyList(), roles = listOf(MoodleMemberRole(role, "", 0)), pictureUrl = null,
        description = "", interests = emptyList(), fields = emptyList(),
    )

    private val teacher = member(1, "T00010001", "Teacher One", "editingteacher")
    private val classmate = member(2, "B00015998", "Student Two", "student")
    private val me = member(3, "B00015999", "Student Three", "student")

    private val api = object : MoodleCourseApi {
        override suspend fun functions() = setOf(MoodleCourseApi.MEMBERS_FUNCTION)
        override suspend fun forums(courseIds: List<Int>) = emptyList<org.ntust.app.tigerduck.network.model.moodle.MoodleForum>()
        override suspend fun discussions(forumId: Int, page: Int) = emptyList<org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussion>()
        override suspend fun markViewed(discussionId: Int, isCurrent: () -> Boolean) = Unit
        override suspend fun members(courseId: Int) = MoodleMemberList(me.id, listOf(teacher, classmate, me))
        override suspend fun picture(url: String) = ByteArray(0)
        override suspend fun gradeItems(courseId: Int) = emptyList<MoodleGradeItem>()
        override suspend fun summary(courseId: Int) = MoodleCourseSummary.EMPTY
        override suspend fun download(file: MoodleFile, target: java.io.File, maxBytes: Long, progress: (Float) -> Unit) = Unit
    }

    private val cache = object : MoodleCachePort {
        override suspend fun <T> load(type: Type, name: String): Pair<T, Long>? = null
        override suspend fun save(value: Any, fetchedAtMs: Long, name: String) = Unit
    }

    private fun string(id: Int) = composeRule.activity.getString(id)

    @Test
    fun `only the signed-in student's row carries the Me tag, read with the name`() {
        val store = MoodleCourseStore(5, emptyList(), emptyList(), api, MoodleCacheContext("B1", cache, { true }), scope)
        composeRule.setContent { MembersList(store = store, query = "", onQueryChange = {}, onOpen = {}) }
        composeRule.waitUntil(5_000) { store.members.value?.members?.size == 3 }
        composeRule.waitForIdle()

        val tag = string(R.string.course_member_me)
        composeRule.onNodeWithContentDescription("Student Three, $tag").assertExists()
        composeRule.onNodeWithContentDescription("Student Two").assertExists()
        composeRule.onNodeWithContentDescription("Teacher One").assertExists()
        assertEquals(1, composeRule.onAllNodes(hasContentDescription(tag, substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun `the pencil that opens Moodle's profile editor is on one's own page only`() {
        var edits = 0
        composeRule.setContent {
            MemberPage(member = me, picture = null, onOpenPicture = null, openingPicture = false, onWrite = {}, onEditOnMoodle = { edits++ })
        }

        composeRule.onNodeWithContentDescription(string(R.string.course_member_edit_on_moodle)).performClick()

        assertEquals(1, edits)
    }

    @Test
    fun `a page is one's own only by the Moodle user the members fetch named`() {
        assertEquals(true, isOwnPage(me, me.id))
        assertEquals(false, isOwnPage(classmate, me.id))
        // Moodle was not asked whose the account is.
        assertEquals(false, isOwnPage(me, null))
    }

    @Test
    fun `the pencil opens Moodle's own profile editor`() {
        assertEquals("https://moodle2.ntust.edu.tw/user/edit.php", ownProfileEditorUrl())
    }

    @Test
    fun `the camera badge on one's own picture picks a new one`() {
        var picks = 0
        composeRule.setContent {
            MemberPage(
                member = me, picture = null, onOpenPicture = null, openingPicture = false, onWrite = {},
                onEditOnMoodle = {}, onChangePicture = { picks++ }, changingPicture = false,
            )
        }

        composeRule.onNodeWithContentDescription(string(R.string.course_member_change_picture)).performClick()

        assertEquals(1, picks)
    }

    @Test
    fun `while the new picture goes up the picture spins and the badge takes no tap`() {
        composeRule.setContent {
            MemberPage(
                member = me, picture = null, onOpenPicture = null, openingPicture = false, onWrite = {},
                onEditOnMoodle = {}, onChangePicture = {}, changingPicture = true,
            )
        }

        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()
        composeRule.onNodeWithContentDescription(string(R.string.course_member_change_picture)).assertIsNotEnabled()
    }

    @Test
    fun `another member's page, or a site that does not allow it, has no camera badge`() {
        composeRule.setContent {
            MemberPage(
                member = classmate, picture = null, onOpenPicture = null, openingPicture = false, onWrite = {},
                onEditOnMoodle = null, onChangePicture = null, changingPicture = false,
            )
        }

        composeRule.onNodeWithContentDescription(string(R.string.course_member_change_picture)).assertDoesNotExist()
    }

    @Test
    fun `a picked photo that cannot be read says so, and one that can opens the crop`() = kotlinx.coroutines.test.runTest {
        var cropped = 0
        var told = 0

        openPickedPicture(decode = { null }, crop = { cropped++ }, unreadable = { told++ })
        assertEquals(0 to 1, cropped to told)

        val photo = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
        openPickedPicture(decode = { photo }, crop = { cropped++ }, unreadable = { told++ })
        assertEquals(1 to 1, cropped to told)
    }

    @Test
    fun `another member's page has no pencil`() {
        composeRule.setContent {
            MemberPage(member = classmate, picture = null, onOpenPicture = null, openingPicture = false, onWrite = {}, onEditOnMoodle = null)
        }

        composeRule.onNodeWithContentDescription(string(R.string.course_member_edit_on_moodle)).assertDoesNotExist()
    }
}
