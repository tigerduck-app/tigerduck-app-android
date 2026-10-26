package org.ntust.app.tigerduck.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.preferences.ReviewPromptStore
import java.time.LocalDate

/**
 * The rule behind the Play Store rating request
 * (tigerduck-app-android#332): asked once, ever, at a calm moment after the
 * class table has loaded on [ReviewPrompt.DAYS_OF_USE] different days.
 *
 * Flavor-neutral: the same rule runs on fdroid, where the launcher is a stub
 * that never hands out a flow, so nothing is recorded as asked there.
 */
class ReviewPromptTest {

    /** What a fresh install's preferences hold: nothing. */
    private class FakeStore : ReviewPromptStore {
        override var reviewPromptDaysUsed: Int = 0
        override var reviewPromptLastDay: String? = null
        override var reviewPromptAsked: Boolean = false
    }

    private val store = FakeStore()
    private var today: LocalDate = LocalDate.of(2026, 10, 12)
    private var mayAsk = true

    // What the page and the activity answer right before a launch.
    private var pageResumed = true
    private var windowFocused = true

    /** A process: the prompt keeps its in-memory state, the store outlives it. */
    private fun process() = ReviewPrompt(store, { today }, { mayAsk })

    private fun days(count: Int): List<LocalDate> =
        (0 until count).map { LocalDate.of(2026, 10, 1).plusDays(it.toLong()) }

    private fun ReviewPrompt.loadedOn(days: List<LocalDate>) {
        for (day in days) {
            today = day
            classTableLoaded()
        }
    }

    private fun ReviewPrompt.moment() = calmMoment { pageResumed }

    private fun ReviewPrompt.launchNow(launch: () -> Boolean) =
        launchIfStillCalm(windowFocused = { windowFocused }, launch = launch)

    /** Five days of use and a calm moment: the ask is pending. */
    private fun readyProcess(): ReviewPrompt = process().also {
        it.loadedOn(days(5))
        it.moment()
        assertTrue(it.pending.value)
    }

    @Test
    fun `does not ask before the class table has loaded on five days`() {
        val prompt = process()
        prompt.loadedOn(days(4))
        prompt.moment()
        assertFalse(prompt.pending.value)
        assertEquals(4, store.reviewPromptDaysUsed)
    }

    @Test
    fun `a day counts once however often the class table loads`() {
        val prompt = process()
        repeat(6) { prompt.classTableLoaded() }
        prompt.moment()
        assertFalse(prompt.pending.value)
        assertEquals(1, store.reviewPromptDaysUsed)
        assertEquals("2026-10-12", store.reviewPromptLastDay)
    }

    @Test
    fun `asks at the first calm moment on the fifth day of use`() {
        val prompt = process()
        prompt.loadedOn(days(5))
        assertFalse("a load is not a calm moment", prompt.pending.value)
        prompt.moment()
        assertTrue(prompt.pending.value)
    }

    @Test
    fun `days counted by an earlier build keep counting`() {
        store.reviewPromptDaysUsed = 4
        store.reviewPromptLastDay = "2026-10-11"
        val prompt = process()
        prompt.classTableLoaded()
        prompt.moment()
        assertTrue(prompt.pending.value)
        assertEquals(5, store.reviewPromptDaysUsed)
    }

    @Test
    fun `launches the flow Play handed out while the gates still hold`() {
        val prompt = readyProcess()
        var launches = 0
        prompt.launchNow { launches++; true }
        assertEquals(1, launches)
        assertTrue(store.reviewPromptAsked)
        assertFalse(prompt.pending.value)
    }

    @Test
    fun `never asks again once the flow was launched`() {
        val prompt = readyProcess()
        prompt.launchNow { true }

        prompt.loadedOn(days(9))
        prompt.moment()
        assertFalse("asked again in the same process", prompt.pending.value)

        val later = process()
        later.loadedOn(listOf(LocalDate.of(2027, 3, 1)))
        later.moment()
        assertFalse("asked again in a later process", later.pending.value)
    }

    @Test
    fun `does not ask while signed out, in the wizard, in the demo account or during a sign-in`() {
        mayAsk = false
        val prompt = process()
        prompt.loadedOn(days(5))
        prompt.moment()
        assertFalse(prompt.pending.value)

        mayAsk = true
        prompt.moment()
        assertTrue(prompt.pending.value)
    }

    @Test
    fun `does not launch a flow handed out after the user signed out, and asks again later`() {
        val prompt = readyProcess()
        // The round trip to Play took long enough for a sign-out, a sign-in
        // sheet or a source failing.
        mayAsk = false
        var launched = false
        prompt.launchNow { launched = true; true }
        assertFalse(launched)
        assertFalse(store.reviewPromptAsked)
        assertFalse(prompt.pending.value)

        mayAsk = true
        prompt.moment()
        assertTrue("a withdrawn ask is not an attempt", prompt.pending.value)
    }

    @Test
    fun `does not launch once the page is no longer resumed, and asks again on its next resume`() {
        val prompt = readyProcess()
        // The round trip to Play took long enough for the page to be left,
        // or the activity to be paused.
        pageResumed = false
        var launched = false
        prompt.launchNow { launched = true; true }
        assertFalse(launched)
        assertFalse(store.reviewPromptAsked)
        assertFalse(prompt.pending.value)

        pageResumed = true
        prompt.moment()
        assertTrue("a withdrawn ask is not an attempt", prompt.pending.value)
    }

    @Test
    fun `does not launch while a dialog or sheet holds the window focus`() {
        val prompt = readyProcess()
        // A dialog, a sheet or a system prompt opened during the round trip:
        // each is a window of its own and takes the focus from the page's.
        windowFocused = false
        var launched = false
        prompt.launchNow { launched = true; true }
        assertFalse(launched)
        assertFalse(store.reviewPromptAsked)
        assertFalse(prompt.pending.value)

        windowFocused = true
        prompt.moment()
        assertTrue("a withdrawn ask is not an attempt", prompt.pending.value)
    }

    @Test
    fun `the page pausing or being left withdraws the pending ask`() {
        val prompt = readyProcess()
        prompt.withdraw()
        assertFalse(prompt.pending.value)

        var launched = false
        prompt.launchNow { launched = true; true }
        assertFalse("launched after the page was left", launched)

        prompt.moment()
        assertTrue("a withdrawn ask is not an attempt", prompt.pending.value)
    }

    @Test
    fun `a gate flipping while the ask is pending withdraws it`() {
        val prompt = readyProcess()
        mayAsk = false
        prompt.recheck()
        assertFalse(prompt.pending.value)

        mayAsk = true
        prompt.recheck()
        assertFalse("a recheck never arms the ask", prompt.pending.value)
        prompt.moment()
        assertTrue(prompt.pending.value)
    }

    @Test
    fun `does not ask right after a load failed`() {
        val prompt = process()
        prompt.loadedOn(days(5))
        prompt.loadFailed()
        prompt.moment()
        assertFalse(prompt.pending.value)

        prompt.classTableLoaded()
        prompt.moment()
        assertTrue(prompt.pending.value)
    }

    @Test
    fun `a failure withdraws an ask not yet launched, and the flow Play then hands out stays unlaunched`() {
        val prompt = readyProcess()
        prompt.loadFailed()
        assertFalse(prompt.pending.value)

        var launched = false
        prompt.launchNow { launched = true; true }
        assertFalse(launched)
        assertFalse(store.reviewPromptAsked)

        prompt.classTableLoaded()
        prompt.moment()
        assertTrue("a withdrawn ask is not an attempt", prompt.pending.value)
    }

    @Test
    fun `does not ask before the class table has loaded in this process`() {
        store.reviewPromptDaysUsed = 5
        store.reviewPromptLastDay = "2026-10-05"
        val prompt = process()
        prompt.moment()
        assertFalse("asked on a cold start, before content showed", prompt.pending.value)

        prompt.classTableLoaded()
        prompt.moment()
        assertTrue(prompt.pending.value)
    }

    @Test
    fun `tries once per process when Play would not hand out the flow`() {
        val prompt = readyProcess()
        prompt.refused()
        assertFalse(store.reviewPromptAsked)
        assertFalse(prompt.pending.value)
        prompt.moment()
        assertFalse("tried again in the same process", prompt.pending.value)

        val later = process()
        later.classTableLoaded()
        later.moment()
        assertTrue("not tried again in a later process", later.pending.value)
    }

    @Test
    fun `a flow that would not start is this process's one attempt too`() {
        val prompt = readyProcess()
        prompt.launchNow { false }
        assertFalse(store.reviewPromptAsked)
        prompt.moment()
        assertFalse(prompt.pending.value)
    }
}
