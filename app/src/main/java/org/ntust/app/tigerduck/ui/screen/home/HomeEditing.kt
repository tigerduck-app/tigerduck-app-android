// Whether Home is being arranged, and what leaving it in the middle does.

package org.ntust.app.tigerduck.ui.screen.home

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.ntust.app.tigerduck.ui.component.PageLeftEffect

/**
 * Whether Home is being arranged. HomeViewModel holds it rather than the page, because leaving
 * Home in the middle has to end editing and also reach the page the next time it shows: the
 * page's own saved state is written before it hears that it left. A rotation keeps editing on.
 */
@Stable
class HomeEditing {
    var isEditing by mutableStateOf(false)
        private set

    private var placeForgotten = false

    fun start() {
        isEditing = true
    }

    /** Done, or the system back: editing ends and the page stays where it was scrolled to. */
    fun finish() {
        isEditing = false
    }

    /**
     * Home left for another page while being arranged ends editing as Done does, and forgets the
     * place it was scrolled to, so Home comes back at its top, as iOS's does
     * (tigerduck-app/tigerduck-app#852). Leaving it while browsing keeps the place.
     */
    fun onPageLeft() {
        if (!isEditing) return
        isEditing = false
        placeForgotten = true
    }

    /** Whether the page should open at its top; true once after [onPageLeft] ended editing. */
    fun takeForgottenPlace(): Boolean = placeForgotten.also { placeForgotten = false }
}

/**
 * Hands Home's leaving to [editing], and opens [listState] at its top when editing ended that way.
 * The request is made when the page is first applied, before its first layout, so the place its
 * saved state brought back never draws.
 */
@Composable
internal fun HomeEditingLeaveEffect(editing: HomeEditing, listState: LazyListState) {
    PageLeftEffect(editing::onPageLeft)
    DisposableEffect(editing, listState) {
        if (editing.takeForgottenPlace()) listState.requestScrollToItem(0)
        onDispose {}
    }
}
