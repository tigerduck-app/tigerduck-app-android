package org.ntust.app.tigerduck.ui.screen.classtable.sharing

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.sharing.ImportedClassTables
import org.ntust.app.tigerduck.data.sharing.ImportedPerson
import org.ntust.app.tigerduck.data.sharing.ImportedPerson.Placement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** The Class tables list and the switcher's Other submenu, as iOS `ClassTableListTests` and `ClassTableSwitcherTests`. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h2000dp")
class ClassTableListScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int) = composeRule.activity.getString(id)

    private fun person(name: String, placement: Placement = Placement.ROTATION) =
        ImportedPerson(name.lowercase(), null, name, Instant.EPOCH, null, emptyList(), placement)

    private val ming = person("Ming")
    private val wen = person("Wen")
    private val hua = person("Hua", Placement.OTHER)

    private val done = mutableListOf<String>()

    private fun showList(people: List<ImportedPerson> = listOf(ming, wen, hua)) {
        val actions = ClassTableListActions(
            onRename = { done += "rename ${it.nickname}" },
            onDelete = { done += "delete ${it.nickname}" },
            onMove = { id, placement, before -> done += "move $id to ${placement.stored} before $before" },
        )
        composeRule.setContent { MaterialTheme { ClassTableList(people, "B11300000", actions) } }
    }

    private fun top(text: String) = composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun `the list runs the rotation with the user first, the long-press menu, then Other`() {
        showList()
        val order = listOf(
            string(R.string.class_table_list_section_double_tap), "B11300000", "Ming", "Wen",
            string(R.string.class_table_list_section_long_press),
            string(R.string.class_table_list_section_other), "Hua",
        )
        val tops = order.map(::top)
        assertEquals(tops.sorted(), tops)
        // The user's own row has no handle.
        composeRule.onAllNodesWithTag(classTableListHandleTag("ming"), useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodesWithTag(classTableListHandleTag("user"), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `a tap renames, a swipe to the start deletes, and the user's own row does neither`() {
        showList()
        composeRule.onNodeWithText("Ming").performClick()
        composeRule.onNodeWithText("B11300000").performClick()
        composeRule.onNodeWithText("Wen").performTouchInput {
            down(centerRight)
            repeat(10) { moveBy(Offset(-width / 10f, 0f)) }
            up()
        }
        composeRule.waitForIdle()

        assertEquals(listOf("rename Ming", "delete Wen"), done)
    }

    @Test
    fun `a handle drags a person into the empty section below, and the list saves it there`() {
        showList()
        val rowHeight = composeRule.onNodeWithText("Wen").fetchSemanticsNode().boundsInRoot.height
        composeRule.onNodeWithTag(classTableListHandleTag("wen"), useUnmergedTree = true).performTouchInput {
            down(center)
            // A row's height: past the long-press menu's header, short of Other's.
            repeat(2) { moveBy(Offset(0f, rowHeight / 2)) }
            up()
        }
        composeRule.waitForIdle()

        assertEquals(listOf("move wen to longPressMenu before null"), done)
    }

    @Test
    fun `a handle can't take a person above the user`() {
        showList()
        val rowHeight = composeRule.onNodeWithText("Ming").fetchSemanticsNode().boundsInRoot.height
        composeRule.onNodeWithTag(classTableListHandleTag("ming"), useUnmergedTree = true).performTouchInput {
            down(center)
            repeat(6) { moveBy(Offset(0f, -rowHeight / 2)) }
            up()
        }
        composeRule.waitForIdle()

        assertEquals(emptyList<String>(), done)
        assert(top("B11300000") < top("Ming"))
    }

    @Test
    fun `TalkBack moves a person to the end of another section, or deletes them`() {
        showList()
        val actions: List<CustomAccessibilityAction> = composeRule.onNodeWithText("Wen").fetchSemanticsNode()
            .let { node -> generateSequence(node) { it.parent }.firstNotNullOf { it.config.getOrNull(SemanticsActions.CustomActions) } }
        assertEquals(
            listOf(
                string(R.string.class_table_list_section_long_press),
                string(R.string.class_table_list_section_other),
                string(R.string.class_table_delete_user),
            ),
            actions.map { it.label },
        )
        actions[1].action()
        actions[2].action()

        assertEquals(listOf("move wen to other before null", "delete Wen"), done)
    }

    @Test
    fun `the switcher's Other opens a menu of its own, whose people are picked from there`() {
        val picked = mutableListOf<ClassTableSwitcherEntry.Choice>()
        val tables = ImportedClassTables(listOf(ming, hua), shownPersonId = "hua")
        composeRule.setContent {
            MaterialTheme { Column { ClassTableSwitcherMenuItems(ClassTableSwitcherEntry.sections(tables, "B11300000")) { picked += it } } }
        }
        composeRule.onNodeWithText("Hua").assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.class_table_list_section_other)).performClick()
        composeRule.onNodeWithText("Hua").performClick()

        assertEquals(listOf(ClassTableSwitcherEntry.Person("hua", "Hua", isShown = true)), picked)
    }
}
