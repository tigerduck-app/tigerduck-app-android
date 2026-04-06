package org.ntust.app.tigerduck.di

import android.content.Context
import org.ntust.app.tigerduck.data.local.AppDatabase
import org.ntust.app.tigerduck.data.local.AnnouncementDao
import org.ntust.app.tigerduck.data.local.AssignmentDao
import org.ntust.app.tigerduck.data.local.CalendarEventDao
import org.ntust.app.tigerduck.data.local.CourseDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.create(context)

    @Provides
    @Singleton
    fun provideCourseDao(db: AppDatabase): CourseDao = db.courseDao()

    @Provides
    @Singleton
    fun provideAssignmentDao(db: AppDatabase): AssignmentDao = db.assignmentDao()

    @Provides
    @Singleton
    fun provideCalendarEventDao(db: AppDatabase): CalendarEventDao = db.calendarEventDao()

    @Provides
    @Singleton
    fun provideAnnouncementDao(db: AppDatabase): AnnouncementDao = db.announcementDao()
}
