package org.ntust.app.tigerduck.ui.screen.whatsnew

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.ntust.app.tigerduck.data.model.WhatsNewSummary
import org.ntust.app.tigerduck.data.model.WhatsNewSummaryItem
import org.ntust.app.tigerduck.update.WhatsNewGate.Plan

class WhatsNewFlowTest {

    private fun page(id: String, applies: Boolean = true) =
        WhatsNewPage.Custom(id = id, content = {}, isApplicable = { applies })

    private val catalog = mapOf(
        27 to listOf(page("a"), page("b", applies = false)),
        29 to listOf(page("c")),
    )

    private val summary = WhatsNewSummary(
        versionCode = 29,
        title = "What's new",
        items = listOf(WhatsNewSummaryItem(title = "Row", body = null, icon = null)),
    )

    @Test
    fun `drops pages that do not apply after an upgrade`() {
        val flow = WhatsNewFlow.from(
            Plan.Show(listOf(27, 29), summaryVersion = 29, replay = false),
            catalog,
            mapOf(29 to summary),
        )
        assertEquals(listOf("a", "c"), flow?.pageIds)
        assertEquals(summary, flow?.summary)
        assertEquals(3, flow?.stepCount)
    }

    @Test
    fun `replay drops pages that do not apply too`() {
        val flow = WhatsNewFlow.from(
            Plan.Show(listOf(27), summaryVersion = null, replay = true),
            catalog,
            emptyMap(),
        )
        assertEquals(listOf("a"), flow?.pageIds)
    }

    @Test
    fun `a recreation restores exactly the pages that were on screen`() {
        // "b" now applies and "a" no longer does — an answer flipped both —
        // but the user was looking at ["a", "c"], so that is what comes back.
        val flipped = mapOf(
            27 to listOf(page("a", applies = false), page("b", applies = true)),
            29 to listOf(page("c")),
        )
        val flow = WhatsNewFlow.from(
            Plan.Show(listOf(27, 29), summaryVersion = 29, replay = false),
            flipped,
            mapOf(29 to summary),
            restoredPageIds = listOf("a", "c"),
        )
        assertEquals(listOf("a", "c"), flow?.pageIds)
    }

    @Test
    fun `a summary-only flow restores as summary-only`() {
        val flow = WhatsNewFlow.from(
            Plan.Show(listOf(27), summaryVersion = 29, replay = false),
            catalog,
            mapOf(29 to summary),
            restoredPageIds = emptyList(),
        )
        assertEquals(emptyList<String>(), flow?.pageIds)
        assertEquals(1, flow?.stepCount)
    }

    @Test
    fun `null when every page is filtered out and there is no summary`() {
        val flow = WhatsNewFlow.from(
            Plan.Show(listOf(27), summaryVersion = 27, replay = false),
            mapOf(27 to listOf(page("b", applies = false))),
            emptyMap(),
        )
        assertNull(flow)
    }
}
