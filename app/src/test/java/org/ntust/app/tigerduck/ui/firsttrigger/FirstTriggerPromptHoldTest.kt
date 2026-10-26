package org.ntust.app.tigerduck.ui.firsttrigger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.preferences.FirstTriggerSeenStore
import org.ntust.app.tigerduck.library.LibraryPagePresence

/**
 * When a pending first-trigger prompt may show: not over What's New or the
 * update prompt, and the flip-to-library one not over the Library page, where
 * it would cover the code; leaving the page shows it
 * (tigerduck-app/tigerduck-app#529).
 */
class FirstTriggerPromptHoldTest {

    private class FakeSeenStore : FirstTriggerSeenStore {
        private val seen = mutableSetOf<String>()
        override fun hasSeenFirstTriggerPrompt(storageKey: String) = storageKey in seen
        override fun setFirstTriggerPromptSeen(storageKey: String, seen: Boolean) {
            if (seen) this.seen.add(storageKey) else this.seen.remove(storageKey)
        }
    }

    private val controller = FirstTriggerPromptController(FakeSeenStore())
    private val libraryPage = LibraryPagePresence()

    private fun mayShow(whatsNewShowing: Boolean = false, updatePromptShowing: Boolean = false) = firstTriggerPromptMayShow(
        whatsNewShowing = whatsNewShowing,
        updatePromptShowing = updatePromptShowing,
        pending = controller.pending.value?.key,
        libraryPageShown = libraryPage.shown.value,
    )

    @Test
    fun `the prompt waits for what's new and the update prompt`() {
        controller.requestIfFirstTime(FirstTriggerPromptKey.FLIP_TO_LIBRARY, onAccept = {}, onDecline = {})

        assertTrue(mayShow())
        assertFalse(mayShow(whatsNewShowing = true))
        assertFalse(mayShow(updatePromptShowing = true))
    }

    /** As when the library shortcut opens the app: the offer is made on the first tab, then Library opens. */
    @Test
    fun `an offer made on another page waits while the Library page shows, and shows once it is left`() {
        controller.requestIfFirstTime(FirstTriggerPromptKey.FLIP_TO_LIBRARY, onAccept = {}, onDecline = {})
        assertTrue(mayShow())

        val page = Any()
        libraryPage.appear(page)
        assertFalse(mayShow())
        // Held, not answered: it is still pending.
        assertEquals(FirstTriggerPromptKey.FLIP_TO_LIBRARY, controller.pending.value?.key)

        libraryPage.disappear(page)
        assertTrue(mayShow())
    }

    /** A tab switch can show one Library page before it hides another. */
    @Test
    fun `the Library page counts as shown while any one of them is`() {
        val first = Any()
        val second = Any()
        libraryPage.appear(first)
        libraryPage.appear(second)
        libraryPage.appear(first)

        libraryPage.disappear(first)
        assertTrue(libraryPage.shown.value)

        libraryPage.disappear(second)
        assertFalse(libraryPage.shown.value)
    }
}
