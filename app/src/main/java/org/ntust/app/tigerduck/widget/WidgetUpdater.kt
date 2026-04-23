package org.ntust.app.tigerduck.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.widget.receivers.NextClassDarkWidget
import org.ntust.app.tigerduck.widget.receivers.NextClassLightWidget
import org.ntust.app.tigerduck.widget.receivers.TodayDarkWidget
import org.ntust.app.tigerduck.widget.receivers.TodayLightWidget
import org.ntust.app.tigerduck.widget.receivers.WeekDarkWidget
import org.ntust.app.tigerduck.widget.receivers.WeekLightWidget
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataCache: DataCache,
    private val boundaryScheduler: WidgetBoundaryScheduler,
) {
    suspend fun updateAll() {
        WeekLightWidget().updateAll(context)
        WeekDarkWidget().updateAll(context)
        TodayLightWidget().updateAll(context)
        TodayDarkWidget().updateAll(context)
        NextClassLightWidget().updateAll(context)
        NextClassDarkWidget().updateAll(context)
        boundaryScheduler.scheduleForToday(dataCache.loadCourses())
    }
}
