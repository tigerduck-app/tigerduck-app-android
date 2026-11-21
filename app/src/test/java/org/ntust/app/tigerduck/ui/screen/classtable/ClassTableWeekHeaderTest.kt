package org.ntust.app.tigerduck.ui.screen.classtable

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture
import org.ntust.app.tigerduck.ui.theme.LocalVisualStylePolicy
import org.ntust.app.tigerduck.ui.theme.VisualPreset
import org.ntust.app.tigerduck.ui.theme.VisualStylePolicy
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The term's week in the header corner left of Monday, as iOS `ClassTableWeekHeaderTests`. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
// Real text measurement, so a label's width is its glyphs'.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClassTableWeekHeaderTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var periodColWidth: Dp = 0.dp

    private fun show(thisWeek: ClassTableWeek?, preset: VisualPreset = VisualPreset.TigerDuck) {
        composeRule.setContent {
            CompositionLocalProvider(LocalVisualStylePolicy provides VisualStylePolicy(preset)) {
                periodColWidth = LocalVisualStylePolicy.current.classTablePeriodColumnWidth(1f)
                val courses = listOf(CourseChangeFixture.course())
                TimetableGrid(
                    courses = courses,
                    showRoomHints = false,
                    weekdays = listOf(1, 2, 3, 4, 5),
                    periods = ClassTableCellLayout.activePeriods(courses),
                    courseNosWithAssignments = emptySet(),
                    thisWeek = thisWeek,
                )
            }
        }
    }

    private fun week(termWeek: Int?) = ClassTableWeek(CourseChangeFixture.thursday, CourseChangeFixture.changes(), termWeek)

    @Test
    fun `the week shows centered over the period column, left of Monday, and is read as a week`() {
        show(week(8))
        val label = composeRule.onNodeWithText("W8")
        label.assertContentDescriptionEquals(composeRule.activity.getString(R.string.a11y_class_table_week).replace("%1\$lld", "8"))

        val bounds = label.getBoundsInRoot()
        // The grid's side padding, then the period column.
        val column = 4.dp..(4.dp + periodColWidth)
        assertEquals(((column.start + column.endInclusive) / 2).value, ((bounds.left + bounds.right) / 2).value, 0.5f)
        val monday = composeRule.onNodeWithText(composeRule.activity.getString(R.string.weekday_mon_short)).getBoundsInRoot()
        assertTrue("left of Monday: $bounds, $monday", bounds.right <= monday.left)
    }

    @Test
    fun `a two-digit week shrinks to fit the narrow period column of the tiles`() {
        show(week(18), VisualPreset.Tat)
        val label = composeRule.onNodeWithText("W18")
        val bounds = label.getBoundsInRoot()
        assertTrue("within the column: $bounds, $periodColWidth", bounds.left >= 4.dp && bounds.right <= 4.dp + periodColWidth)
        val layouts = mutableListOf<TextLayoutResult>()
        label.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        assertFalse("not cut off", layouts.single().hasVisualOverflow)
    }

    /** Every text on screen that reads as a week, such as W8. */
    private fun weekLabels(): List<String> =
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).fetchSemanticsNodes()
            .flatMap { it.config[SemanticsProperties.Text].map { text -> text.text } }
            .filter { Regex("W[0-9]+").matches(it) }

    @Test
    fun `no week shows outside the term`() {
        show(week(null))
        assertEquals(emptyList<String>(), weekLabels())
    }

    @Test
    fun `a plain timetable has no week`() {
        show(null)
        assertEquals(emptyList<String>(), weekLabels())
    }
}
