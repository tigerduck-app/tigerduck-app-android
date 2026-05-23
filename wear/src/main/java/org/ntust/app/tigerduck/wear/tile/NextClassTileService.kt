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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.ntust.app.tigerduck.shared.NextClassResolver
import org.ntust.app.tigerduck.shared.NextClassResult
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.wear.MainActivity
import org.ntust.app.tigerduck.wear.R
import org.ntust.app.tigerduck.wear.data.ScheduleRepository
import org.ntust.app.tigerduck.wear.data.WatchSnapshot

class NextClassTileService : TileService() {

    override fun onTileRequest(
        request: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> {
        val tile = runBlocking {
            val snapshot = ScheduleRepository.get(this@NextClassTileService).flow.first()
            buildTile(snapshot)
        }
        return ResolvableFuture.create<TileBuilders.Tile>().also { it.set(tile) }
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

        val (label, body, sub) = when {
            snapshot.syncedAtMs == null -> Triple(
                getString(R.string.watch_open_phone_to_sync),
                "",
                ""
            )

            snapshot.courses.isEmpty() -> Triple(
                getString(R.string.watch_no_courses_synced),
                "",
                ""
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
                    getString(R.string.watch_no_upcoming_classes),
                    "",
                    ""
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

        val root: LayoutElementBuilders.LayoutElement = LayoutElementBuilders.Box.Builder()
            .addContent(column)
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(launchClick).build())
            .build()

        val timeline = TimelineBuilders.Timeline.Builder()
            .addTimelineEntry(
                TimelineBuilders.TimelineEntry.Builder()
                    .setLayout(LayoutElementBuilders.Layout.Builder().setRoot(root).build())
                    .build()
            )
            .build()

        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(timeline)
            .setFreshnessIntervalMillis(60_000)
            .build()
    }

    private fun textLine(text: String): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Text.Builder()
            .setText(TypeBuilders.StringProp.Builder(text).build())
            .build()

    private fun formatHm(minuteOfDay: Int): String =
        "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

    private fun weekdayShortName(weekday: Int): String = getString(
        when (weekday) {
            1 -> R.string.watch_weekday_mon_short
            2 -> R.string.watch_weekday_tue_short
            3 -> R.string.watch_weekday_wed_short
            4 -> R.string.watch_weekday_thu_short
            5 -> R.string.watch_weekday_fri_short
            6 -> R.string.watch_weekday_sat_short
            else -> R.string.watch_weekday_sun_short
        }
    )

    companion object {
        private const val RESOURCES_VERSION = "1"

        fun requestUpdate(context: Context) {
            getUpdater(context).requestUpdate(NextClassTileService::class.java)
        }
    }
}
