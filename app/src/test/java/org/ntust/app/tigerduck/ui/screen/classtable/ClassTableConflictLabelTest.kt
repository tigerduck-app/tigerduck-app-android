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
 * What TalkBack reads for the two courses of a 衝堂 tile: each names the period its own block
 * starts at, and that period's room.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en-rUS")
class ClassTableConflictLabelTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // A on 3-4, B on 4-5 on Monday: one cluster of three rows, B starting a row in.
    private val a = Course.fromSchedule(
        "CS1001301", "Calculus",
        classroom = "TR-313",
        schedule = mapOf(1 to listOf("3", "4")),
        classroomMap = mapOf("1-3" to "TR-313", "1-4" to "TR-313"),
    )
    private val b = Course.fromSchedule(
        "PH1002301", "Physics",
        classroom = "TR-412, TR-413",
        schedule = mapOf(1 to listOf("4", "5"), 3 to listOf("3")),
        classroomMap = mapOf("1-4" to "TR-412", "1-5" to "TR-412", "3-3" to "TR-413"),
    )

    @Test
    fun `the later course names its own first period and that period's room`() {
        composeRule.setContent {
            Box(Modifier.size(300.dp)) {
                ConflictCourseCell(
                    cellRole = CellRole.ConflictStart(
                        courseA = a, spanA = 2, offsetA = 0,
                        courseB = b, spanB = 2, offsetB = 1,
                        combinedSpan = 3,
                    ),
                    dayColWidth = 80.dp,
                    cellHeight = 52.dp,
                    x = 0.dp,
                    y = 0.dp,
                    weekday = 1,
                    periodId = "3",
                    clusterPeriods = listOf("3", "4", "5"),
                    hasAssignmentA = false,
                    hasAssignmentB = false,
                    assignmentsIgnoredA = false,
                    assignmentsIgnoredB = false,
                    onPickConflict = { _, _, _, _ -> },
                    onLongPress = {},
                    onRename = {},
                    onPickColor = {},
                    onToggleIgnoreAssignments = null,
                    onDelete = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Class conflict: Mon period 3: Calculus, TR-313").assertExists()
        composeRule.onNodeWithContentDescription("Class conflict: Mon period 4: Physics, TR-412").assertExists()
    }
}
