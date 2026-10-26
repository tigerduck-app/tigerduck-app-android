package org.ntust.app.tigerduck.ui.screen.coursemoodle

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.core.strings.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The picture at the top of a member's page (#698, as the iPhone's tigerduck-app#741): the
 * member's own picture is a button that opens it full screen, labelled for TalkBack, with a
 * spinner while it gets ready, which takes no second tap. Moodle's default picture stays a
 * plain picture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MoodleMemberPictureTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val label get() = composeRule.activity.getString(R.string.course_member_profile_picture)

    @Test
    fun `a member's own picture is a button labelled for TalkBack that opens it`() {
        var opened = 0
        composeRule.setContent { MemberPicture("Amy Lin", picture = null, onOpen = { opened++ }, opening = false) }

        composeRule.onNode(hasContentDescription(label))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertIsEnabled()
            .performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `while it gets ready it spins and takes no second tap`() {
        var opening by mutableStateOf(true)
        var opened = 0
        composeRule.setContent { MemberPicture("Amy Lin", picture = null, onOpen = { opened++ }, opening = opening) }

        composeRule.onNode(hasContentDescription(label)).assertIsNotEnabled().performClick()
        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate), useUnmergedTree = true).assertExists()
        assertEquals(0, opened)

        opening = false
        composeRule.waitForIdle()
        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `Moodle's default picture is no button`() {
        composeRule.setContent { MemberPicture("Amy Lin", picture = null, onOpen = null, opening = false) }

        composeRule.onNode(hasContentDescription(label)).assertDoesNotExist()
    }
}
