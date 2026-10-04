package org.ntust.app.tigerduck.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.update.WhatsNewGate.Plan

class WhatsNewGateTest {

    @Test
    fun `does not show on a fresh install`() {
        assertFalse(
            WhatsNewGate.shouldShow(
                lastSeenVersionCode = AppPreferences.WHATS_NEW_UNSET,
                currentVersionCode = 21,
            )
        )
    }

    @Test
    fun `shows after an upgrade`() {
        assertTrue(WhatsNewGate.shouldShow(lastSeenVersionCode = 20, currentVersionCode = 21))
    }

    @Test
    fun `does not show when already on the current version`() {
        assertFalse(WhatsNewGate.shouldShow(lastSeenVersionCode = 21, currentVersionCode = 21))
    }

    @Test
    fun `does not show when last seen is somehow newer`() {
        assertFalse(WhatsNewGate.shouldShow(lastSeenVersionCode = 22, currentVersionCode = 21))
    }

    @Test
    fun `does not show when last seen is the replay sentinel`() {
        assertFalse(
            WhatsNewGate.shouldShow(
                lastSeenVersionCode = AppPreferences.WHATS_NEW_REPLAY,
                currentVersionCode = 21,
            )
        )
    }

    // --- plan ---

    private fun plan(
        lastSeen: Int,
        current: Int = 30,
        onboarded: Boolean = true,
        freshStart: Boolean = true,
        pages: Set<Int> = emptySet(),
        summaries: Set<Int> = emptySet(),
    ) = WhatsNewGate.plan(
        lastSeen = lastSeen,
        current = current,
        hasCompletedOnboarding = onboarded,
        freshStart = freshStart,
        pageVersions = pages,
        summaryVersions = summaries,
    )

    @Test
    fun `stacks feature pages of every skipped version oldest first, then the current summary`() {
        val result = plan(lastSeen = 26, pages = setOf(29, 27, 30, 25), summaries = setOf(27, 30))
        assertEquals(Plan.Show(listOf(27, 29, 30), summaryVersion = 30, replay = false), result)
    }

    @Test
    fun `skipped versions contribute pages but not their summaries`() {
        val result = plan(lastSeen = 26, pages = emptySet(), summaries = setOf(27, 28))
        assertEquals(Plan.RecordOnly, result)
    }

    @Test
    fun `pages without a summary still show`() {
        val result = plan(lastSeen = 29, pages = setOf(30))
        assertEquals(Plan.Show(listOf(30), summaryVersion = null, replay = false), result)
    }

    @Test
    fun `a summary without pages still shows`() {
        val result = plan(lastSeen = 29, summaries = setOf(30))
        assertEquals(Plan.Show(emptyList(), summaryVersion = 30, replay = false), result)
    }

    @Test
    fun `nothing registered for the skipped range records only`() {
        assertEquals(Plan.RecordOnly, plan(lastSeen = 29, pages = setOf(12), summaries = setOf(12)))
    }

    @Test
    fun `pages registered ahead of the running build are not shown`() {
        val result = plan(lastSeen = 29, pages = setOf(30, 31), summaries = setOf(31))
        assertEquals(Plan.Show(listOf(30), summaryVersion = null, replay = false), result)
    }

    @Test
    fun `same version or a downgrade records only`() {
        assertEquals(Plan.RecordOnly, plan(lastSeen = 30, pages = setOf(30), summaries = setOf(30)))
        assertEquals(Plan.RecordOnly, plan(lastSeen = 31, pages = setOf(30), summaries = setOf(30)))
    }

    @Test
    fun `fresh install records only`() {
        val result = plan(
            lastSeen = AppPreferences.WHATS_NEW_UNSET,
            onboarded = false,
            pages = setOf(30),
            summaries = setOf(30),
        )
        assertEquals(Plan.RecordOnly, result)
    }

    @Test
    fun `pre-feature upgrade shows only the running version, never the history`() {
        val result = plan(
            lastSeen = AppPreferences.WHATS_NEW_UNSET,
            onboarded = true,
            pages = setOf(27, 28, 30),
            summaries = setOf(28, 30),
        )
        assertEquals(Plan.Show(listOf(30), summaryVersion = 30, replay = false), result)
    }

    @Test
    fun `pre-feature upgrade with nothing for the running version records only`() {
        val result = plan(
            lastSeen = AppPreferences.WHATS_NEW_UNSET,
            onboarded = true,
            pages = setOf(27),
            summaries = setOf(28),
        )
        assertEquals(Plan.RecordOnly, result)
    }

    @Test
    fun `replay sentinel shows the newest registered version`() {
        val result = plan(
            lastSeen = AppPreferences.WHATS_NEW_REPLAY,
            pages = setOf(27, 31),
            summaries = setOf(28, 31),
        )
        assertEquals(Plan.Show(listOf(31), summaryVersion = 31, replay = true), result)
    }

    @Test
    fun `replay of a version with only a summary has no pages`() {
        val result = plan(
            lastSeen = AppPreferences.WHATS_NEW_REPLAY,
            pages = setOf(27),
            summaries = setOf(28),
        )
        assertEquals(Plan.Show(emptyList(), summaryVersion = 28, replay = true), result)
    }

    @Test
    fun `replay sentinel with nothing registered records only`() {
        assertEquals(Plan.RecordOnly, plan(lastSeen = AppPreferences.WHATS_NEW_REPLAY))
    }

    @Test
    fun `replay sentinel is deferred on a config-change recreation`() {
        val result = plan(
            lastSeen = AppPreferences.WHATS_NEW_REPLAY,
            freshStart = false,
            summaries = setOf(30),
        )
        assertEquals(Plan.Defer, result)
    }

    @Test
    fun `replay is null when nothing is registered`() {
        assertNull(WhatsNewGate.replay(emptySet(), emptySet()))
    }

    // --- collectPages ---

    @Test
    fun `collectPages keeps version order and drops pages that do not apply`() {
        val catalog = mapOf(
            27 to listOf("a", "skip-b"),
            29 to listOf("c"),
            30 to listOf("skip-d", "e"),
        )
        val pages = WhatsNewGate.collectPages(listOf(27, 29, 30), catalog) { !it.startsWith("skip") }
        assertEquals(listOf("a", "c", "e"), pages)
    }

    @Test
    fun `collectPages tolerates versions missing from the catalog`() {
        val pages = WhatsNewGate.collectPages(listOf(28), mapOf(27 to listOf("a"))) { true }
        assertTrue(pages.isEmpty())
    }
}
