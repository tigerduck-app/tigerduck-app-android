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

    companion object {
        val defaultTabs = listOf(HOME, CLASS_TABLE, CALENDAR)

        val pinnableFeatures = listOf(
            HOME, CLASS_TABLE, CALENDAR, ANNOUNCEMENTS, LIBRARY, SCORE,
            // TODO: re-enable once these pages are implemented
            // COURSE_SELECTION, GRADUATION_REQUIREMENTS,
            // DISCUSSION_ROOM, LIBRARY_LECTURE,
            // FREE_LUNCH, CLUBS, EMPTY_CLASSROOM, SCHOLARSHIP,
            // ENGLISH_VOCAB,
        )

        val moreFeatures = listOf(
            CLASS_TABLE, CALENDAR, SCORE,
            LIBRARY, ANNOUNCEMENTS,
            // TODO: re-enable once these pages are implemented
            // COURSE_SELECTION, GRADUATION_REQUIREMENTS,
            // DISCUSSION_ROOM, LIBRARY_LECTURE,
            // FREE_LUNCH, CLUBS, EMPTY_CLASSROOM, SCHOLARSHIP,
            // ENGLISH_VOCAB,
        )

        // TODO: remove once every entry here has a real screen. Used to scrub
        // obsolete entries from persisted user tab configs on app open.
        val unfinishedFeatures = setOf(
            COURSE_SELECTION, GRADUATION_REQUIREMENTS,
            DISCUSSION_ROOM, LIBRARY_LECTURE,
            FREE_LUNCH, CLUBS, EMPTY_CLASSROOM, SCHOLARSHIP,
            ENGLISH_VOCAB,
        )

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
