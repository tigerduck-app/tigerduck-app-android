package org.ntust.app.tigerduck.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import org.ntust.app.tigerduck.data.model.*

@Database(
    entities = [Course::class, Assignment::class, CalendarEvent::class, Announcement::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun courseDao(): CourseDao
    abstract fun assignmentDao(): AssignmentDao
    abstract fun calendarEventDao(): CalendarEventDao
    abstract fun announcementDao(): AnnouncementDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "tigerduck.db")
                .fallbackToDestructiveMigration(true)
                .build()
    }
}
