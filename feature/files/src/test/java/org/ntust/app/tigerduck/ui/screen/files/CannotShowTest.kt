package org.ntust.app.tigerduck.ui.screen.files

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertTrue

/** "Can't be shown here": the device line for an encoding it can't decode, above the raw error. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
// Real text measuring, so a wrapped line is a line.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CannotShowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val unsupportedLine = "This device can't play this video's format."

    private fun show(failure: ViewerFailure) {
        composeRule.setContent { CannotShow(reason = null, shown = ViewerErrors.shown(failure, privateParts = emptyList()), onOpenElsewhere = {}) }
        composeRule.waitForIdle()
    }

    private fun count(text: String, substring: Boolean = false) =
        composeRule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size

    @Test
    fun `a decoder that can't take the video says the device doesn't support its encoding, above the error`() {
        show(ViewerFailure(PlaybackException("decoder", null, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)))

        assertEquals(1, count(unsupportedLine))
        assertEquals(1, count("ERROR_CODE_DECODING_FORMAT_UNSUPPORTED", substring = true))
        val line = composeRule.onAllNodesWithText(unsupportedLine).fetchSemanticsNodes().single().boundsInRoot
        val raw = composeRule.onAllNodesWithText("ERROR_CODE_DECODING_FORMAT_UNSUPPORTED", substring = true)
            .fetchSemanticsNodes().single().boundsInRoot
        assert(line.bottom <= raw.top) { "the line sits under the error" }
    }

    @Test
    fun `a 4K60 clip beyond the decoder's rate gets the line too`() {
        show(ViewerFailure(PlaybackException("exceeds", null, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES)))

        assertEquals(1, count(unsupportedLine))
    }

    @Test
    fun `a long error shows a screenful at most, wrapped lines counted`() {
        val long = List(ViewerErrors.MAX_LINES) { "caused by RuntimeException: " + "x".repeat(380) }.joinToString("\n")
        composeRule.setContent { CannotShow(reason = null, shown = ShownFailure(long, unsupportedEncoding = false), onOpenElsewhere = {}) }
        composeRule.waitForIdle()

        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onAllNodesWithText("caused by RuntimeException", substring = true).fetchSemanticsNodes().single()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val layout = layouts.single()
        assertTrue("${layout.lineCount} lines on screen", layout.lineCount <= ViewerErrors.MAX_LINES)
        assertTrue("the cut doesn't show", layout.hasVisualOverflow)
    }

    @Test
    fun `a broken file keeps the plain message and its error`() {
        show(ViewerFailure(PlaybackException("Source error", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)))

        assertEquals(0, count(unsupportedLine))
        assertEquals(1, count("ERROR_CODE_IO_UNSPECIFIED", substring = true))
    }
}
