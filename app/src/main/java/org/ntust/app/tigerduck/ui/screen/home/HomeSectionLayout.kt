// Add / remove / reorder for the Home screen's section list, and for the
// shortcut cards inside a section that holds them.
//
// Order is carried by list position, not by `sortOrder` — nothing in the app
// sorts on that field, and Gson round-trips a JSON array in order. `sortOrder`
// is a mirror of the index that exists because it is part of the persisted
// shape (HomeSection lives in data.model, which proguard-rules keeps
// wholesale) and dropping it would be a schema change for a field no reader
// needs.
//
// Every section operation here still renumbers it to match position, for one
// reason: a stored layout with duplicate or gapped values is indistinguishable
// from a corrupt one, so if a reader is ever added it must not inherit garbage.
// The restore path in AppPreferences renumbers for the same reason — builds
// before 2.5 filtered QUICK_WIDGETS out of what they read, and a layout one of
// them re-saved can carry the gap that left, so the next `add` would hand out
// a duplicate.
//
// Every operation returns the very list it was given when it changes nothing,
// so HomeViewModel can skip the preferences write on `!==`.
//
// Extracted from HomeViewModel, which only needs to persist what these
// return.

package org.ntust.app.tigerduck.ui.screen.home

import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.data.model.HomeSection
import org.ntust.app.tigerduck.data.model.WidgetItem

object HomeSectionLayout {

    fun remove(sections: List<HomeSection>, sectionId: String): List<HomeSection> =
        sections.filter { it.id != sectionId }.renumbered()

    /**
     * Move [fromId] into [toId]'s slot.
     *
     * Id-based rather than index-based because Home renders a *filtered*
     * list — the today-courses section drops out of the term window (see
     * the published academic calendar) — so a position in
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

    /**
     * Put a shortcut to [feature] in the first section that holds shortcuts,
     * adding a Quick widgets section (id [sectionId], titled
     * [quickWidgetsTitle]) when Home has none, so the Add dialog's list always
     * does something — the iPhone's `HomeViewModel.addQuickFeature`.
     *
     * Returns [sections] unchanged when the feature is already somewhere on
     * Home: the dialog hides those, and this keeps a double tap from placing
     * a second copy.
     */
    fun addQuickFeature(
        sections: List<HomeSection>,
        feature: AppFeature,
        sectionId: String,
        widgetId: String,
        quickWidgetsTitle: String,
    ): List<HomeSection> {
        if (sections.any { s -> s.widgets.any { it.feature == feature } }) return sections
        val withHolder =
            if (sections.any { it.type.holdsShortcuts }) sections
            else add(sections, sectionId, HomeSection.HomeSectionType.QUICK_WIDGETS, quickWidgetsTitle)
        val target = withHolder.first { it.type.holdsShortcuts }
        return withHolder.map { s ->
            if (s.id != target.id) s
            else s.copy(widgets = s.widgets + WidgetItem(id = widgetId, feature = feature))
        }
    }

    /**
     * What the Add dialog's "quick features" list offers: the shipped
     * shortcut features ([AppFeature.widgetFeatures]) not yet placed in any
     * section, custom ones included, in that list's order.
     */
    fun addableQuickFeatures(sections: List<HomeSection>): List<AppFeature> {
        val placed = sections.flatMap { s -> s.widgets.map { it.feature } }.toSet()
        return AppFeature.widgetFeatures.filter { it !in placed }
    }

    /** Drop the shortcut [widgetId] from section [sectionId]; the section itself stays, empty or not. */
    fun removeWidget(sections: List<HomeSection>, sectionId: String, widgetId: String): List<HomeSection> {
        val section = sections.firstOrNull { it.id == sectionId } ?: return sections
        if (section.widgets.none { it.id == widgetId }) return sections
        return sections.map { s ->
            if (s.id != sectionId) s else s.copy(widgets = s.widgets.filter { it.id != widgetId })
        }
    }

    /**
     * Move the shortcut [widgetId] to position [toIndex] within section
     * [sectionId]. The target is an index, not an id, because the grid's
     * drag resolves where the finger is to a slot, and a slot past the last
     * card (the empty cell of a half row) is clamped by the caller
     * ([ShortcutGridGeometry]); an index outside the list is a no-op here.
     */
    fun moveWidget(
        sections: List<HomeSection>,
        sectionId: String,
        widgetId: String,
        toIndex: Int,
    ): List<HomeSection> {
        val section = sections.firstOrNull { it.id == sectionId } ?: return sections
        val from = section.widgets.indexOfFirst { it.id == widgetId }
        if (from < 0 || toIndex !in section.widgets.indices || from == toIndex) return sections
        val widgets = section.widgets.toMutableList()
        widgets.add(toIndex, widgets.removeAt(from))
        return sections.map { s -> if (s.id != sectionId) s else s.copy(widgets = widgets) }
    }

    private fun List<HomeSection>.renumbered(): List<HomeSection> =
        mapIndexed { i, s -> s.copy(sortOrder = i) }
}
