package org.ntust.app.tigerduck.data.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

enum class AppFeature(val id: String) {
    HOME("home"),
    CLASS_TABLE("classTable"),
    CALENDAR("calendar"),
    ANNOUNCEMENTS("announcements"),
    GPA("gpa"),
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

    val displayName: String
        get() = when (this) {
            HOME -> "首頁"
            CLASS_TABLE -> "課表"
            CALENDAR -> "行事曆"
            ANNOUNCEMENTS -> "公告"
            LIBRARY -> "圖書館"
            GPA -> "GPA 查詢"
            COURSE_SELECTION -> "選課系統"
            GRADUATION_REQUIREMENTS -> "畢業門檻"
            DISCUSSION_ROOM -> "討論小間"
            LIBRARY_LECTURE -> "圖書館講座"
            FREE_LUNCH -> "免費便當"
            CLUBS -> "社團活動"
            EMPTY_CLASSROOM -> "空教室"
            SCHOLARSHIP -> "獎學金"
            ENGLISH_VOCAB -> "英文單字測驗"
            MORE -> "更多"
            SETTINGS -> "設定"
        }

    @Suppress("DEPRECATION")
    val icon: ImageVector
        get() = when (this) {
            HOME -> Icons.Filled.Home
            CLASS_TABLE -> Icons.Filled.CalendarViewDay
            CALENDAR -> Icons.Filled.CalendarMonth
            ANNOUNCEMENTS -> Icons.Filled.Campaign
            LIBRARY -> Icons.Filled.MenuBook
            GPA -> Icons.Filled.BarChart
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
            GPA, COURSE_SELECTION, GRADUATION_REQUIREMENTS -> FeatureCategory.ACADEMIC
            LIBRARY, DISCUSSION_ROOM, LIBRARY_LECTURE -> FeatureCategory.LIBRARY
            FREE_LUNCH, CLUBS, EMPTY_CLASSROOM, SCHOLARSHIP -> FeatureCategory.LIFE
            ENGLISH_VOCAB -> FeatureCategory.LANGUAGE
            SETTINGS -> FeatureCategory.SYSTEM
            else -> null
        }

    companion object {
        val defaultTabs = listOf(HOME, CLASS_TABLE, CALENDAR, LIBRARY)

        val pinnableFeatures = listOf(
            HOME, CLASS_TABLE, CALENDAR, ANNOUNCEMENTS, LIBRARY,
            GPA, COURSE_SELECTION, GRADUATION_REQUIREMENTS,
            DISCUSSION_ROOM, LIBRARY_LECTURE,
            FREE_LUNCH, CLUBS, EMPTY_CLASSROOM, SCHOLARSHIP,
            ENGLISH_VOCAB
        )

        val moreFeatures = listOf(
            ANNOUNCEMENTS,
            GPA, COURSE_SELECTION, GRADUATION_REQUIREMENTS,
            DISCUSSION_ROOM, LIBRARY_LECTURE,
            FREE_LUNCH, CLUBS, EMPTY_CLASSROOM, SCHOLARSHIP,
            ENGLISH_VOCAB
        )

        fun fromId(id: String): AppFeature? = entries.firstOrNull { it.id == id }
    }
}

enum class FeatureCategory {
    ACADEMIC, LIBRARY, LIFE, LANGUAGE, SYSTEM;

    val displayName: String
        get() = when (this) {
            ACADEMIC -> "學業"
            LIBRARY -> "圖書館"
            LIFE -> "生活"
            LANGUAGE -> "語言"
            SYSTEM -> "系統"
        }
}
