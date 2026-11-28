package org.ntust.app.tigerduck.ui.screen.whatsnew

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import androidx.compose.material.icons.filled.Info
import org.ntust.app.tigerduck.R
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

    private fun summary(versionCode: Int) = WhatsNewSummary(
        versionCode = versionCode,
        title = "What's new in $versionCode",
        items = listOf(WhatsNewSummaryItem(title = "Row", body = null, icon = null)),
    )

    private val summary = summary(29)

    private fun summaries(vararg versionCodes: Int) = versionCodes.associateWith(::summary)

    private val WhatsNewFlow?.stepIds get() = this?.steps?.map { it.id }

    @Test
    fun `the page that leads a run comes first, though its release is not the oldest`() {
        // Upgrading from before 2.3.0 shows 2.3.0's pages and 2.4.0's: where to see
        // them again opens the whole run, not 2.4.0's part of it, ahead of 2.3.0's summary too.
        val leading = WhatsNewPage.Feature(
            id = "where-again",
            visual = WhatsNewVisual.Icon(androidx.compose.material.icons.Icons.Filled.Info),
            title = WhatsNewText(en = "Again?", zhHant = "Again?"),
            body = WhatsNewText(en = "In Settings.", zhHant = "In Settings."),
            leadsTheRun = true,
        )
        val spanning = mapOf(28 to listOf(page("a"), page("b")), 30 to listOf(leading, page("c")))
        val plan = Plan.Show(listOf(28, 30), summaryVersions = listOf(28, 30), replay = false)

        val run = listOf("where-again", "a", "b", "summary:28", "c", "summary:30")
        assertEquals(run, WhatsNewFlow.from(plan, spanning, summaries(28, 30)).stepIds)
        // And so after a recreation, which brings back the same pages.
        assertEquals(
            run,
            WhatsNewFlow.from(plan, spanning, summaries(28, 30), restoredPageIds = listOf("a", "b", "where-again", "c")).stepIds,
        )
    }

    @Test
    fun `a page two releases both ask shows once, at its first place`() {
        // 2.3.0 and 2.4.0 both ask whether Mail takes Calendar's place; upgrading from before
        // 2.3.0 shows both releases.
        val both = mapOf(28 to listOf(page("intro"), page("same")), 30 to listOf(page("a"), page("same"), page("b")))

        val flow = WhatsNewFlow.from(Plan.Show(listOf(28, 30), summaryVersions = emptyList(), replay = false), both, emptyMap())

        assertEquals(listOf("intro", "same", "a", "b"), flow?.pageIds)
    }

    @Test
    fun `every skipped version's summary shows, oldest first, right after its own pages`() {
        val flow = WhatsNewFlow.from(
            Plan.Show(listOf(27, 29), summaryVersions = listOf(26, 28, 29), replay = false),
            catalog,
            summaries(29, 28, 26, 30),
        )

        assertEquals(listOf("summary:26", "a", "summary:28", "c", "summary:29"), flow.stepIds)
    }

    @Test
    fun `a summary the plan names but the locale has none of is left out`() {
        val flow = WhatsNewFlow.from(
            Plan.Show(listOf(29), summaryVersions = listOf(28, 29), replay = false),
            catalog,
            summaries(29),
        )

        assertEquals(listOf("c", "summary:29"), flow.stepIds)
    }

    @Test
    fun `Next on every step but the last, Continue on the last`() {
        val flow = WhatsNewFlow.from(
            Plan.Show(listOf(29), summaryVersions = listOf(28, 29), replay = false),
            catalog,
            summaries(28, 29),
        )!!

        assertEquals(
            listOf(R.string.action_next, R.string.action_next, R.string.whats_new_continue),
            flow.steps.indices.map(flow::advanceLabel),
        )
        val single = WhatsNewFlow(pages = emptyList(), summary = summary)
        assertEquals(R.string.whats_new_continue, single.advanceLabel(0))
    }

    @Test
    fun `drops pages that do not apply after an upgrade`() {
        val flow = WhatsNewFlow.from(
            Plan.Show(listOf(27, 29), summaryVersions = listOf(29), replay = false),
            catalog,
            mapOf(29 to summary),
        )
        assertEquals(listOf("a", "c"), flow?.pageIds)
        assertEquals(listOf("a", "c", "summary:29"), flow.stepIds)
        assertEquals(3, flow?.stepCount)
    }

    @Test
    fun `replay drops pages that do not apply too`() {
        val flow = WhatsNewFlow.from(
            Plan.Show(listOf(27), summaryVersions = emptyList(), replay = true),
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
            Plan.Show(listOf(27, 29), summaryVersions = listOf(27, 29), replay = false),
            flipped,
            summaries(27, 29),
            restoredPageIds = listOf("a", "c"),
        )
        assertEquals(listOf("a", "c"), flow?.pageIds)
        assertEquals(listOf("a", "summary:27", "c", "summary:29"), flow.stepIds)
    }

    @Test
    fun `a summary-only flow restores as summary-only`() {
        val flow = WhatsNewFlow.from(
            Plan.Show(listOf(27), summaryVersions = listOf(29), replay = false),
            catalog,
            mapOf(29 to summary),
            restoredPageIds = emptyList(),
        )
        assertEquals(emptyList<String>(), flow?.pageIds)
        assertEquals(1, flow?.stepCount)
    }

    @Test
    fun `a version missing from the catalog adds no pages`() {
        val flow = WhatsNewFlow.from(Plan.Show(listOf(28, 29), summaryVersions = emptyList(), replay = false), catalog, emptyMap())

        assertEquals(listOf("c"), flow?.pageIds)
    }

    @Test
    fun `null when every page is filtered out and there is no summary`() {
        val flow = WhatsNewFlow.from(
            Plan.Show(listOf(27), summaryVersions = listOf(27), replay = false),
            mapOf(27 to listOf(page("b", applies = false))),
            emptyMap(),
        )
        assertNull(flow)
    }
}
