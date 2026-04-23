package org.ntust.app.tigerduck.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.widget.receivers.NextClassDarkWidget
import org.ntust.app.tigerduck.widget.receivers.NextClassDarkWidgetReceiver
import org.ntust.app.tigerduck.widget.receivers.NextClassLightWidget
import org.ntust.app.tigerduck.widget.receivers.NextClassLightWidgetReceiver
import org.ntust.app.tigerduck.widget.receivers.TodayDarkWidget
import org.ntust.app.tigerduck.widget.receivers.TodayDarkWidgetReceiver
import org.ntust.app.tigerduck.widget.receivers.TodayLightWidget
import org.ntust.app.tigerduck.widget.receivers.TodayLightWidgetReceiver
import org.ntust.app.tigerduck.widget.receivers.WeekDarkWidget
import org.ntust.app.tigerduck.widget.receivers.WeekDarkWidgetReceiver
import org.ntust.app.tigerduck.widget.receivers.WeekLightWidget
import org.ntust.app.tigerduck.widget.receivers.WeekLightWidgetReceiver
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataCache: DataCache,
    private val boundaryScheduler: WidgetBoundaryScheduler,
) {
    // Singleton-scoped supervisor so fire-and-forget refreshes survive when
    // the caller's viewModelScope is cancelled (e.g. user adds a course and
    // navigates away before Glance has finished re-rendering). Default
    // dispatcher keeps the widget IPC off the main thread.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun updateAll() {
        // Bump the per-widget tick BEFORE asking Glance to recompose. The
        // composable reads this tick via currentState(), so observing a new
        // value forces its LaunchedEffect to re-run and reload from disk.
        // Without this, Glance's recomposition reuses the stale snapshot
        // captured when the widget's session was first established.
        val now = System.currentTimeMillis()
        bumpTickForEveryPlacedWidget(now)
        try {
            WeekLightWidget().updateAll(context)
            WeekDarkWidget().updateAll(context)
            TodayLightWidget().updateAll(context)
            TodayDarkWidget().updateAll(context)
            NextClassLightWidget().updateAll(context)
            NextClassDarkWidget().updateAll(context)
        } catch (_: Exception) {
            // Fall through to the manual broadcast below.
        }
        // Belt-and-suspenders: poke each provider via the system's
        // ACTION_APPWIDGET_UPDATE broadcast too.
        broadcastAppWidgetUpdate()
        boundaryScheduler.scheduleForToday(dataCache.loadCourses())
    }

    private suspend fun bumpTickForEveryPlacedWidget(tick: Long) {
        val manager = GlanceAppWidgetManager(context)
        GLANCE_WIDGET_FACTORIES.forEach { factory ->
            val widget = factory()
            val ids: List<GlanceId> = runCatching { manager.getGlanceIds(widget.javaClass) }
                .getOrDefault(emptyList())
            ids.forEach { id ->
                runCatching {
                    updateAppWidgetState(context, id) { prefs ->
                        prefs[WidgetState.TickKey] = tick
                    }
                }
            }
        }
    }

    /**
     * Fire-and-forget variant. Use from UI event handlers where you don't
     * want to block on widget rendering and don't want a short-lived scope
     * to cancel the refresh before it reaches the system.
     */
    fun requestUpdate() {
        scope.launch { updateAll() }
    }

    private fun broadcastAppWidgetUpdate() {
        val manager = AppWidgetManager.getInstance(context)
        RECEIVER_CLASSES.forEach { clazz ->
            val component = ComponentName(context, clazz)
            val ids = runCatching { manager.getAppWidgetIds(component) }.getOrNull()
                ?: return@forEach
            if (ids.isEmpty()) return@forEach
            val intent = Intent(context, clazz).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }
    }

    companion object {
        private val RECEIVER_CLASSES = listOf(
            WeekLightWidgetReceiver::class.java,
            WeekDarkWidgetReceiver::class.java,
            TodayLightWidgetReceiver::class.java,
            TodayDarkWidgetReceiver::class.java,
            NextClassLightWidgetReceiver::class.java,
            NextClassDarkWidgetReceiver::class.java,
        )
        private val GLANCE_WIDGET_FACTORIES: List<() -> GlanceAppWidget> = listOf(
            { WeekLightWidget() },
            { WeekDarkWidget() },
            { TodayLightWidget() },
            { TodayDarkWidget() },
            { NextClassLightWidget() },
            { NextClassDarkWidget() },
        )
    }
}
