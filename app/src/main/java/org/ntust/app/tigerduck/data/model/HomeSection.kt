package org.ntust.app.tigerduck.data.model

import androidx.annotation.StringRes
import org.ntust.app.tigerduck.R

/**
 * One block of the Home tab. Persisted by Gson as `homeSections` in
 * AppPreferences, which also reads it back through `restoreHomeSections`,
 * the one place that repairs what an older build may have written (see
 * CLAUDE.md, "Upgrade-safe persistence", before adding a field here).
 */
data class HomeSection(
    val id: String,
    val type: HomeSectionType,
    val title: String,
    val sortOrder: Int,
    val isVisible: Boolean,
    /** The shortcut cards of a section whose type [HomeSectionType.holdsShortcuts]; empty otherwise. */
    val widgets: List<WidgetItem> = emptyList()
) {
    enum class HomeSectionType(
        val defaultTitleKey: String,
        @param:StringRes val defaultTitleRes: Int,
    ) {
        TODAY_COURSES("today-courses", R.string.home_section_today_courses),
        UPCOMING_ASSIGNMENTS("upcoming-assignments", R.string.home_section_upcoming_assignments),
        QUICK_WIDGETS("quick-widgets", R.string.home_section_quick_widgets),
        CUSTOM("custom", R.string.home_custom_section);

        /**
         * Whether the section is a grid of feature shortcuts. Quick widgets
         * and a custom section are the same grid under different titles, as
         * on iOS (`WidgetGridView` renders both), so a quick feature added
         * from the Add dialog goes to whichever of them comes first.
         */
        val holdsShortcuts: Boolean
            get() = this == QUICK_WIDGETS || this == CUSTOM
    }

    companion object {
        fun defaults(): List<HomeSection> = listOf(
            HomeSection(
                "today-courses",
                HomeSectionType.TODAY_COURSES,
                HomeSectionType.TODAY_COURSES.defaultTitleKey,
                0,
                true
            ),
            HomeSection(
                "upcoming-assignments",
                HomeSectionType.UPCOMING_ASSIGNMENTS,
                HomeSectionType.UPCOMING_ASSIGNMENTS.defaultTitleKey,
                1,
                true
            ),
            // QUICK_WIDGETS is absent from the defaults, as on iOS: it is
            // opt-in through the Add dialog, which also creates it the first
            // time a quick feature is added with nowhere to go.
        )
    }
}

data class WidgetItem(
    val id: String,
    val feature: AppFeature
)
