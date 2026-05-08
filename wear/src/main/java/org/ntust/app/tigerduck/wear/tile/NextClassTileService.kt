package org.ntust.app.tigerduck.wear.tile

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.TypeBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.concurrent.futures.ResolvableFuture
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.ntust.app.tigerduck.shared.NextClassResolver
import org.ntust.app.tigerduck.shared.NextClassResult
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.wear.MainActivity
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
            snapshot.syncedAtMs == null -> Triple("Open TigerDuck", "", "")
            snapshot.courses.isEmpty() -> Triple("No courses", "", "")
            else -> when (val r = NextClassResolver.resolve(snapshot.courses, weekday, minuteOfDay)) {
                is NextClassResult.Ongoing -> Triple(
                    "NOW · ends ${formatHm(r.endMinute)}",
                    "${r.course.courseName}\n${r.course.classroom} · ${r.course.instructor}",
                    r.nextToday?.let { "Next: ${it.course.courseName} · ${formatHm(it.startMinute)}" } ?: "",
                )
                is NextClassResult.NextToday -> Triple(
                    "NEXT · ${formatHm(r.startMinute)}",
                    "${r.course.courseName}\n${r.course.classroom} · ${r.course.instructor}",
                    "",
                )
                is NextClassResult.NextFuture -> Triple(
                    if (r.daysAhead == 1) "TOMORROW · ${formatHm(r.startMinute)}"
                    else "${formatHm(r.startMinute)} · in ${r.daysAhead} d",
                    "${r.course.courseName}\n${r.course.classroom} · ${r.course.instructor}",
                    "",
                )
                NextClassResult.Empty -> Triple("No upcoming classes", "", "")
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

    companion object {
        private const val RESOURCES_VERSION = "1"

        fun requestUpdate(context: Context) {
            getUpdater(context).requestUpdate(NextClassTileService::class.java)
        }
    }
}
