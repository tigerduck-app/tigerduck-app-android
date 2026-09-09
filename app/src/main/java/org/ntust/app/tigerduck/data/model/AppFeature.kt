package org.ntust.app.tigerduck.data.model

import androidx.annotation.StringRes
import org.ntust.app.tigerduck.R

enum class AppFeature(val id: String) {
    HOME("home"),
    CLASS_TABLE("classTable"),
    CALENDAR("calendar"),
    ANNOUNCEMENTS("announcements"),
    SCORE("score"),
    COURSE_SELECTION("courseSelection"),
    GRADUATION_REQUIREMENTS("graduationRequirements"),
    LIBRARY("library"),
    DISCUSSION_ROOM("discussionRoom"),
    LIBRARY_LECTURE("libraryLecture"),
    FREE_LUNCH("freeLunch"),
    CLUBS("clubs"),
    EMPTY_CLASSROOM("emptyClassroom"),
    SCHOLARSHIP("scholarship"),
    ENGLISH_VOCAB("englishVocab"),
    MORE("more"),
    SETTINGS("settings");

    @get:StringRes
    val displayNameRes: Int
        get() = when (this) {
            HOME -> R.string.feature_home
            CLASS_TABLE -> R.string.feature_class_table
            CALENDAR -> R.string.feature_calendar
            ANNOUNCEMENTS -> R.string.feature_announcements
            LIBRARY -> R.string.feature_library
            SCORE -> R.string.feature_score
            COURSE_SELECTION -> R.string.feature_course_selection
            GRADUATION_REQUIREMENTS -> R.string.feature_graduation_requirements
            DISCUSSION_ROOM -> R.string.feature_discussion_room
            LIBRARY_LECTURE -> R.string.feature_library_lecture
            FREE_LUNCH -> R.string.feature_free_lunch
            CLUBS -> R.string.feature_clubs
            EMPTY_CLASSROOM -> R.string.feature_empty_classroom
            SCHOLARSHIP -> R.string.feature_scholarship
            ENGLISH_VOCAB -> R.string.feature_english_vocab
            MORE -> R.string.feature_more
            SETTINGS -> R.string.feature_settings
        }

    @get:StringRes
    val shortDisplayNameRes: Int
        get() = when (this) {
            HOME -> R.string.feature_home_short
            CLASS_TABLE -> R.string.feature_class_table_short
            CALENDAR -> R.string.feature_calendar_short
            ANNOUNCEMENTS -> R.string.feature_announcements_short
            LIBRARY -> R.string.feature_library_short
            SCORE -> R.string.feature_score_short
            COURSE_SELECTION -> R.string.feature_course_selection_short
            GRADUATION_REQUIREMENTS -> R.string.feature_graduation_requirements_short
            DISCUSSION_ROOM -> R.string.feature_discussion_room_short
            LIBRARY_LECTURE -> R.string.feature_library_lecture_short
            FREE_LUNCH -> R.string.feature_free_lunch_short
            CLUBS -> R.string.feature_clubs_short
            EMPTY_CLASSROOM -> R.string.feature_empty_classroom_short
            SCHOLARSHIP -> R.string.feature_scholarship_short
            ENGLISH_VOCAB -> R.string.feature_english_vocab_short
            MORE -> R.string.feature_more_short
            SETTINGS -> R.string.feature_settings_short
        }

    val category: FeatureCategory?
        get() = when (this) {
            CLASS_TABLE, CALENDAR, SCORE, COURSE_SELECTION, GRADUATION_REQUIREMENTS -> FeatureCategory.ACADEMIC
            LIBRARY, DISCUSSION_ROOM, LIBRARY_LECTURE -> FeatureCategory.LIBRARY
            ANNOUNCEMENTS, FREE_LUNCH, CLUBS, EMPTY_CLASSROOM, SCHOLARSHIP -> FeatureCategory.LIFE
            ENGLISH_VOCAB -> FeatureCategory.LANGUAGE
            SETTINGS -> FeatureCategory.SYSTEM
            else -> null
        }

    val isLibraryRelated: Boolean
        get() = this == LIBRARY || this == DISCUSSION_ROOM || this == LIBRARY_LECTURE

    /**
     * Whether this feature has a real screen behind it.
     *
     * The single place that decides. Every list below is derived from it, so
     * shipping a feature is one edit here rather than three lists that can
     * drift apart — and it matches how iOS/macOS does it (`AppFeature
     * .isImplemented` in the Apple repo), which is what keeps the two
     * platforms showing the same set.
     *
     * MORE and SETTINGS are false because they are navigation chrome, not
     * feature pages: they never appear in the ordered lists below, and
     * nothing routes to them through a feature list.
     */
    val isImplemented: Boolean
        get() = when (this) {
            HOME, CLASS_TABLE, CALENDAR, ANNOUNCEMENTS, LIBRARY, SCORE -> true
            else -> false
        }

    companion object {
        val defaultTabs = listOf(HOME, CLASS_TABLE, CALENDAR)

        /**
         * Every feature that could be pinned to the bottom bar, in display
         * order, *including* ones that have not shipped — [isImplemented] is
         * what excludes them. Keeping the unshipped names here rather than
         * commented out means a feature lands in the right position the
         * moment it flips, instead of at the end of whatever list someone
         * remembered to uncomment.
         */
        private val pinnableOrder = listOf(
            HOME, CLASS_TABLE, CALENDAR, ANNOUNCEMENTS, LIBRARY, SCORE,
            COURSE_SELECTION, GRADUATION_REQUIREMENTS,
            DISCUSSION_ROOM, LIBRARY_LECTURE,
            FREE_LUNCH, CLUBS, EMPTY_CLASSROOM, SCHOLARSHIP,
            ENGLISH_VOCAB,
        )

        /** Same contract as [pinnableOrder], for the More screen's own order. */
        private val moreOrder = listOf(
            CLASS_TABLE, CALENDAR, SCORE,
            LIBRARY, ANNOUNCEMENTS,
            COURSE_SELECTION, GRADUATION_REQUIREMENTS,
            DISCUSSION_ROOM, LIBRARY_LECTURE,
            FREE_LUNCH, CLUBS, EMPTY_CLASSROOM, SCHOLARSHIP,
            ENGLISH_VOCAB,
        )

        val pinnableFeatures: List<AppFeature> = pinnableOrder.filter { it.isImplemented }

        val moreFeatures: List<AppFeature> = moreOrder.filter { it.isImplemented }

        /**
         * Features a user may have pinned before they were withdrawn, or that
         * were pinnable while still routing to a placeholder. Used to scrub
         * persisted tab configs on app open so the bottom bar never shows a
         * "coming soon" tab. Derived, so it can never fall out of step with
         * what actually shipped.
         */
        val unfinishedFeatures: Set<AppFeature> =
            pinnableOrder.filterNot { it.isImplemented }.toSet()

        fun fromId(id: String): AppFeature? = entries.firstOrNull { it.id == id }
    }
}

enum class FeatureCategory {
    ACADEMIC, LIBRARY, LIFE, LANGUAGE, SYSTEM;

    @get:StringRes
    val displayNameRes: Int
        get() = when (this) {
            ACADEMIC -> R.string.feature_category_academic
            LIBRARY -> R.string.feature_category_library
            LIFE -> R.string.feature_category_life
            LANGUAGE -> R.string.feature_category_language
            SYSTEM -> R.string.feature_category_system
        }
}
