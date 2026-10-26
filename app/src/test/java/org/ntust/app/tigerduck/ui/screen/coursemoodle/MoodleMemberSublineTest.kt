package org.ntust.app.tigerduck.ui.screen.coursemoodle

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The members list's line under a name (#697): with too little room, the department's short name
 * is cut short and the student ID never is, as on the iPhone.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
// Real text measuring, so a line too long for its width is ellipsized.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MoodleMemberSublineTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    @Test
    fun `a department too long for the line is cut short, and the ID beside it is not`() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.width(120.dp)) {
                    MemberSubline("B00015001", "Computer Science and Information Engineering")
                }
            }
        }
        composeRule.waitForIdle()

        val id = composeRule.onNodeWithText("B00015001").layout()
        assertFalse("the student ID is shown whole", id.isLineEllipsized(0))
        val department = composeRule.onNodeWithText(" · Computer Science and Information Engineering").layout()
        assertTrue("the department gives way", department.isLineEllipsized(0))
    }
}
