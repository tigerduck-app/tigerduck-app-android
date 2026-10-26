package org.ntust.app.tigerduck.ui.screen.classtable

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.shared.Course
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What TalkBack reads for a class table tile: the weekday and period come
 * first, so a course meeting on Monday and Wednesday sounds different on each.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en-rUS")
class ClassTableCellLabelTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val course = Course.fromSchedule(
        courseNo = "CS2001301",
        courseName = "Data Structures",
        classroom = "TR-313, TR-412",
        schedule = mapOf(1 to listOf("3", "4", "7", "8"), 3 to listOf("3")),
        classroomMap = mapOf(
            "1-3" to "TR-313", "1-4" to "TR-313",
            "1-7" to "TR-412", "1-8" to "TR-412",
            "3-3" to "TR-313",
        ),
    )

    private fun show(weekday: Int, periodId: String) {
        composeRule.setContent {
            Box(Modifier.size(200.dp)) {
                SoloCourseCell(
                    course = course,
                    roomHint = null,
                    spanCount = 2,
                    dayColWidth = 80.dp,
                    cellHeight = 52.dp,
                    x = 0.dp,
                    y = 0.dp,
                    weekday = weekday,
                    periodId = periodId,
                    hasAssignment = true,
                    assignmentsIgnored = false,
                    onTap = {},
                    onLongPress = {},
                    onRename = {},
                    onPickColor = {},
                    onToggleIgnoreAssignments = null,
                    onDelete = {},
                )
            }
        }
    }

    @Test
    fun `a tile says its weekday, period and the room of that slot`() {
        show(weekday = 1, periodId = "7")

        composeRule
            .onNodeWithContentDescription("Mon period 7: Data Structures, TR-412. Has assignment")
            .assertExists()
    }

    @Test
    fun `the same course on another day says that day`() {
        show(weekday = 3, periodId = "3")

        composeRule
            .onNodeWithContentDescription("Wed period 3: Data Structures, TR-313. Has assignment")
            .assertExists()
    }

    @Test
    fun `a tile given no period reads as before, with the day's rooms`() {
        // As the callers that predate periodId call it.
        composeRule.setContent {
            Box(Modifier.size(200.dp)) {
                SoloCourseCell(
                    course = course,
                    roomHint = null,
                    spanCount = 2,
                    dayColWidth = 80.dp,
                    cellHeight = 52.dp,
                    x = 0.dp,
                    y = 0.dp,
                    weekday = 1,
                    hasAssignment = false,
                    assignmentsIgnored = false,
                    onTap = {},
                    onLongPress = {},
                    onRename = {},
                    onPickColor = {},
                    onToggleIgnoreAssignments = null,
                    onDelete = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Data Structures, TR-313, TR-412").assertExists()
    }
}

