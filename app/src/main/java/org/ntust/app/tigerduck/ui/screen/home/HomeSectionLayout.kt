// Add / remove / reorder for the Home screen's section list.
//
// Order is carried by list position, not by `sortOrder` — nothing in the app
// sorts on that field, and Gson round-trips a JSON array in order. `sortOrder`
// is a mirror of the index that exists because it is part of the persisted
// shape (HomeSection lives in data.model, which proguard-rules keeps
// wholesale) and dropping it would be a schema change for a field no reader
// needs.
//
// Every operation here still renumbers it to match position, for one reason:
// a stored layout with duplicate or gapped values is indistinguishable from a
// corrupt one, so if a reader is ever added it must not inherit garbage. The
// restore path in AppPreferences.homeSections renumbers for the same reason —
// it filters QUICK_WIDGETS out without which `[0,1,2,3]` would come back as
// `[0,1,3]` and the next `add` would hand out a duplicate 3.
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
     *
     * Renumbers like the other two rather than trusting `sections.size` as the
     * new value: a restored layout that had a section filtered out of it is
     * shorter than its highest `sortOrder`, so `size` collides with a value
     * already in the list.
     */
    fun add(
        sections: List<HomeSection>,
        id: String,
        type: HomeSection.HomeSectionType,
        title: String,
    ): List<HomeSection> = (
        sections + HomeSection(
            id = id,
            type = type,
            title = title,
            sortOrder = sections.size,
            isVisible = true,
        )
        ).renumbered()

    private fun List<HomeSection>.renumbered(): List<HomeSection> =
        mapIndexed { i, s -> s.copy(sortOrder = i) }
}
