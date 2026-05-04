package org.ntust.app.tigerduck.data.model

import androidx.annotation.StringRes
import org.ntust.app.tigerduck.R

// TODO: Add user adjustment of the order and visibility of sections
data class HomeSection(
    val id: String,
    val type: HomeSectionType,
    val title: String,
    val sortOrder: Int,
    val isVisible: Boolean,
    val widgets: List<WidgetItem> = emptyList()
) {
    enum class HomeSectionType(
        val defaultTitleKey: String,
        @param:StringRes val defaultTitleRes: Int,
    ) {
        TODAY_COURSES("today-courses", R.string.home_section_today_courses),
        UPCOMING_ASSIGNMENTS("upcoming-assignments", R.string.home_section_upcoming_assignments),

        @Deprecated("Feature temporarily disabled")
        QUICK_WIDGETS("quick-widgets", R.string.home_section_quick_widgets),
        CUSTOM("custom", R.string.home_section_custom);
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
            // TODO: 快速功能
//            HomeSection(
//                "quick-widgets", HomeSectionType.QUICK_WIDGETS, HomeSectionType.QUICK_WIDGETS.defaultTitleKey, 2, true,
//                widgets = listOf(
//                    WidgetItem("w1", AppFeature.FREE_LUNCH),
//                    WidgetItem("w2", AppFeature.EMPTY_CLASSROOM),
//                    WidgetItem("w3", AppFeature.SCHOLARSHIP),
//                    WidgetItem("w4", AppFeature.SCORE),
//                )
//            )
        )
    }
}

data class WidgetItem(
    val id: String,
    val feature: AppFeature
)
