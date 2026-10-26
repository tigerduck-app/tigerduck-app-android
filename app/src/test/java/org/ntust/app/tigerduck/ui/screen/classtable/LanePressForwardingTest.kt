package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A press on one run of a clashing course shows on the course's whole shape
 * (MultiConflictCourseCell), so it has to start where it was made: a run reports it from its own
 * corner, the shape draws it from its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LanePressForwardingTest {

    @Test
    fun `a run's press reaches the shape where it was made, and its release ends that press`() = runTest(UnconfinedTestDispatcher()) {
        val run = MutableInteractionSource()
        val shape = MutableInteractionSource()
        val seen = mutableListOf<Interaction>()
        backgroundScope.launch { shape.interactions.collect { seen += it } }
        backgroundScope.launch { forwardInteractions(run, shape, Offset(0f, 51.5f)) }

        val press = PressInteraction.Press(Offset(10f, 5f))
        run.emit(press)
        run.emit(PressInteraction.Release(press))
        val cancelled = PressInteraction.Press(Offset(1f, 1f))
        run.emit(cancelled)
        run.emit(PressInteraction.Cancel(cancelled))

        val moved = seen[0] as PressInteraction.Press
        assertEquals(Offset(10f, 56.5f), moved.pressPosition)
        assertEquals(moved, (seen[1] as PressInteraction.Release).press)
        assertEquals(seen[2], (seen[3] as PressInteraction.Cancel).press)
        assertEquals(4, seen.size)
    }

    /** The run moved while pressed, which restarts its forwarding: the shape's press ends then. */
    @Test
    fun `a press still held when the forwarding ends is cancelled on the shape`() = runTest(UnconfinedTestDispatcher()) {
        val run = MutableInteractionSource()
        val shape = MutableInteractionSource()
        val seen = mutableListOf<Interaction>()
        backgroundScope.launch { shape.interactions.collect { seen += it } }
        val forwarding = backgroundScope.launch { forwardInteractions(run, shape, Offset(0f, 10f)) }

        run.emit(PressInteraction.Press(Offset(1f, 1f)))
        forwarding.cancel()

        assertEquals(2, seen.size)
        assertEquals(seen[0], (seen[1] as PressInteraction.Cancel).press)
    }
}
