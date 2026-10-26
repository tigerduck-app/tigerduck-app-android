package org.ntust.app.tigerduck.library

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Library pages on screen. A pending flip-to-library offer waits while
 * one is up, since the prompt would cover the code, and shows once the page
 * is left. Each page counts once however often it
 * appears, and a tab switch can show one page before it hides another.
 * Mirrors the Apple app's `LibraryPagePresence`. A page counts while its back
 * stack entry is resumed; see [observer].
 */
@Singleton
class LibraryPagePresence @Inject constructor() {
    private val pages = mutableSetOf<Any>()
    private val _shown = MutableStateFlow(false)

    /** Whether a Library page is on screen. */
    val shown: StateFlow<Boolean> = _shown.asStateFlow()

    fun appear(page: Any) = synchronized(pages) {
        pages += page
        _shown.value = true
    }

    /**
     * [page] left the screen. One torn down only to be built again
     * ([recreating]), as when the dark theme or the font size changes, stays:
     * it shows again once rebuilt, and a held prompt would show for the frame
     * in between. (A rotation rebuilds nothing: the activity handles it.)
     */
    fun disappear(page: Any, recreating: Boolean = false) = synchronized(pages) {
        if (recreating) return@synchronized
        pages -= page
        _shown.value = pages.isNotEmpty()
    }

    /**
     * Counts [page] while the back stack entry it follows is resumed: the page
     * on screen, not merely composed. A Library page left in the back stack,
     * as the first tab is under the page opened from it, stops counting as
     * soon as another opens over it, before any transition or recreation
     * could keep it counted. [recreating] says whether a pause is one only to
     * build the activity again.
     */
    fun observer(page: Any, recreating: () -> Boolean): LifecycleEventObserver =
        LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> appear(page)
                Lifecycle.Event.ON_PAUSE -> disappear(page, recreating())
                else -> Unit
            }
        }
}
