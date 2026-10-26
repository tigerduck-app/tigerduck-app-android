package org.ntust.app.tigerduck.ui.component

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.AppProcessing
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** The app-wide spinner: up while work holds it, after a short grace, and down the moment it stops. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppProcessingHostTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val processing = AppProcessing()
    private val questionShowing = MutableStateFlow(false)
    private val line = RuntimeEnvironment.getApplication().getString(R.string.app_processing_keep_open)

    @Before
    fun setUp() {
        rule.mainClock.autoAdvance = false
        rule.setContent { AppProcessingHost(processing, questionShowing = questionShowing.collectAsState().value) }
    }

    private fun advance(ms: Long) {
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun shown(): Boolean =
        rule.onAllNodes(hasText(line), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `nothing shows while no work holds the spinner`() {
        advance(2_000L)

        assertFalse(shown())
    }

    @Test
    fun `a hold shows the spinner and its line after a short grace, and stopping it takes it down`() {
        val hold = processing.newHold()
        hold.start()
        advance(PROCESSING_GRACE_MS - 50L)
        assertFalse("not within the grace", shown())

        advance(100L)
        assertTrue(shown())

        hold.stop()
        advance(FRAME_MS)
        assertFalse(shown())
    }

    @Test
    fun `work that ends within the grace never flashes the spinner`() {
        val hold = processing.newHold()
        hold.start()
        // Frame by frame, so a spinner up for a single frame would be seen.
        repeat(((PROCESSING_GRACE_MS - 50L) / 16L).toInt()) {
            advance(16L)
            assertFalse(shown())
        }
        hold.stop()
        repeat(120) {
            advance(16L)
            assertFalse(shown())
        }
    }

    @Test
    fun `the spinner stays while any hold is left`() {
        val first = processing.newHold()
        val second = processing.newHold()
        first.start()
        second.start()
        advance(PROCESSING_GRACE_MS + 50L)

        first.stop()
        advance(FRAME_MS)
        assertTrue(shown())

        second.stop()
        advance(FRAME_MS)
        assertFalse(shown())
    }

    @Test
    fun `TalkBack reads the line as it comes up`() {
        processing.newHold().start()
        advance(PROCESSING_GRACE_MS + 50L)

        val node = rule.onAllNodes(hasText(line)).fetchSemanticsNodes().single()
        assertEquals(LiveRegionMode.Polite, node.config.getOrNull(SemanticsProperties.LiveRegion))
        // One stop for TalkBack: the progress is read with the line, not as a node of its own.
        val progress = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .fetchSemanticsNodes()
        assertEquals(listOf(node.id), progress.map { it.id })
    }

    @Test
    fun `Back does not take the spinner down while work holds it`() {
        processing.newHold().start()
        advance(PROCESSING_GRACE_MS + 50L)

        // Back goes to the spinner's own window, which is the one in front.
        val dialog = ShadowDialog.getLatestDialog() as androidx.activity.ComponentDialog
        rule.runOnIdle { dialog.onBackPressedDispatcher.onBackPressed() }
        advance(FRAME_MS)

        assertTrue(shown())
        assertFalse(rule.activity.isFinishing)
    }

    private companion object {
        /** A couple of frames: the hold's change reaches the host, which draws without the spinner. */
        const val FRAME_MS = 48L
    }

    @Test
    fun `a question the work waits on keeps the spinner down until it is answered`() {
        val hold = processing.newHold()
        hold.start()
        // A pin-mismatch prompt a sign-in's request waits on: the spinner would cover its buttons.
        questionShowing.value = true
        advance(PROCESSING_GRACE_MS + 100L)
        assertFalse(shown())

        questionShowing.value = false
        advance(100L)
        assertTrue(shown())
        hold.stop()
    }
}
