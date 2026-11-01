package org.ntust.app.tigerduck.ui.screen.classtable

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class, qualifiers = "w400dp-h2000dp")
class ClassTableMenuTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int) = composeRule.activity.getString(id)

    private fun showMenu() {
        composeRule.setContent {
            MaterialTheme {
                Column {
                    OwnClassTableMenuItems(
                        onAddCourse = {},
                        shareEnabled = true,
                        onShare = {},
                        onImport = {},
                        exportEnabled = true,
                        onExport = {},
                        onReset = {},
                    )
                }
            }
        }
    }

    @Test
    fun `the menu runs as on the iPhone, from Add Course down to Reset`() {
        showMenu()
        val order = listOf(
            R.string.add_course_title,
            R.string.class_table_subscribe_clubs,
            R.string.class_table_share,
            R.string.class_table_import,
            R.string.class_table_export_screenshot,
            R.string.class_table_reset_title,
        )
        val tops = order.map { composeRule.onNodeWithText(string(it)).fetchSemanticsNode().boundsInRoot.top }

        assertEquals(tops.sorted(), tops)
    }

    @Test
    fun `Subscribe to Clubs is there but cannot be picked yet`() {
        showMenu()

        composeRule.onNodeWithText(string(R.string.class_table_subscribe_clubs)).assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.class_table_reset_title)).assertIsEnabled()
    }

    private fun showPersonMenu(exportEnabled: Boolean = true, picked: MutableList<String> = mutableListOf()) {
        composeRule.setContent {
            MaterialTheme {
                Column {
                    PersonClassTableMenuItems(
                        exportEnabled = exportEnabled,
                        onExport = { picked += "export" },
                        onImport = { picked += "import" },
                        onRename = { picked += "rename" },
                        onDelete = { picked += "delete" },
                    )
                }
            }
        }
    }

    @Test
    fun `someone else's menu exports and imports, then what applies to the person`() {
        val picked = mutableListOf<String>()
        showPersonMenu(picked = picked)
        val order = listOf(
            R.string.class_table_export_screenshot,
            R.string.class_table_import,
            R.string.class_table_rename_title,
            R.string.class_table_delete_user,
        )
        val items = order.map { composeRule.onNodeWithText(string(it)) }
        val tops = items.map { it.fetchSemanticsNode().boundsInRoot.top }
        assertEquals(tops.sorted(), tops)

        items.forEach { it.performClick() }
        assertEquals(listOf("export", "import", "rename", "delete"), picked)
    }

    @Test
    fun `someone else's class table can't be exported with no grid drawn`() {
        showPersonMenu(exportEnabled = false)
        composeRule.onNodeWithText(string(R.string.class_table_export_screenshot)).assertIsNotEnabled()
    }
}
