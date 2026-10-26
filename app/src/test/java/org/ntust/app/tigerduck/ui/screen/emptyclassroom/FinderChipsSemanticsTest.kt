package org.ntust.app.tigerduck.ui.screen.emptyclassroom

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.emptyclassroom.BuildingDirectory
import org.ntust.app.tigerduck.emptyclassroom.RoomSlotUse
import org.ntust.app.tigerduck.emptyclassroom.RoomUsage
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The finder's chips as TalkBack reads them: the chosen one says it is selected, which a state
 * description would replace, and its details travel in its description.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FinderChipsSemanticsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the chosen period chip reads as selected, with its times and that it is now`() {
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = false) { PeriodChipRow(selected = 2, current = 2, ended = 2, onSelect = {}) }
        }

        val chip = composeRule.onNodeWithContentDescription("Period 3, 10:20–11:10, Now").fetchSemanticsNode()

        assertEquals(true, chip.config.getOrNull(SemanticsProperties.Selected))
        assertNull(chip.config.getOrNull(SemanticsProperties.StateDescription))
        val other = composeRule.onNodeWithContentDescription("Period 4, 11:20–12:10").fetchSemanticsNode()
        assertEquals(false, other.config.getOrNull(SemanticsProperties.Selected))
    }

    @Test
    @Config(qualifiers = "+zh-rTW")
    fun `in Chinese a chip's parts are joined with the full-width comma`() {
        val free = List(14) { RoomSlotUse.Free }
        val directory = BuildingDirectory.of(listOf(RoomUsage("TR-209", free)), period = 2)
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = false) {
                PeriodChipRow(selected = 2, current = 2, ended = 2, onSelect = {})
                BuildingSection(
                    directory = directory,
                    shown = "TR",
                    siteNames = mapOf("TR" to "研揚大樓"),
                    period = 2,
                    ended = 0,
                    onPick = {},
                    onNextPeriod = null,
                    onRoom = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("第 3 節，10:20–11:10，現在").assertExists()
        composeRule.onNodeWithContentDescription("研揚大樓，1 間空教室").assertExists()
    }

    @Test
    fun `the separator follows the UI language`() {
        assertEquals("，", labelSeparator("zh-TW"))
        assertEquals("，", labelSeparator("zh-Hans"))
        assertEquals("，", labelSeparator("yue-HK"))
        assertEquals(", ", labelSeparator("en-US"))
        assertEquals(", ", labelSeparator("ja"))
    }

    @Test
    fun `the chosen building chip reads as selected, with its name and free-room count`() {
        val free = List(14) { RoomSlotUse.Free }
        val directory = BuildingDirectory.of(listOf(RoomUsage("TR-209", free), RoomUsage("IB-508", free)), period = 2)
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = false) {
                BuildingSection(
                    directory = directory,
                    shown = "TR",
                    siteNames = mapOf("TR" to "研揚大樓", "IB" to "國際大樓"),
                    period = 2,
                    ended = 0,
                    onPick = {},
                    onNextPeriod = null,
                    onRoom = {},
                )
            }
        }

        val chip = composeRule.onNodeWithContentDescription("AAEON Building, Free classrooms: 1").fetchSemanticsNode()

        assertEquals(true, chip.config.getOrNull(SemanticsProperties.Selected))
        assertNull(chip.config.getOrNull(SemanticsProperties.StateDescription))
    }
}
