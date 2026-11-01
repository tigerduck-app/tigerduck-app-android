package org.ntust.app.tigerduck.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.state.updateAppWidgetState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.ntust.app.tigerduck.coursechanges.CourseChangeStore
import org.ntust.app.tigerduck.shared.timeline.CourseChanges
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.UiLanguageMonitor
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.util.logSummary
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

/**
 * Redraws every placed widget, on demand and on a change of the app's
 * language or of the school's course changes ([courseChangesUpdated], empty
 * in a test that does not give one), then arms the next class boundary.
 */
@Singleton
class WidgetUpdater internal constructor(
    private val context: Context,
    private val dataCache: DataCache,
    private val boundaryScheduler: WidgetBoundaryScheduler,
    private val analyticsLogger: AnalyticsLogger,
    private val scope: CoroutineScope,
    uiLanguage: UiLanguageMonitor,
    courseChangesUpdated: Flow<CourseChanges> = emptyFlow(),
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        dataCache: DataCache,
        boundaryScheduler: WidgetBoundaryScheduler,
        analyticsLogger: AnalyticsLogger,
        @ApplicationScope scope: CoroutineScope,
        uiLanguage: UiLanguageMonitor,
        courseChangeStore: CourseChangeStore,
    ) : this(
        context, dataCache, boundaryScheduler, analyticsLogger, scope, uiLanguage,
        // Cold, so the store reads its copy from disk once this is collected
        // on the app's scope, not as the updater is injected at launch.
        flow { emitAll(courseChangeStore.changes) },
    )

    private val boundaryLock = Mutex()
    private var boundaryInFlight = false
    private val boundaryFinishers = mutableListOf<() -> Unit>()
    private var hasLoggedWidgetAnalytics = false

    init {
        // A widget keeps the language it was last drawn in until something
        // redraws it, which without this is the next period boundary, data
        // change or half-hourly update. Conflated: only the last of a burst
        // matters. TigerDuckApp injects this, so it listens from launch.
        scope.launch {
            uiLanguage.changes.conflate().collect { updateAll() }
        }
        // And a new course changes feed: a class it cancels, moves or puts in
        // another room changes every widget and the day's boundaries. The
        // first value is the copy the widgets already read.
        scope.launch {
            courseChangesUpdated.drop(1).conflate().collect { updateAll() }
        }
    }

    suspend fun updateAll() {
        // Bump the per-widget tick BEFORE asking Glance to recompose. The
        // composable reads this tick via currentState(), so observing a new
        // value forces its LaunchedEffect to re-run and reload from disk.
        // Without this, Glance's recomposition reuses the stale snapshot
        // captured when the widget's session was first established.
        val now = AppClock.nowMillis()
        val manager = GlanceAppWidgetManager(context)
        val awm = AppWidgetManager.getInstance(context)
        coroutineScope {
            WIDGETS.forEach { widgetKind ->
                launch {
                    val widget = widgetKind.factory()
                    // Ids come from AppWidgetManager keyed on the *receiver*
                    // component, never GlanceAppWidgetManager.getGlanceIds().
                    // That call answers "which widgets is this provider on?"
                    // out of a datastore map keyed by the GlanceAppWidget
                    // subclass's canonical name, written by whichever build
                    // last ran the receiver and never pruned — so it is the
                    // one thing here that can hand back another provider's
                    // appWidgetIds and render a layout into a widget it does
                    // not belong to. The pairing is static and known at
                    // compile time; there is no reason to look it up at all.
                    // getGlanceIdBy() only validates and wraps the int, so it
                    // reads nothing persisted.
                    val component = ComponentName(context, widgetKind.receiver)
                    val appWidgetIds: IntArray =
                        runCatching { awm.getAppWidgetIds(component) }.getOrNull() ?: IntArray(0)
                    // Per id, not around the whole map: getGlanceIdBy throws on
                    // an id the host has since dropped, and one widget removed
                    // mid-refresh must not cost the others their update.
                    val ids: List<GlanceId> = appWidgetIds.toList().mapNotNull {
                        runCatching { manager.getGlanceIdBy(it) }.getOrNull()
                    }
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
            WIDGETS.forEach { widgetKind ->
                val count = runCatching {
                    awm.getAppWidgetIds(ComponentName(context, widgetKind.receiver))?.size ?: 0
                }.getOrDefault(0)
                if (count > 0) {
                    analyticsLogger.log(
                        "widget_active",
                        mapOf("widget_type" to widgetKind.analyticsLabel, "count" to count),
                    )
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
                Log.w("WidgetUpdater", "Boundary widget update failed: ${e.logSummary()}")
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
        WIDGETS.forEach { widgetKind ->
            val clazz = widgetKind.receiver
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

    /**
     * One placed widget kind: the manifest receiver that owns its
     * appWidgetIds, the provider that renders them, and its analytics label.
     *
     * Deliberately one table rather than three parallel lists. The pairing of
     * receiver to provider is what keeps a layout rendering into its own
     * widget, so it must not be able to drift by someone appending to one
     * list and not another.
     */
    private class WidgetKind(
        val receiver: Class<out GlanceAppWidgetReceiver>,
        val analyticsLabel: String,
        val factory: () -> GlanceAppWidget,
    )

    companion object {
        // The *_dark kinds are hidden from the widget picker but still
        // refreshed: widgets placed from them before keep showing.
        private val WIDGETS = listOf(
            WidgetKind(WeekLightWidgetReceiver::class.java, "week_light") { WeekLightWidget() },
            WidgetKind(WeekDarkWidgetReceiver::class.java, "week_dark") { WeekDarkWidget() },
            WidgetKind(TodayLightWidgetReceiver::class.java, "today_light") { TodayLightWidget() },
            WidgetKind(TodayDarkWidgetReceiver::class.java, "today_dark") { TodayDarkWidget() },
            WidgetKind(NextClassLightWidgetReceiver::class.java, "next_class_light") {
                NextClassLightWidget()
            },
            WidgetKind(NextClassDarkWidgetReceiver::class.java, "next_class_dark") {
                NextClassDarkWidget()
            },
            WidgetKind(LibraryShortcutWidgetReceiver::class.java, "library_shortcut") {
                LibraryShortcutWidget()
            },
        )
    }
}
