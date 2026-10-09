package org.ntust.app.tigerduck.ui.screen.whatsnew

import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class KeepOpenSheetDownTest {

    private suspend fun leftover(available: Velocity) =
        KeepOpenSheetDown.onPostFling(consumed = Velocity.Zero, available = available)

    @Test
    fun `swallows what is left of an upward fling`() = runTest {
        assertEquals(Velocity(0f, -4000f), leftover(Velocity(0f, -4000f)))
    }

    @Test
    fun `leaves a downward fling to the sheet so it can still close`() = runTest {
        assertEquals(Velocity.Zero, leftover(Velocity(0f, 4000f)))
    }

    @Test
    fun `takes only the vertical part`() = runTest {
        assertEquals(Velocity(0f, -4000f), leftover(Velocity(800f, -4000f)))
    }

    @Test
    fun `takes nothing when nothing is left`() = runTest {
        assertEquals(Velocity.Zero, leftover(Velocity.Zero))
    }
}
