package org.ntust.app.tigerduck.data.model

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
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

    @Suppress("DEPRECATION")
    val icon: ImageVector
        get() = when (this) {
            HOME -> Icons.Filled.Home
            CLASS_TABLE -> Icons.Filled.CalendarViewDay
            CALENDAR -> Icons.Filled.CalendarMonth
            ANNOUNCEMENTS -> Icons.Filled.Campaign
            LIBRARY -> Icons.Filled.MenuBook
            SCORE -> Icons.Filled.BarChart
            COURSE_SELECTION -> Icons.Filled.EditNote
            GRADUATION_REQUIREMENTS -> Icons.Filled.School
            DISCUSSION_ROOM -> Icons.Filled.MeetingRoom
            LIBRARY_LECTURE -> Icons.Filled.Mic
            FREE_LUNCH -> Icons.Filled.LunchDining
            CLUBS -> Icons.Filled.Groups
            EMPTY_CLASSROOM -> Icons.Filled.Business
            SCHOLARSHIP -> Icons.Filled.Payments
            ENGLISH_VOCAB -> Icons.Filled.Translate
            MORE -> Icons.Filled.MoreHoriz
            SETTINGS -> Icons.Filled.Settings
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
            HOME, CLASS_TABLE, CALENDAR, ANNOUNCEMENTS, LIBRARY,
            SCORE, COURSE_SELECTION, GRADUATION_REQUIREMENTS,
            DISCUSSION_ROOM, LIBRARY_LECTURE,
            FREE_LUNCH, CLUBS, EMPTY_CLASSROOM, SCHOLARSHIP,
            ENGLISH_VOCAB
        )

        val moreFeatures = listOf(
            CLASS_TABLE, CALENDAR,
            SCORE, COURSE_SELECTION, GRADUATION_REQUIREMENTS,
            LIBRARY, DISCUSSION_ROOM, LIBRARY_LECTURE,
            ANNOUNCEMENTS, FREE_LUNCH, CLUBS, EMPTY_CLASSROOM, SCHOLARSHIP,
            ENGLISH_VOCAB
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
