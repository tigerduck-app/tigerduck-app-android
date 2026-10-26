package org.ntust.app.tigerduck.ui.screen.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A re-enable check that failed, offline for example, ran again only at the
 * next Settings visit, and until then the categories it held waited: course
 * ignores among them, which the server went on reminding about. A full sync
 * or a poll that reaches the server now runs it while it is owed
 * ([OwedReenableCheck]), as on iOS (tigerduck-app/tigerduck-app#368).
 */
class OwedReenableCheckTest {

    private val owed = OwedReenableCheck()
    private val pending = setOf("assignments")

    @Test
    fun `a check is owed from launch until one gives a verdict`() {
        assertTrue(owed.due(pending, syncOn = true))

        owed.started()
        owed.ended(verdict = true)

        assertFalse("a verdict stops the retries; a closed dialog is not raised again by itself", owed.due(pending, syncOn = true))
    }

    @Test
    fun `a failed check, or one whose result was dropped, is owed again`() {
        owed.started()
        owed.ended(verdict = true)
        owed.started()
        owed.ended(verdict = false)

        assertTrue(owed.due(pending, syncOn = true))
    }

    @Test
    fun `not while a check runs, with nothing pending, or with sync off`() {
        owed.started()
        assertFalse(owed.due(pending, syncOn = true))
        owed.ended(verdict = false)

        assertFalse(owed.due(emptySet(), syncOn = true))
        assertFalse(owed.due(pending, syncOn = false))
    }
}
