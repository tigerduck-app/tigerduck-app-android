package org.ntust.app.tigerduck.library

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Library page counts as shown while its back stack entry is resumed: the
 * page on screen, not one composed or left in the back stack.
 */
class LibraryPagePresenceTest {

    private val presence = LibraryPagePresence()
    private val page = Any()
    private var recreating = false

    /** A back stack entry of the Library page, followed as the screen follows it. */
    private fun entry(): LifecycleRegistry {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry.addObserver(presence.observer(page) { recreating })
        return owner.registry
    }

    @Test
    fun `the page counts while its entry is resumed, and not once another page opens over it`() {
        val entry = entry()
        entry.currentState = Lifecycle.State.RESUMED
        assertTrue(presence.shown.value)

        // Navigating away: the entry stays in the back stack, no longer on top.
        entry.currentState = Lifecycle.State.STARTED
        assertFalse(presence.shown.value)
    }

    /** As when the first tab, kept under the next page, is Library and the theme changes meanwhile. */
    @Test
    fun `a page left in the back stack does not count again through a recreation`() {
        val entry = entry()
        entry.currentState = Lifecycle.State.RESUMED
        entry.currentState = Lifecycle.State.CREATED

        recreating = true
        entry.currentState = Lifecycle.State.DESTROYED

        assertFalse(presence.shown.value)
    }

    /**
     * A change of the dark theme or the font size builds the activity again: the page stays
     * counted in between, so a held prompt does not show for the frame before it is back.
     */
    @Test
    fun `a page rebuilt by a recreation stays counted through it`() {
        val before = entry()
        before.currentState = Lifecycle.State.RESUMED

        recreating = true
        before.currentState = Lifecycle.State.DESTROYED
        assertTrue(presence.shown.value)

        recreating = false
        val after = entry()
        after.currentState = Lifecycle.State.RESUMED
        assertTrue(presence.shown.value)
        after.currentState = Lifecycle.State.STARTED
        assertFalse(presence.shown.value)
    }
}
