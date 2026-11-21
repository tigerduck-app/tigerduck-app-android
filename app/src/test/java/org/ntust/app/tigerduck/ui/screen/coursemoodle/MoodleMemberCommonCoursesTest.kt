package org.ntust.app.tigerduck.ui.screen.coursemoodle

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.moodle.MoodleCommonCourse
import org.ntust.app.tigerduck.data.moodle.MoodleCommonTerm
import org.ntust.app.tigerduck.network.model.moodle.MoodleMember
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A member's page lists the courses in common under a heading of its own, a heading per term, as
 * the iPhone's (tigerduck-app#984): each row read whole and doing nothing on a tap; a page with
 * none has no such heading. Made-up names and course numbers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MoodleMemberCommonCoursesTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val member = MoodleMember(
        id = 43, fullName = "B11399002 @ Duck Tiger", idNumber = "B11399002", email = null, groups = emptyList(),
        roles = emptyList(), pictureUrl = null, description = "", interests = emptyList(), fields = emptyList(),
    )

    private val terms = listOf(
        MoodleCommonTerm(
            "1141",
            listOf(
                MoodleCommonCourse(1, "Algorithms", listOf("CS2001301", "Wang"), "CS2001301"),
                MoodleCommonCourse(2, "Ethics", listOf("GE3105701"), "GE3105701"),
            ),
        ),
        MoodleCommonTerm("113H", listOf(MoodleCommonCourse(3, "Swimming", listOf("PE1001301"), "PE1001301"))),
        MoodleCommonTerm(null, listOf(MoodleCommonCourse(900, "Department Notices", emptyList(), "900"))),
    )

    private fun page(commonCourses: List<MoodleCommonTerm>) = composeRule.setContent {
        MemberPage(
            member = member, picture = null, onOpenPicture = null, openingPicture = false, onWrite = {},
            onEditOnMoodle = null, commonCourses = commonCourses,
        )
    }

    /** Unclipped, so a node scrolled out of the window keeps its place. */
    private fun top(text: String) = composeRule.onNode(hasText(text)).fetchSemanticsNode().positionInRoot.y

    @Test
    fun `the courses in common list by term under headings, each row read whole and not a button`() {
        page(terms)
        val title = composeRule.activity.getString(R.string.course_member_common_courses)
        val other = composeRule.activity.getString(R.string.course_member_common_courses_other)

        val headings = listOf(title, "114-1", "113-H", other)
        headings.forEach { composeRule.onNode(hasText(it)).assert(isHeading()) }
        assertEquals(headings, headings.sortedBy(::top))
        for ((name, details) in listOf(
            "Algorithms" to "CS2001301 · Wang", "Ethics" to "GE3105701", "Swimming" to "PE1001301",
        )) {
            composeRule.onNode(hasText(name) and hasText(details)).assert(!hasClickAction())
        }
        composeRule.onNode(hasText("Department Notices")).assert(!hasClickAction())
        val rows = listOf("Algorithms", "Ethics", "Swimming", "Department Notices")
        assertEquals(rows, rows.reversed().sortedBy(::top))
    }

    @Test
    fun `a page with no courses in common has no such heading`() {
        page(emptyList())

        composeRule.onNode(hasText(composeRule.activity.getString(R.string.course_member_common_courses))).assertDoesNotExist()
    }
}
