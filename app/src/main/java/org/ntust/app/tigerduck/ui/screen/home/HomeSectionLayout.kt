// Add / remove / reorder for the Home screen's section list.
//
// Every operation renumbers `sortOrder` to match list position afterwards.
// That redundancy is the point: the list is persisted as JSON and read back
// by code that sorts on the field, so a gap or a duplicate left behind by an
// edit shows up later as sections silently swapping places.
//
// Extracted from HomeViewModel, which only needs to persist what these
// return.

package org.ntust.app.tigerduck.ui.screen.home

import org.ntust.app.tigerduck.data.model.HomeSection

object HomeSectionLayout {

    fun remove(sections: List<HomeSection>, sectionId: String): List<HomeSection> =
        sections.filter { it.id != sectionId }.renumbered()

    /**
     * Move [fromId] into [toId]'s slot.
     *
     * Id-based rather than index-based because Home renders a *filtered*
     * list — the today-courses section drops out of the term window (see
     * [org.ntust.app.tigerduck.AppConstants.CurrentTerm]) — so a position in
     * what the user dragged is not a position in the stored layout.
     * Resolving both ends here keeps the two from drifting.
     *
     * Returns [sections] unchanged if either id is unknown or they are the
     * same, so a drag that ends where it started is a no-op rather than a
     * rewrite.
     */
    fun move(sections: List<HomeSection>, fromId: String, toId: String): List<HomeSection> {
        val list = sections.toMutableList()
        val from = list.indexOfFirst { it.id == fromId }
        val to = list.indexOfFirst { it.id == toId }
        if (from < 0 || to < 0 || from == to) return sections
        list.add(to, list.removeAt(from))
        return list.renumbered()
    }

    /**
     * Append a new section. [id] is a parameter rather than generated here so
     * the result is a function of its inputs and can be asserted on.
     */
    fun add(
        sections: List<HomeSection>,
        id: String,
        type: HomeSection.HomeSectionType,
        title: String,
    ): List<HomeSection> = sections + HomeSection(
        id = id,
        type = type,
        title = title,
        sortOrder = sections.size,
        isVisible = true,
    )

    private fun List<HomeSection>.renumbered(): List<HomeSection> =
        mapIndexed { i, s -> s.copy(sortOrder = i) }
}
