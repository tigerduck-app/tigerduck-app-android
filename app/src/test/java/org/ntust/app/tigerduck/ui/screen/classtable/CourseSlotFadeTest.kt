@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck.ui.screen.classtable

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A slot fades the courses it showed out and the new ones in, and keeps a same-key change in place. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CourseSlotFadeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private data class Slot(val key: String, val label: String)

    private var slot by mutableStateOf(Slot("calculus", "Calculus"))

    private fun show() {
        composeRule.setContent {
            CourseSlotFade(target = slot, key = { it.key }) { Text(it.label) }
        }
        composeRule.waitForIdle()
    }

    private fun change(to: Slot) {
        slot = to
        Snapshot.sendApplyNotifications()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()
    }

    @Test
    fun `another course fades in while the one before, as it was, fades out`() {
        show()
        composeRule.mainClock.autoAdvance = false

        change(Slot("physics", "Physics"))

        // The leaving one draws the course it showed, not the new one a second time.
        composeRule.onNodeWithText("Calculus").assertExists()
        composeRule.onNodeWithText("Physics").assertExists()

        composeRule.mainClock.advanceTimeBy((COURSE_FADE_OUT_MS + COURSE_FADE_IN_MS + 50).toLong())
        composeRule.onNodeWithText("Calculus").assertDoesNotExist()
        composeRule.onNodeWithText("Physics").assertExists()
    }

    @Test
    fun `the same course changes in place, with nothing fading`() {
        show()
        composeRule.mainClock.autoAdvance = false

        change(Slot("calculus", "Calculus II"))

        composeRule.onNodeWithText("Calculus II").assertExists()
        composeRule.onNodeWithText("Calculus").assertDoesNotExist()
    }
}
