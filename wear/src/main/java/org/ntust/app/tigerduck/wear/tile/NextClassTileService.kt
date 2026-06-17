package org.ntust.app.tigerduck.wear.tile

import android.content.Context
import androidx.concurrent.futures.ResolvableFuture
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.TypeBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.shared.NextClassResolver
import org.ntust.app.tigerduck.shared.NextClassResult
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.wear.MainActivity
import org.ntust.app.tigerduck.wear.R
import org.ntust.app.tigerduck.wear.data.SchedulePersistenceHolder
import org.ntust.app.tigerduck.wear.data.WatchSnapshot
import java.time.ZoneId

class NextClassTileService : TileService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onTileRequest(
        request: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> {
        val future = ResolvableFuture.create<TileBuilders.Tile>()
        serviceScope.launch {
            try {
                val snapshot = SchedulePersistenceHolder.get(this@NextClassTileService).flow.first()
                future.set(buildTile(snapshot))
            } catch (e: Exception) {
                future.setException(e)
            }
        }
        return future
    }

    override fun onTileResourcesRequest(
        request: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> {
        val resources = ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build()
        return ResolvableFuture.create<ResourceBuilders.Resources>().also { it.set(resources) }
    }

    private fun buildTile(snapshot: WatchSnapshot): TileBuilders.Tile {
        val now = AppClock.localDateTime()
        val weekday = now.dayOfWeek.value
        val minuteOfDay = now.hour * 60 + now.minute

        val timeline = TimelineBuilders.Timeline.Builder()

        timeline.addTimelineEntry(
            TimelineBuilders.TimelineEntry.Builder()
                .setLayout(layoutFor(snapshot, weekday, minuteOfDay))
                .build()
        )

        if (snapshot.syncedAtMs != null && snapshot.courses.isNotEmpty()) {
            addFutureEntries(timeline, snapshot, weekday, minuteOfDay, now)
        }

        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(timeline.build())
            .setFreshnessIntervalMillis(FALLBACK_FRESHNESS_MS)
            .build()
    }

    /**
     * Pre-compute timeline entries for each future class-state transition today
     * so the platform swaps layouts at class boundaries without waking the service.
     */
    private fun addFutureEntries(
        timeline: TimelineBuilders.Timeline.Builder,
        snapshot: WatchSnapshot,
        weekday: Int,
        minuteOfDay: Int,
        now: java.time.LocalDateTime,
    ) {
        val todayBaseMs = now.toLocalDate()
            .atStartOfDay(ZoneId.of("Asia/Taipei"))
            .toInstant().toEpochMilli()

        val blocks = NextClassResolver.todaysClasses(snapshot.courses, weekday, minuteOfDay)
            .filter { it.endMinute > minuteOfDay }

        val transitions = mutableListOf<Int>()
        for (block in blocks) {
            if (block.startMinute > minuteOfDay) transitions += block.startMinute
            transitions += block.endMinute + 1
        }

        for (i in transitions.indices) {
            val from = transitions[i]
            val to = transitions.getOrElse(i + 1) { END_OF_DAY_MINUTE }

            timeline.addTimelineEntry(
                TimelineBuilders.TimelineEntry.Builder()
                    .setValidity(
                        TimelineBuilders.TimeInterval.Builder()
                            .setStartMillis(todayBaseMs + from.toLong() * 60_000L)
                            .setEndMillis(todayBaseMs + to.toLong() * 60_000L)
                            .build()
                    )
                    .setLayout(layoutFor(snapshot, weekday, from))
                    .build()
            )
        }
    }

    private fun layoutFor(
        snapshot: WatchSnapshot,
        weekday: Int,
        minuteOfDay: Int,
    ): LayoutElementBuilders.Layout {
        val (label, body, sub) = when {
            snapshot.syncedAtMs == null -> Triple(
                getString(R.string.watch_open_phone_to_sync), "", ""
            )

            snapshot.courses.isEmpty() -> Triple(
                getString(R.string.watch_no_courses_synced), "", ""
            )

            else -> when (val r =
                NextClassResolver.resolve(snapshot.courses, weekday, minuteOfDay)) {
                is NextClassResult.Ongoing -> Triple(
                    getString(R.string.watch_now_ends_at, formatHm(r.endMinute)),
                    "${r.course.displayName}\n${r.course.classroom(r.weekday)} · ${r.course.instructor}",
                    r.nextToday?.let {
                        getString(
                            R.string.watch_next_label,
                            it.course.displayName,
                            formatHm(it.startMinute)
                        )
                    } ?: "",
                )

                is NextClassResult.NextToday -> Triple(
                    getString(R.string.watch_starts_at, formatHm(r.startMinute)),
                    "${r.course.displayName}\n${r.course.classroom(r.weekday)} · ${r.course.instructor}",
                    "",
                )

                is NextClassResult.NextFuture -> Triple(
                    if (r.daysAhead == 1) {
                        getString(R.string.watch_tomorrow_at, formatHm(r.startMinute))
                    } else {
                        val targetWeekday = ((weekday - 1 + r.daysAhead) % 7) + 1
                        getString(
                            R.string.watch_weekday_at,
                            weekdayShortName(targetWeekday),
                            formatHm(r.startMinute),
                        )
                    },
                    "${r.course.displayName}\n${r.course.classroom(r.weekday)} · ${r.course.instructor}",
                    "",
                )

                NextClassResult.Empty -> Triple(
                    getString(R.string.watch_no_upcoming_classes), "", ""
                )
            }
        }

        val column = LayoutElementBuilders.Column.Builder()
            .addContent(textLine(label))
            .addContent(textLine(body))
            .apply { if (sub.isNotEmpty()) addContent(textLine(sub)) }
            .build()

        val launchClick = ModifiersBuilders.Clickable.Builder()
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setClassName(MainActivity::class.java.name)
                            .setPackageName(packageName)
                            .build()
                    )
                    .build()
            )
            .build()

        val root = LayoutElementBuilders.Box.Builder()
            .addContent(column)
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(launchClick).build())
            .build()

        return LayoutElementBuilders.Layout.Builder().setRoot(root).build()
    }

    private fun textLine(text: String): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Text.Builder()
            .setText(TypeBuilders.StringProp.Builder(text).build())
            .build()

    private fun formatHm(minuteOfDay: Int): String =
        "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

    private fun weekdayShortName(weekday: Int): String = getString(
        when (weekday) {
            1 -> R.string.weekday_mon_short
            2 -> R.string.weekday_tue_short
            3 -> R.string.weekday_wed_short
            4 -> R.string.weekday_thu_short
            5 -> R.string.weekday_fri_short
            6 -> R.string.weekday_sat_short
            else -> R.string.weekday_sun_short
        }
    )

    companion object {
        private const val RESOURCES_VERSION = "1"
        private const val END_OF_DAY_MINUTE = 24 * 60
        private const val FALLBACK_FRESHNESS_MS = 3_600_000L

        fun requestUpdate(context: Context) {
            getUpdater(context).requestUpdate(NextClassTileService::class.java)
        }
    }
}
