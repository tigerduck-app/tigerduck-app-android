package org.ntust.app.tigerduck.ui.screen.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Other settings > Course name size: the slider ticks once for every 0.05x step it moves onto,
 * as the iPhone's does and as the Live Update lead-time slider already does here.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class CourseNameSizeSliderTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var steps = 0
    private val committed = mutableListOf<Float>()

    private fun show(scale: Float = 1.0f) = composeRule.setContent {
        TigerDuckAppTheme {
            CourseNameSizeContent(
                persistedScale = scale,
                onCommit = { committed += it },
                onStep = { steps++ },
                onBack = {},
            )
        }
    }

    private fun slideTo(value: Float) {
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
        composeRule.waitForIdle()
    }

    @Test
    fun `each step the slider moves onto ticks once`() {
        show()
        slideTo(1.05f)
        assertEquals(1, steps)
        slideTo(1.3f)
        assertEquals(2, steps)
        assertEquals(1.3f, committed.last(), 0.001f)
    }

    @Test
    fun `staying on a step does not tick again`() {
        show()
        slideTo(1.1f)
        slideTo(1.1f)
        assertEquals(1, steps)
    }

    @Test
    fun `opening the page does not tick`() {
        show(scale = 1.2f)
        composeRule.waitForIdle()
        assertEquals(0, steps)
    }
}
