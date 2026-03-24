package com.tigerduck.app.di

import android.content.Context
import com.tigerduck.app.data.local.AppDatabase
import com.tigerduck.app.data.local.AnnouncementDao
import com.tigerduck.app.data.local.AssignmentDao
import com.tigerduck.app.data.local.CalendarEventDao
import com.tigerduck.app.data.local.CourseDao
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
    fun provideCourseDao(db: AppDatabase): CourseDao = db.courseDao()

    @Provides
    fun provideAssignmentDao(db: AppDatabase): AssignmentDao = db.assignmentDao()

    @Provides
    fun provideCalendarEventDao(db: AppDatabase): CalendarEventDao = db.calendarEventDao()

    @Provides
    fun provideAnnouncementDao(db: AppDatabase): AnnouncementDao = db.announcementDao()
}
