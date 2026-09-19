package org.ntust.app.tigerduck.ui.screen.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers [signInFieldValue], the rule behind "a prefill only ever fills an empty field" in
 * both ways into a sign-in form: the [LoginSheet] dialog and School Mail's signed-out card.
 *
 * The state behind each field is null until the field is edited, which is what makes the
 * rule exact: a correction already typed, or one restored after a rotation, is never
 * replaced by a prefill, however the prefill changes while the form is up.
 */
class LoginFieldPrefillTest {

    @Test
    fun `an untouched field takes the prefill`() {
        assertEquals("B10000001", signInFieldValue(null, "B10000001"))
    }

    @Test
    fun `with nothing to prefill an untouched field is empty`() {
        assertEquals("", signInFieldValue(null, ""))
    }

    @Test
    fun `a typed value is never overwritten by the prefill`() {
        assertEquals(
            "a correction the user typed has to survive, whatever the prefill now says",
            "B10000002",
            signInFieldValue("B10000002", "B10000001"),
        )
    }

    /**
     * Clearing a field is an edit like any other, so it holds "" rather than null -- the
     * prefill does not spring back into a field somebody just emptied.
     */
    @Test
    fun `a cleared field stays cleared`() {
        assertEquals("", signInFieldValue("", "B10000001"))
    }
}
