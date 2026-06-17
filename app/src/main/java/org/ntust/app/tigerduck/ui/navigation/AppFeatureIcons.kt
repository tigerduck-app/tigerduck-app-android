package org.ntust.app.tigerduck.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CalendarViewDay
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LunchDining
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.ui.graphics.vector.ImageVector
import org.ntust.app.tigerduck.data.model.AppFeature

@Suppress("DEPRECATION")
val AppFeature.icon: ImageVector
    get() = when (this) {
        AppFeature.HOME -> Icons.Filled.Home
        AppFeature.CLASS_TABLE -> Icons.Filled.CalendarViewDay
        AppFeature.CALENDAR -> Icons.Filled.CalendarMonth
        AppFeature.ANNOUNCEMENTS -> Icons.Filled.Campaign
        AppFeature.LIBRARY -> Icons.Filled.MenuBook
        AppFeature.SCORE -> Icons.Filled.BarChart
        AppFeature.COURSE_SELECTION -> Icons.Filled.EditNote
        AppFeature.GRADUATION_REQUIREMENTS -> Icons.Filled.School
        AppFeature.DISCUSSION_ROOM -> Icons.Filled.MeetingRoom
        AppFeature.LIBRARY_LECTURE -> Icons.Filled.Mic
        AppFeature.FREE_LUNCH -> Icons.Filled.LunchDining
        AppFeature.CLUBS -> Icons.Filled.Groups
        AppFeature.EMPTY_CLASSROOM -> Icons.Filled.Business
        AppFeature.SCHOLARSHIP -> Icons.Filled.Payments
        AppFeature.ENGLISH_VOCAB -> Icons.Filled.Translate
        AppFeature.MORE -> Icons.Filled.MoreHoriz
        AppFeature.SETTINGS -> Icons.Filled.Settings
    }
