package org.ntust.app.tigerduck.ui.screen.whatsnew

import androidx.annotation.StringRes
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.WhatsNewSummary
import org.ntust.app.tigerduck.update.WhatsNewGate

/** One step of What's New: a release's feature page, or its summary. */
sealed interface WhatsNewStep {
    /** Unique in a flow: a page's own id, a summary's `summary:<versionCode>`. */
    val id: String

    data class Page(val page: WhatsNewPage) : WhatsNewStep {
        override val id: String get() = page.id
    }

    data class Summary(val summary: WhatsNewSummary) : WhatsNewStep {
        override val id: String get() = "summary:${summary.versionCode}"
    }
}

/**
 * One run of What's New: the [steps] to go through, in order.
 * Never empty — [from] returns null instead.
 */
class WhatsNewFlow(val steps: List<WhatsNewStep>) {
    /** One release's [pages], then its [summary] if there is one. */
    constructor(pages: List<WhatsNewPage>, summary: WhatsNewSummary?) :
        this(pages.map(WhatsNewStep::Page) + listOfNotNull(summary?.let(WhatsNewStep::Summary)))

    val stepCount: Int get() = steps.size

    /** The ids of the feature pages among [steps], saved across a config-change recreation. */
    val pageIds: List<String> get() = steps.mapNotNull { (it as? WhatsNewStep.Page)?.page?.id }

    /** The primary button on [step]: Next on every step but the last, and Continue, which finishes, on the last. */
    @StringRes
    fun advanceLabel(step: Int): Int = if (step == steps.lastIndex) R.string.whats_new_continue else R.string.action_next

    companion object {
        /**
         * Materializes a [WhatsNewGate.Plan.Show] into its steps: release by
         * release, oldest first, each release's pages and then its summary,
         * after any page that [leads the run][WhatsNewPage.leadsTheRun].
         *
         * [restoredPageIds] is the [pageIds] of a flow that was on screen
         * before a config-change recreation. When given, exactly those pages
         * come back, in the same order as a fresh run, and the "only if this applies" checks
         * are skipped: an answer the user already gave can flip a check, and
         * re-filtering would shift every page after it under their thumb.
         * Summaries have no such check, so the plan's come back as they were.
         *
         * Otherwise a page is dropped when it doesn't apply — on a replay
         * too.
         *
         * Null when nothing is left to show.
         */
        fun from(
            plan: WhatsNewGate.Plan.Show,
            catalog: Map<Int, List<WhatsNewPage>>,
            summaries: Map<Int, WhatsNewSummary>,
            restoredPageIds: Collection<String>? = null,
        ): WhatsNewFlow? {
            val keep: (WhatsNewPage) -> Boolean = { page ->
                when {
                    restoredPageIds != null -> page.id in restoredPageIds
                    else -> page.isApplicable()
                }
            }
            val steps = (plan.pageVersions + plan.summaryVersions).distinct().sorted().flatMap { version ->
                val pages = if (version in plan.pageVersions) catalog[version].orEmpty().filter(keep) else emptyList()
                val summary = summaries[version]?.takeIf { version in plan.summaryVersions }
                pages.map(WhatsNewStep::Page) + listOfNotNull(summary?.let(WhatsNewStep::Summary))
            }
                // A page two releases both ask shows once, where the older release asks it.
                .distinctBy { it.id }
                .sortedByDescending { it is WhatsNewStep.Page && it.page.leadsTheRun }
            return if (steps.isEmpty()) null else WhatsNewFlow(steps)
        }
    }
}
