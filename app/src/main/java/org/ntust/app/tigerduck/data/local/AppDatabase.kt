package org.ntust.app.tigerduck.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.data.model.*

@Database(
    entities = [Course::class, Assignment::class, CalendarEvent::class, Announcement::class],
    version = 2,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun courseDao(): CourseDao
    abstract fun assignmentDao(): AssignmentDao
    abstract fun calendarEventDao(): CalendarEventDao
    abstract fun announcementDao(): AnnouncementDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_assignments_dueDate` ON `assignments` (`dueDate`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_assignments_courseNo` ON `assignments` (`courseNo`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_announcements_publishDate` ON `announcements` (`publishDate`)")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "tigerduck.db")
                .apply {
                    addMigrations(MIGRATION_1_2)
                    if (BuildConfig.DEBUG) {
                        fallbackToDestructiveMigration(true)
                    }
                }
                .build()
    }
}
