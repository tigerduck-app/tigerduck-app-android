package org.ntust.app.tigerduck.ui.screen.whatsnew

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import androidx.compose.material.icons.filled.Info
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
    fun `the page that leads a run comes first, though its release is not the oldest`() {
        // Upgrading from before 2.3.0 shows 2.3.0's pages and 2.4.0's: where to see
        // them again opens the whole run, not 2.4.0's part of it.
        val leading = WhatsNewPage.Feature(
            id = "where-again",
            visual = WhatsNewVisual.Icon(androidx.compose.material.icons.Icons.Filled.Info),
            title = WhatsNewText(en = "Again?", zhHant = "Again?"),
            body = WhatsNewText(en = "In Settings.", zhHant = "In Settings."),
            leadsTheRun = true,
        )
        val spanning = mapOf(28 to listOf(page("a"), page("b")), 30 to listOf(leading, page("c")))
        val plan = Plan.Show(listOf(28, 30), summaryVersion = 30, replay = false)

        assertEquals(listOf("where-again", "a", "b", "c"), WhatsNewFlow.from(plan, spanning, emptyMap())?.pageIds)
        // And so after a recreation, which brings back the same pages.
        assertEquals(
            listOf("where-again", "a", "b", "c"),
            WhatsNewFlow.from(plan, spanning, emptyMap(), restoredPageIds = listOf("a", "b", "where-again", "c"))?.pageIds,
        )
    }

    @Test
    fun `a page two releases both ask shows once, at its first place`() {
        // 2.3.0 and 2.4.0 both ask whether Mail takes Calendar's place; upgrading from before
        // 2.3.0 shows both releases.
        val both = mapOf(28 to listOf(page("intro"), page("same")), 30 to listOf(page("a"), page("same"), page("b")))

        val flow = WhatsNewFlow.from(Plan.Show(listOf(28, 30), summaryVersion = null, replay = false), both, emptyMap())

        assertEquals(listOf("intro", "same", "a", "b"), flow?.pageIds)
    }

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
