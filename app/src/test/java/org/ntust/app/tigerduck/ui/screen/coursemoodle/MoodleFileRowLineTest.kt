package org.ntust.app.tigerduck.ui.screen.coursemoodle

import android.app.Application
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.moodle.MoodleDownloadState
import org.ntust.app.tigerduck.network.model.moodle.MoodleFile
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A Moodle file row's second line ends with the file's type, and TalkBack hears the type before
 * the download's progress, as VoiceOver does (tigerduck-app#1005).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MoodleFileRowLineTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val slides = MoodleFile(
        name = "Week 3 slides.pdf",
        size = 646_000,
        url = "https://moodle2.ntust.edu.tw/webservice/pluginfile.php/31/mod_resource/content/1/slides.pdf",
        mimeType = "application/pdf",
        modified = null,
        path = "/",
    )

    private fun show(file: MoodleFile, state: MoodleDownloadState = MoodleDownloadState.NotDownloaded) {
        composeRule.setContent {
            MoodleFileRow(title = file.name, file = file, state = state, onTap = {}, onShare = {}, onOpenInMoodle = {})
        }
    }

    private val size get() = Formatter.formatShortFileSize(composeRule.activity, 646_000)

    @Test
    fun `a file row's line ends with its type`() {
        show(slides)

        composeRule.onNodeWithText("$size · PDF", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `TalkBack hears the type before the download's progress`() {
        show(slides, MoodleDownloadState.Downloading(0.5f))

        assertEquals(
            "$size · PDF, 50%",
            composeRule.onNodeWithContentDescription("Week 3 slides.pdf").fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.StateDescription),
        )
    }

    @Test
    fun `a file with no type to show ends its line with its size`() {
        show(slides.copy(name = "Makefile", mimeType = null))

        composeRule.onNodeWithText(size, useUnmergedTree = true).assertExists()
    }
}
