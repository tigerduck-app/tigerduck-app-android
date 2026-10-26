package org.ntust.app.tigerduck.ui.firsttrigger

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.R

/** The flip-to-library offer in the approved words (tigerduck-app/tigerduck-app#434). */
class FirstTriggerStringsTest {

    @Test
    fun `the flip offer asks to turn the gesture on, or maybe later`() {
        val strings = stringsFor(FirstTriggerPromptKey.FLIP_TO_LIBRARY)
        assertEquals(R.string.first_trigger_flip_to_library_title, strings.titleRes)
        assertEquals(R.string.settings_flip_to_library_summary, strings.messageRes)
        assertEquals(R.string.first_trigger_flip_to_library_turn_on, strings.acceptRes)
        assertEquals(R.string.permission_warning_action_later, strings.declineRes)
    }
}
