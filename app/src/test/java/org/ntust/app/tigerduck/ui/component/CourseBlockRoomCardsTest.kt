package org.ntust.app.tigerduck.ui.component

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.shared.Course
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The room the in-app course cards and the 衝堂 picker show for a course meeting twice a day in
 * two rooms: the block's, not both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en-rUS")
class CourseBlockRoomCardsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // Monday 1-2 in TR-513, 6-7 in TR-215.
    private val twice = Course.fromSchedule(
        courseNo = "CS3005301",
        courseName = "Data Structures",
        classroom = "TR-513, TR-215",
        schedule = mapOf(1 to listOf("1", "2", "6", "7")),
        classroomMap = mapOf("1-1" to "TR-513", "1-2" to "TR-513", "1-6" to "TR-215", "1-7" to "TR-215"),
    )
    private val other = Course.fromSchedule(
        "PH1002301", "Physics",
        classroom = "TR-101",
        schedule = mapOf(1 to listOf("7")),
        classroomMap = mapOf("1-7" to "TR-101"),
    )

    @Test
    fun `a course card shows its block's room`() {
        composeRule.setContent {
            CourseCard(course = twice, weekday = 1, periods = listOf("6", "7"), onClick = {})
        }

        composeRule.onNodeWithText("TR-215").assertExists()
    }

    @Test
    fun `the current class card shows the room of the block going on`() {
        composeRule.setContent {
            CurrentClassCard(
                course = twice,
                blockStartMinute = 8 * 60 + 10,
                blockEndMinute = 10 * 60,
                currentMinute = 9 * 60,
                weekday = 1,
                periods = listOf("1", "2"),
                onClick = {},
            )
        }

        composeRule.onNodeWithText("TR-513").assertExists()
    }

    @Test
    fun `the conflict picker shows each course's room at the tapped tile`() {
        composeRule.setContent {
            ConflictCoursePickerSheet(
                courseA = other,
                courseB = twice,
                weekday = 1,
                periodId = "7",
                onPick = {},
                onDismiss = {},
            )
        }

        composeRule.onNodeWithText("CS3005301 · TR-215").assertExists()
    }
}
