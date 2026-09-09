package org.ntust.app.tigerduck.ui.firsttrigger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.preferences.FirstTriggerSeenStore

class FirstTriggerPromptControllerTest {

    /** In-memory stand-in for the SharedPreferences-backed store. */
    private class FakeSeenStore : FirstTriggerSeenStore {
        private val seen = mutableSetOf<String>()
        override fun hasSeenFirstTriggerPrompt(storageKey: String) = storageKey in seen
        override fun setFirstTriggerPromptSeen(storageKey: String, seen: Boolean) {
            if (seen) this.seen.add(storageKey) else this.seen.remove(storageKey)
        }
    }

    private val key = FirstTriggerPromptKey.FLIP_TO_LIBRARY

    @Test
    fun `requestIfFirstTime surfaces a prompt when unseen and nothing pending`() {
        val controller = FirstTriggerPromptController(FakeSeenStore())
        controller.requestIfFirstTime(key, onAccept = {}, onDecline = {})
        assertEquals(key, controller.pending.value?.key)
    }

    @Test
    fun `requestIfFirstTime is dropped when one is already pending`() {
        val controller = FirstTriggerPromptController(FakeSeenStore())
        var secondBuilt = false
        controller.requestIfFirstTime(key, onAccept = {}, onDecline = {})
        controller.requestIfFirstTime(key, onAccept = { secondBuilt = true }, onDecline = {})
        // The second request must not replace the first, and its actions are never wired up.
        assertFalse(secondBuilt)
        assertEquals(key, controller.pending.value?.key)
    }

    @Test
    fun `requestIfFirstTime is dropped once the prompt has been seen`() {
        val store = FakeSeenStore()
        val controller = FirstTriggerPromptController(store)
        controller.markSeen(key)
        controller.requestIfFirstTime(key, onAccept = {}, onDecline = {})
        assertNull(controller.pending.value)
    }

    @Test
    fun `finish accept clears pending, marks seen, and runs only the accept action`() {
        val controller = FirstTriggerPromptController(FakeSeenStore())
        var accepted = false
        var declined = false
        controller.requestIfFirstTime(key, onAccept = { accepted = true }, onDecline = { declined = true })

        controller.finish(accept = true)

        assertTrue(accepted)
        assertFalse(declined)
        assertNull(controller.pending.value)
        assertTrue(controller.hasSeen(key))
    }

    @Test
    fun `finish decline runs only the decline action and marks seen`() {
        val controller = FirstTriggerPromptController(FakeSeenStore())
        var accepted = false
        var declined = false
        controller.requestIfFirstTime(key, onAccept = { accepted = true }, onDecline = { declined = true })

        controller.finish(accept = false)

        assertFalse(accepted)
        assertTrue(declined)
        assertNull(controller.pending.value)
        assertTrue(controller.hasSeen(key))
    }

    @Test
    fun `reset re-arms a seen prompt so it can surface again`() {
        val controller = FirstTriggerPromptController(FakeSeenStore())
        controller.markSeen(key)
        controller.reset(key)
        controller.requestIfFirstTime(key, onAccept = {}, onDecline = {})
        assertEquals(key, controller.pending.value?.key)
    }

    @Test
    fun `finish is a no-op when nothing is pending`() {
        val controller = FirstTriggerPromptController(FakeSeenStore())
        controller.finish(accept = true)
        assertNull(controller.pending.value)
        assertFalse(controller.hasSeen(key))
    }
}
