@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck.ui.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Class Table tab's long press and double tap ride along with its ordinary taps. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ClassTableTabGesturesTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var taps = 0
    private var longPresses = 0
    private var doubleTaps = 0
    private var showingClassTable = true

    private fun showTab() {
        composeRule.setContent {
            NavigationBar {
                NavigationBarItem(
                    selected = true,
                    onClick = { taps++ },
                    icon = { Text("Class table") },
                    modifier = Modifier
                        .classTableTabGestures(
                            isShowingClassTable = { showingClassTable },
                            onLongPress = { longPresses++ },
                            onDoubleTap = { doubleTaps++ },
                            longPressLabel = "Switch class table",
                        )
                        .testTag("tab"),
                )
            }
        }
    }

    @Test
    fun `a long press opens the switcher, and letting go does not tap the tab`() {
        showTab()

        composeRule.onNodeWithTag("tab").performTouchInput { longClick() }
        composeRule.waitForIdle()

        assertEquals(1, longPresses)
        assertEquals(0, taps)
    }

    @Test
    fun `a tap is still the tab's tap`() {
        showTab()

        composeRule.onNodeWithTag("tab").performTouchInput { click() }
        composeRule.waitForIdle()

        assertEquals(1, taps)
        assertEquals(0, longPresses)
        assertEquals(0, doubleTaps)
    }

    @Test
    fun `a double tap on the shown class table switches, and both taps still reach the tab`() {
        showTab()

        composeRule.onNodeWithTag("tab").performTouchInput { doubleClick() }
        composeRule.waitForIdle()

        assertEquals(1, doubleTaps)
        assertEquals(2, taps)
    }

    @Test
    fun `a double tap begun on another page does not switch`() {
        showingClassTable = false
        showTab()

        composeRule.onNodeWithTag("tab").performTouchInput { doubleClick() }
        composeRule.waitForIdle()

        assertEquals(0, doubleTaps)
    }

    @Test
    fun `TalkBack offers the switcher as the tab's long-press action`() {
        showTab()

        composeRule.onNodeWithTag("tab").performSemanticsAction(SemanticsActions.OnLongClick)

        assertEquals(1, longPresses)
        assertEquals(0, taps)
    }

    @Test
    fun `after a double tap a single tap only taps, and the next double tap switches again, as on iOS`() {
        // iOS steps on with each further double tap within three seconds (#215), not with one tap.
        showTab()
        val tab = composeRule.onNodeWithTag("tab")

        tab.performTouchInput { doubleClick() }
        composeRule.mainClock.advanceTimeBy(400)
        tab.performTouchInput { click() }
        composeRule.mainClock.advanceTimeBy(400)
        assertEquals(1, doubleTaps)

        tab.performTouchInput { doubleClick() }
        composeRule.waitForIdle()
        assertEquals(2, doubleTaps)
        assertEquals(5, taps)
    }
}

