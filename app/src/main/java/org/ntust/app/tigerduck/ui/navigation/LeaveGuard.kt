package org.ntust.app.tigerduck.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * Asks the page on screen before the app moves away from it by anything but its own back: a
 * bottom-bar tap or a link from a reminder, a widget or the Live Update, either of which would pop
 * it. The mail being written asks whether to save its draft; with no page asking, the move is made
 * at once.
 */
@Stable
class LeaveGuard {
    private var asker: ((proceed: () -> Unit) -> Unit)? = null

    /** Makes [move] once the page on screen allows it, which may be never: Keep editing. */
    fun leave(move: () -> Unit) {
        val ask = asker
        if (ask == null) move() else ask(move)
    }

    internal fun register(ask: (proceed: () -> Unit) -> Unit) {
        asker = ask
    }

    internal fun unregister(ask: (proceed: () -> Unit) -> Unit) {
        if (asker === ask) asker = null
    }
}

/**
 * While composed, a move away from this page that [guard] is asked about goes through [onLeave],
 * which runs the move it is handed once the page has let it go, or drops it.
 */
@Composable
fun AskBeforeLeaving(guard: LeaveGuard, onLeave: (proceed: () -> Unit) -> Unit) {
    val latest by rememberUpdatedState(onLeave)
    DisposableEffect(guard) {
        val ask: (() -> Unit) -> Unit = { latest(it) }
        guard.register(ask)
        onDispose { guard.unregister(ask) }
    }
}

/**
 * The move a tab or a link asked for while the page waits on its own question, such as the mail
 * being written on Save draft: made once that question ends with the page let go, and dropped
 * when it ends with the user staying, the page failing, or the page leaving some other way.
 */
class HeldMove {
    private var move: (() -> Unit)? = null

    /**
     * A tab or a link asks: made at once when the page has [nothingToLose] or has [left] already,
     * ignored while it [cannotLeave], and held otherwise. Says whether it was held, for the page
     * to ask its question.
     */
    fun ask(move: () -> Unit, left: Boolean, cannotLeave: Boolean, nothingToLose: Boolean): Boolean {
        when {
            left || (!cannotLeave && nothingToLose) -> move()
            cannotLeave -> Unit
            else -> {
                this.move = move
                return true
            }
        }
        return false
    }

    /** The user stays, or the page went another way: what was held is no longer wanted. */
    fun drop() {
        move = null
    }

    /** The page let go: the move held, once, or [otherwise] when none was. */
    fun release(otherwise: () -> Unit) {
        val held = move
        move = null
        (held ?: otherwise)()
    }
}
