package org.ntust.app.tigerduck.ui.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LeaveGuardTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val guard = LeaveGuard()
    private var moves = 0
    private val move = { moves++; Unit }

    @Test
    fun `with no page asking, a tab or a link moves at once`() {
        guard.leave(move)

        assertEquals(1, moves)
    }

    @Test
    fun `a page asking holds the move until it lets it go`() {
        var held: (() -> Unit)? = null
        composeRule.setContent { AskBeforeLeaving(guard) { held = it } }
        composeRule.waitForIdle()

        composeRule.runOnIdle { guard.leave(move) }
        assertEquals("waits on Save draft or Discard", 0, moves)

        assertNotNull(held)
        held!!()
        assertEquals(1, moves)
    }

    @Test
    fun `a page that keeps its user, as Keep editing does, drops the move`() {
        composeRule.setContent { AskBeforeLeaving(guard) { } }
        composeRule.waitForIdle()

        composeRule.runOnIdle { guard.leave(move) }

        assertEquals(0, moves)
    }

    @Test
    fun `a page gone from the screen asks nothing more`() {
        val shown = mutableStateOf(true)
        composeRule.setContent { if (shown.value) AskBeforeLeaving(guard) { } }
        composeRule.waitForIdle()

        shown.value = false
        composeRule.waitForIdle()
        composeRule.runOnIdle { guard.leave(move) }

        assertEquals(1, moves)
    }
}

class HeldMoveTest {

    private val held = HeldMove()
    private var moves = 0
    private val move = { moves++; Unit }
    private var closes = 0
    private val close = { closes++; Unit }

    private fun ask(left: Boolean = false, cannotLeave: Boolean = false, nothingToLose: Boolean = false) =
        held.ask(move, left, cannotLeave, nothingToLose)

    @Test
    fun `with nothing to lose, the move is made at once`() {
        assertEquals(false, ask(nothingToLose = true))
        assertEquals(1, moves)
    }

    @Test
    fun `while a send is out, the move is not made, held or asked about`() {
        assertEquals(false, ask(cannotLeave = true))
        held.release(close)
        assertEquals(0, moves)
        assertEquals(1, closes)
    }

    @Test
    fun `with text to lose, the move waits for Save draft or Discard, and is made once`() {
        assertEquals(true, ask())
        assertEquals(0, moves)

        held.release(close)
        assertEquals(1, moves)
        assertEquals(0, closes)

        held.release(close)
        assertEquals("not twice", 1, moves)
    }

    @Test
    fun `Keep editing, a failed save, a send or back drops what was held`() {
        ask()
        held.drop()

        held.release(close)

        assertEquals(0, moves)
        assertEquals(1, closes)
    }

    @Test
    fun `a page that has let go already, sliding away, holds nothing more`() {
        assertEquals(false, ask(left = true))
        assertEquals(1, moves)
    }
}
