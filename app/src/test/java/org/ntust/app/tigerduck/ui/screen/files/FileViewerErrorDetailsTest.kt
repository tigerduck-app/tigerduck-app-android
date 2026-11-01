package org.ntust.app.tigerduck.ui.screen.files

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.files.ViewerKind
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** "Can't be shown here", with the error under it, so a screenshot says why. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class FileViewerErrorDetailsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a file that can't be read shows its error, with no name or ID from its path`() {
        val missing = File(temp.root, "B00015999_notes.txt")
        composeRule.setContent {
            FileViewerScreen(
                file = ViewedFile(Uri.fromFile(missing), "text/plain", "notes.txt", ViewerKind.TEXT),
                browserPreference = "in_app",
                onBack = {},
                onShare = {},
                onOpenElsewhere = {},
            )
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("FileNotFoundException", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        val cannotShow = composeRule.activity.getString(R.string.file_viewer_cannot_show)
        assertTrue(composeRule.onAllNodesWithText(cannotShow).fetchSemanticsNodes().isNotEmpty())
        assertTrue(
            "the student ID from the path is on screen",
            composeRule.onAllNodes(hasText("B00015999", substring = true)).fetchSemanticsNodes().isEmpty(),
        )
        val openElsewhere = composeRule.activity.getString(R.string.file_viewer_open_elsewhere)
        assertTrue(composeRule.onAllNodes(hasText(openElsewhere)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `the bar is 56dp tall, with the file's name, the way back, Share and Open elsewhere`() {
        composeRule.setContent {
            FileViewerScreen(
                file = ViewedFile(Uri.fromFile(File(temp.root, "notes.txt")), "text/plain", "notes.txt", ViewerKind.TEXT),
                browserPreference = "in_app",
                onBack = {},
                onShare = {},
                onOpenElsewhere = {},
            )
        }
        val activity = composeRule.activity
        composeRule.onNodeWithText("notes.txt").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(activity.getString(R.string.school_mail_attachment_share)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(activity.getString(R.string.file_viewer_open_elsewhere)).assertIsDisplayed()
        val back = composeRule.onNodeWithContentDescription(activity.getString(R.string.action_back))
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(28f * activity.resources.displayMetrics.density, back.center.y, 1f)
    }
}
