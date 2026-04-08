package org.ntust.app.tigerduck.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Date

@Entity(
    tableName = "announcements",
    indices = [Index("publishDate")]
)
data class Announcement(
    @PrimaryKey val announcementId: String,
    val title: String,
    val summary: String,
    val department: String,
    val publishDate: Date,
    val detailUrl: String? = null,
    val htmlContent: String? = null
)
