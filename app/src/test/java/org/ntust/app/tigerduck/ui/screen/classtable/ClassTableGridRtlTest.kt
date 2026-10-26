package org.ntust.app.tigerduck.ui.screen.classtable

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.shared.Course
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Each weekday label sits over its own column's courses, in a right-to-left language as well
 * (tigerduck-app-android#309). The courses are placed at absolute offsets, which do not mirror,
 * but the header was a `Row`, which does, so Monday's label went to the right; and the boxes the
 * offsets start from put each child at their right edge first, so the offsets pushed the courses,
 * the lanes of a 衝堂 cluster included, off the screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ClassTableGridRtlTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun course(no: String, name: String, schedule: Map<Int, List<String>>) =
        Course.fromSchedule(courseNo = no, courseName = name, schedule = schedule)

    // Alpha, Bravo and Charlie chain into one 衝堂 cluster on Monday, drawn as two lanes.
    private val courses = listOf(
        course("CS1", "Alpha", mapOf(1 to listOf("6", "7"))),
        course("CS2", "Bravo", mapOf(1 to listOf("6", "7", "8"))),
        course("CS3", "Charlie", mapOf(1 to listOf("8", "9"))),
        course("CS4", "Delta", mapOf(2 to listOf("1", "2"))),
    )

    private fun show(direction: LayoutDirection) {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                TimetableGrid(
                    courses = courses,
                    showRoomHints = false,
                    weekdays = ClassTableCellLayout.activeWeekdays(courses),
                    periods = ClassTableCellLayout.activePeriods(courses),
                    courseNosWithAssignments = emptySet(),
                )
            }
        }
    }

    private fun bounds(name: String): DpRect =
        composeRule.onNodeWithContentDescription(name, substring = true).getUnclippedBoundsInRoot()

    private fun centre(label: String): Float =
        composeRule.onNodeWithText(label).getUnclippedBoundsInRoot().let { (it.left + it.right).value / 2 }

    /** Whether [name]'s cell lies inside the column under the label centred at [centre]. */
    private fun assertUnder(name: String, centre: Float, columnWidth: Float) {
        val cell = bounds(name)
        val (left, right) = centre - columnWidth / 2 - 1 to centre + columnWidth / 2 + 1
        assertTrue(
            "$name runs ${cell.left}..${cell.right}, its column $left..$right",
            cell.left.value >= left && cell.right.value <= right,
        )
    }

    private fun assertEachCourseUnderItsWeekday() {
        val monday = centre("Mon")
        val tuesday = centre("Tue")
        assertTrue("Tuesday is right of Monday", tuesday > monday)
        val columnWidth = tuesday - monday
        for (name in listOf("Alpha", "Bravo", "Charlie")) assertUnder(name, monday, columnWidth)
        assertUnder("Delta", tuesday, columnWidth)
    }

    @Test
    fun `each course sits under its weekday's label`() {
        show(LayoutDirection.Ltr)
        assertEachCourseUnderItsWeekday()
    }

    @Test
    fun `in a right-to-left language each course still sits under its weekday's label`() {
        show(LayoutDirection.Rtl)
        assertEachCourseUnderItsWeekday()
    }
}
