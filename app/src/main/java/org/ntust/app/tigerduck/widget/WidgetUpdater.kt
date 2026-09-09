package org.ntust.app.tigerduck.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.shared.clock.AppClock
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
import org.ntust.app.tigerduck.analytics.AnalyticsLogger
import org.ntust.app.tigerduck.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WidgetUpdater @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dataCache: DataCache,
    private val boundaryScheduler: WidgetBoundaryScheduler,
    private val analyticsLogger: AnalyticsLogger,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val boundaryLock = Mutex()
    private var boundaryInFlight = false
    private val boundaryFinishers = mutableListOf<() -> Unit>()
    private var hasLoggedWidgetAnalytics = false

    suspend fun updateAll() {
        // Bump the per-widget tick BEFORE asking Glance to recompose. The
        // composable reads this tick via currentState(), so observing a new
        // value forces its LaunchedEffect to re-run and reload from disk.
        // Without this, Glance's recomposition reuses the stale snapshot
        // captured when the widget's session was first established.
        val now = AppClock.nowMillis()
        val manager = GlanceAppWidgetManager(context)
        coroutineScope {
            GLANCE_WIDGET_FACTORIES.forEach { factory ->
                launch {
                    val widget = factory()
                    // Single getGlanceIds round-trip per factory: the prior
                    // shape called it once for the tick bump and again inside
                    // widget.updateAll(). Explicit per-id update reuses the
                    // ids we already have.
                    val ids: List<GlanceId> =
                        runCatching { manager.getGlanceIds(widget.javaClass) }
                            .getOrDefault(emptyList())
                    if (ids.isEmpty()) return@launch
                    ids.forEach { id ->
                        runCatching {
                            updateAppWidgetState(context, id) { prefs ->
                                prefs[WidgetState.TickKey] = now
                            }
                            widget.update(context, id)
                        }
                    }
                }
            }
        }
        if (!hasLoggedWidgetAnalytics) {
            hasLoggedWidgetAnalytics = true
            val awm = AppWidgetManager.getInstance(context)
            WIDGET_ANALYTICS.forEach { (clazz, label) ->
                val count = runCatching {
                    awm.getAppWidgetIds(ComponentName(context, clazz))?.size ?: 0
                }.getOrDefault(0)
                if (count > 0) {
                    analyticsLogger.log("widget_active", mapOf("widget_type" to label, "count" to count))
                }
            }
        }
        // Belt-and-suspenders: poke each provider via the system's
        // ACTION_APPWIDGET_UPDATE broadcast too.
        broadcastAppWidgetUpdate()
        boundaryScheduler.scheduleForToday(dataCache.loadCourses())
    }

    /**
     * Fire-and-forget variant. Use from UI event handlers where you don't
     * want to block on widget rendering and don't want a short-lived scope
     * to cancel the refresh before it reaches the system.
     */
    fun requestUpdate() {
        scope.launch { updateAll() }
    }

    /**
     * Coalesces repeated boundary-triggered refreshes into one in-flight run.
     * Every receiver callback gets its finish block invoked when that run ends.
     */
    fun requestBoundaryUpdate(onFinished: () -> Unit) {
        scope.launch {
            val shouldStart = boundaryLock.withLock {
                boundaryFinishers += onFinished
                if (boundaryInFlight) {
                    false
                } else {
                    boundaryInFlight = true
                    true
                }
            }
            if (!shouldStart) return@launch

            try {
                // 8s leaves a margin under the ~10s goAsync budget so we don't
                // hit ANR when a single Glance IPC stalls right at the edge.
                withTimeout(8_000) { updateAll() }
            } catch (e: Exception) {
                Log.w("WidgetUpdater", "Boundary widget update failed", e)
            } finally {
                val finishers = boundaryLock.withLock {
                    boundaryInFlight = false
                    boundaryFinishers.toList().also { boundaryFinishers.clear() }
                }
                finishers.forEach { finisher ->
                    runCatching { finisher() }
                }
            }
        }
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
            LibraryShortcutWidgetReceiver::class.java,
        )
        private val WIDGET_ANALYTICS = listOf(
            WeekLightWidgetReceiver::class.java to "week_light",
            WeekDarkWidgetReceiver::class.java to "week_dark",
            TodayLightWidgetReceiver::class.java to "today_light",
            TodayDarkWidgetReceiver::class.java to "today_dark",
            NextClassLightWidgetReceiver::class.java to "next_class_light",
            NextClassDarkWidgetReceiver::class.java to "next_class_dark",
            LibraryShortcutWidgetReceiver::class.java to "library_shortcut",
        )
        private val GLANCE_WIDGET_FACTORIES: List<() -> GlanceAppWidget> = listOf(
            { WeekLightWidget() },
            { WeekDarkWidget() },
            { TodayLightWidget() },
            { TodayDarkWidget() },
            { NextClassLightWidget() },
            { NextClassDarkWidget() },
            { LibraryShortcutWidget() },
        )
    }
}
