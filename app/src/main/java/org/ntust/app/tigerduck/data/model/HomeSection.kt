package org.ntust.app.tigerduck.data.model

// TODO: Add user adjustment of the order and visibility of sections
data class HomeSection(
    val id: String,
    val type: HomeSectionType,
    val title: String,
    val sortOrder: Int,
    val isVisible: Boolean,
    val widgets: List<WidgetItem> = emptyList()
) {
    enum class HomeSectionType(val defaultTitle: String) {
        TODAY_COURSES("今日課程"),
        UPCOMING_ASSIGNMENTS("作業"),
        @Deprecated("Feature temporarily disabled")
        QUICK_WIDGETS("快速功能"),
        CUSTOM("自訂區塊");
    }

    companion object {
        fun defaults(): List<HomeSection> = listOf(
            HomeSection("today-courses", HomeSectionType.TODAY_COURSES, "今日課程", 0, true),
            HomeSection("upcoming-assignments", HomeSectionType.UPCOMING_ASSIGNMENTS, "作業", 1, true),
            // TODO: 快速功能
//            HomeSection(
//                "quick-widgets", HomeSectionType.QUICK_WIDGETS, "快速功能", 2, true,
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
