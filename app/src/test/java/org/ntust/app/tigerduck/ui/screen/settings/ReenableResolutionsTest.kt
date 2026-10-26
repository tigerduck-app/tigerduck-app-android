package org.ntust.app.tigerduck.ui.screen.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReenableResolutionsTest {

    @Test
    fun `a check runs at once when no answer is being applied`() {
        assertNotNull(ReenableResolutions().checkStarting { })
    }

    @Test
    fun `a check waits for the answer being applied and runs when it finishes`() {
        val resolutions = ReenableResolutions()
        var checks = 0
        resolutions.start()

        assertNull(resolutions.checkStarting { checks++ })
        assertEquals(0, checks)
        // Letting the hold go here would end it under the check's own dialog.
        assertFalse(resolutions.finish())
        assertEquals(1, checks)
        assertNotNull(resolutions.checkStarting { checks++ })
    }

    @Test
    fun `the hold is let go when the last answer finishes and no check waits`() {
        val resolutions = ReenableResolutions()
        resolutions.start()

        assertTrue(resolutions.finish())
    }

    @Test
    fun `an answer finishing first keeps the hold for one still being applied`() {
        val resolutions = ReenableResolutions()
        var checks = 0
        resolutions.start()
        resolutions.start()
        resolutions.checkStarting { checks++ }

        assertFalse(resolutions.finish())
        assertEquals(0, checks)
        assertFalse(resolutions.finish())
        assertEquals(1, checks)
    }

    @Test
    fun `a check keeps its result when no answer starts before it is done`() {
        val resolutions = ReenableResolutions()
        val token = resolutions.checkStarting { }!!

        assertTrue(resolutions.isCurrent(token))
    }

    @Test
    fun `a check overtaken by an answer drops its result`() {
        val resolutions = ReenableResolutions()
        val token = resolutions.checkStarting { }!!
        resolutions.start()
        resolutions.finish()

        assertFalse(resolutions.isCurrent(token))
    }

    @Test
    fun `a category switched on while an answered dialog's check ran is checked once the answer is applied`() {
        // As the Settings screens run them: each check reads the pending set as it starts.
        val resolutions = ReenableResolutions()
        var pending = setOf("courses")
        val checked = mutableListOf<Set<String>>()
        fun check(): Int? = resolutions.checkStarting { check() }?.also { checked += pending }

        val first = check()!!
        // Names switched back on while the courses check waits for the server.
        pending = pending + "course_names"
        val second = check()!!
        // The courses check finds a difference, and its dialog is answered before the second returns.
        assertTrue(resolutions.isCurrent(first))
        val answered = listOf("courses")
        val left = unanswered(pending, answered)
        pending = heldUntilAnswered(answered) + left
        resolutions.start()
        if (left.isNotEmpty()) check()

        assertFalse("the answer changed what the second compared", resolutions.isCurrent(second))
        assertEquals(setOf("course_names"), pending)
        resolutions.finish()
        assertEquals(listOf(setOf("courses"), setOf("courses", "course_names"), setOf("course_names")), checked)
    }

    @Test
    fun `a check that does not wait leaves a Settings screen's waiting check in place`() {
        // The owed check a sync starts must not take the place of the one a
        // Settings screen left waiting, whose dialog it would then never show.
        val resolutions = ReenableResolutions()
        val ran = mutableListOf<String>()
        resolutions.start()
        assertNull(resolutions.checkStarting { ran += "settings" })

        assertNull(resolutions.checkStarting(waits = false) { ran += "owed" })
        resolutions.finish()

        assertEquals(listOf("settings"), ran)
    }
}
