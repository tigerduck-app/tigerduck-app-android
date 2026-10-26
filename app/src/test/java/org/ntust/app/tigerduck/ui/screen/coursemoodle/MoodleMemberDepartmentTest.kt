package org.ntust.app.tigerduck.ui.screen.coursemoodle

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.data.moodle.MoodleCacheContext
import org.ntust.app.tigerduck.data.moodle.MoodleCachePort
import org.ntust.app.tigerduck.data.moodle.MoodleCourseStore
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseSummary
import org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussion
import org.ntust.app.tigerduck.network.model.moodle.MoodleFile
import org.ntust.app.tigerduck.network.model.moodle.MoodleForum
import org.ntust.app.tigerduck.network.model.moodle.MoodleGradeItem
import org.ntust.app.tigerduck.network.model.moodle.MoodleMember
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberList
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberRole
import org.ntust.app.tigerduck.network.moodle.MoodleCourseApi
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Type

/**
 * A member's department comes from the department code in their student ID (#697), never from
 * Moodle's `department` field, which NTUST fills for the account alone and with the degree level:
 * the member's page names it in full, the list in short, and the search matches what the list shows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MoodleMemberDepartmentTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private fun student(id: Int, fullName: String) = MoodleMember(
        id = id, fullName = fullName, idNumber = null, email = null,
        groups = emptyList(), roles = listOf(MoodleMemberRole("student", "", 0)), pictureUrl = null,
        description = "", interests = emptyList(), fields = emptyList(),
    )

    private fun api(members: List<MoodleMember>) = object : MoodleCourseApi {
        override suspend fun functions() = setOf(MoodleCourseApi.MEMBERS_FUNCTION)
        override suspend fun forums(courseIds: List<Int>) = emptyList<MoodleForum>()
        override suspend fun discussions(forumId: Int, page: Int) = emptyList<MoodleDiscussion>()
        override suspend fun markViewed(discussionId: Int, isCurrent: () -> Boolean) = Unit
        override suspend fun members(courseId: Int) = MoodleMemberList(0, members)
        override suspend fun picture(url: String) = ByteArray(0)
        override suspend fun gradeItems(courseId: Int) = emptyList<MoodleGradeItem>()
        override suspend fun summary(courseId: Int) = MoodleCourseSummary.EMPTY
        override suspend fun download(file: MoodleFile, target: java.io.File, maxBytes: Long, progress: (Float) -> Unit) = Unit
    }

    private val cache = object : MoodleCachePort {
        override suspend fun <T> load(type: Type, name: String): Pair<T, Long>? = null
        override suspend fun save(value: Any, fetchedAtMs: Long, name: String) = Unit
    }

    private fun stateDescription(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    @Test
    fun `the members request does not ask Moodle for its department field`() {
        assertFalse(MoodleCourseApi.MEMBER_FIELDS, "department" in MoodleCourseApi.MEMBER_FIELDS.split(","))
    }

    @Test
    fun `every department the app knows has a name for the page and one for the list`() {
        val expected = mapOf(
            "ad" to (R.string.course_member_department_ad to R.string.course_member_department_ad_short),
            "ba" to (R.string.course_member_department_ba to R.string.course_member_department_ba_short),
            "ch" to (R.string.course_member_department_ch to R.string.course_member_department_ch_short),
            "cs" to (R.string.course_member_department_cs to R.string.course_member_department_cs_short),
            "ct" to (R.string.course_member_department_ct to R.string.course_member_department_ct_short),
            "dt" to (R.string.course_member_department_dt to R.string.course_member_department_dt_short),
            "ee" to (R.string.course_member_department_ee to R.string.course_member_department_ee_short),
            "et" to (R.string.course_member_department_et to R.string.course_member_department_et_short),
            "hc" to (R.string.course_member_department_hc to R.string.course_member_department_hc_short),
            "im" to (R.string.course_member_department_im to R.string.course_member_department_im_short),
            "me" to (R.string.course_member_department_me to R.string.course_member_department_me_short),
            "tx" to (R.string.course_member_department_tx to R.string.course_member_department_tx_short),
        )
        assertEquals(
            expected,
            expected.keys.associateWith { departmentNameRes(it, short = false) to departmentNameRes(it, short = true) },
        )
        assertNull(departmentNameRes("zz", short = false))
        assertNull(departmentNameRes("zz", short = true))
    }

    @Test
    fun `the page names the department in full from the ID`() {
        val members = listOf(
            student(1, "B00015001 @ Amy"),
            student(2, "M00015002 @ Max"),
            student(3, "B00009003 @ Uma"),
        )
        var rows: Map<Int, List<MemberRow>> = emptyMap()
        composeRule.setContent { rows = members.associate { it.id to memberRows(it) } }
        composeRule.waitForIdle()

        val studentId = composeRule.activity.getString(R.string.sign_in_student_id)
        val department = composeRule.activity.getString(R.string.course_member_department)
        assertEquals(
            listOf(
                MemberRow(studentId, "B00015001"),
                MemberRow(department, "Department of Computer Science and Information Engineering"),
            ),
            rows[1],
        )
        assertEquals("a master's ID has no code", listOf(MemberRow(studentId, "M00015002")), rows[2])
        assertEquals("a code the app does not know", listOf(MemberRow(studentId, "B00009003")), rows[3])
    }

    @Test
    fun `the list shows the short name beside the ID, and the search matches it`() {
        val members = listOf(student(1, "B00015001 @ Amy"), student(2, "B00000002 @ Bob"))
        val store = MoodleCourseStore(5, emptyList(), emptyList(), api(members), MoodleCacheContext("B1", cache, { true }), scope)
        var query by mutableStateOf("")
        composeRule.setContent {
            MembersList(store = store, query = query, onQueryChange = { query = it }, onOpen = {})
        }
        composeRule.waitUntil(5_000) { store.members.value?.members?.size == 2 }
        composeRule.waitForIdle()

        composeRule.onNode(hasContentDescription("Amy"))
            .assert(stateDescription("B00015001 · Computer Science and Information Engineering"))
        composeRule.onNode(hasContentDescription("Bob")).assert(stateDescription("B00000002"))

        query = "computer science"
        composeRule.waitForIdle()
        composeRule.onNode(hasContentDescription("Amy")).assertExists()
        composeRule.onNode(hasContentDescription("Bob")).assertDoesNotExist()
    }
}
