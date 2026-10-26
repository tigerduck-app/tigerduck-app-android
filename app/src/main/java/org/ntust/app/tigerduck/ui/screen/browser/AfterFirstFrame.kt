package org.ntust.app.tigerduck.ui.screen.browser

import android.view.View
import androidx.core.view.doOnPreDraw

/**
 * Runs [block] once, after [view]'s window has drawn its first frame. Work that blocks the main
 * thread then shows over that frame instead of holding it back.
 */
internal fun afterFirstFrame(view: View, block: () -> Unit) {
    // Posted from the pre-draw, it runs once that frame is drawn.
    view.doOnPreDraw { it.post(block) }
}
