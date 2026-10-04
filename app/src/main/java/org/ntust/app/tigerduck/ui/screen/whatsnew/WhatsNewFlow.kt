package org.ntust.app.tigerduck.ui.screen.whatsnew

import org.ntust.app.tigerduck.data.model.WhatsNewSummary
import org.ntust.app.tigerduck.update.WhatsNewGate

/**
 * One run of the What's New sheet: the feature [pages] to step through, then
 * the [summary] page if there is one. Never empty — [from] returns null
 * instead.
 */
class WhatsNewFlow(
    val pages: List<WhatsNewPage>,
    val summary: WhatsNewSummary?,
) {
    val stepCount: Int get() = pages.size + if (summary != null) 1 else 0

    /** The ids of [pages], saved across a config-change recreation. */
    val pageIds: List<String> get() = pages.map { it.id }

    companion object {
        /**
         * Materializes a [WhatsNewGate.Plan.Show] into the pages and summary
         * it names.
         *
         * [restoredPageIds] is the [pageIds] of a flow that was on screen
         * before a config-change recreation. When given, exactly those pages
         * come back (in catalog order) and the "only if this applies" checks
         * are skipped: an answer the user already gave can flip a check, and
         * re-filtering would shift every page after it under their thumb.
         *
         * Null when nothing is left to show.
         */
        fun from(
            plan: WhatsNewGate.Plan.Show,
            catalog: Map<Int, List<WhatsNewPage>>,
            summaries: Map<Int, WhatsNewSummary>,
            restoredPageIds: Collection<String>? = null,
        ): WhatsNewFlow? {
            val pages = WhatsNewGate.collectPages(plan.pageVersions, catalog) { page ->
                when {
                    restoredPageIds != null -> page.id in restoredPageIds
                    plan.replay -> true
                    else -> page.isApplicable()
                }
            }
            val summary = plan.summaryVersion?.let(summaries::get)
            if (pages.isEmpty() && summary == null) return null
            return WhatsNewFlow(pages, summary)
        }
    }
}
