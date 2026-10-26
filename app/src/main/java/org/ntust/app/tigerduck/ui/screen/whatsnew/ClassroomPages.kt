package org.ntust.app.tigerduck.ui.screen.whatsnew

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Stairs
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.data.model.AppFeature

/**
 * "Find a free classroom": the nearest free room between your classes, and
 * how far each way is: up or down the stairs, along the same floor, or over
 * to another building. The demo shows each in turn.
 */
internal fun emptyClassroomPage() = WhatsNewPage.Feature(
    id = "empty-classroom",
    visual = WhatsNewVisual.Custom { animate -> EmptyClassroomDemo(animate) },
    title = WhatsNewText(
        en = "Find a free classroom",
        zhHant = "找空教室更輕鬆",
    ),
    body = WhatsNewText(
        en = "Open Empty classroom from the bottom bar or from More. It suggests the nearest free room between your classes, and shows whether it's up the stairs, along the same floor or in another building.",
        zhHant = "從底部功能列或「更多」打開空教室，就會依你前後的課推薦最近的空教室，並標出要爬樓梯、在同一層走過去，還是要去其他棟。",
    ),
)

/** One way a suggestion can lie: the classes before and after it, and the legs between. */
private class Route(
    val caption: WhatsNewText,
    val before: String,
    val toRoom: Leg,
    val toNext: Leg,
    val after: String,
)

private sealed interface Leg {
    data class Stairs(val floors: Int) : Leg
    data class Walk(val minutes: Int) : Leg
    data object Along : Leg
}

private const val SUGGESTED_ROOM = "TR-412"

private val routes = listOf(
    Route(WhatsNewText(en = "Up or down the stairs", zhHant = "要爬樓梯"), "TR-212", Leg.Stairs(2), Leg.Stairs(-1), "TR-313"),
    Route(WhatsNewText(en = "Along the same floor", zhHant = "本樓平移"), "TR-410", Leg.Along, Leg.Along, "TR-415"),
    Route(WhatsNewText(en = "Over to another building", zhHant = "去其他棟"), "IB-201", Leg.Walk(3), Leg.Walk(4), "EE-105"),
)

private val classroomDemoDescription = WhatsNewText(
    en = "Demo: Empty classroom opens from More, and suggests a free room between two classes, reached up the stairs, along the same floor, or from another building.",
    zhHant = "示範：從「更多」打開空教室，推薦兩堂課之間的空教室，可能要爬樓梯、在同一層走過去，或從其他棟過去。",
)

private const val CLASSROOM_LOOP_MILLIS = 9_000

@Composable
private fun EmptyClassroomDemo(animate: Boolean) {
    // Each route holds a third of the feature's part.
    fun shown(t: () -> Float, index: Int) = { window(t(), index / 3f, (index + 1) / 3f, fade = 0.04f) }
    PathDemo(
        description = classroomDemoDescription.resolve(),
        animate = animate,
        featureMillis = CLASSROOM_LOOP_MILLIS,
        start = PathStart.MoreCard(AppFeature.EMPTY_CLASSROOM),
        stops = emptyList(),
        // Still: the stairs.
        featureStill = 0.15f,
        below = { t -> routes.forEachIndexed { index, route -> DemoCaption(route.caption.resolve(), alpha = shown(t, index)) } },
    ) { t -> EmptyClassroomScene(shown = { index -> shown(t, index) }) }
}

/** The suggestion and its route, each route shown by [shown]. */
@Composable
private fun EmptyClassroomScene(shown: (Int) -> () -> Float) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(colors.surface)) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MockText(stringResource(R.string.feature_empty_classroom), size = 14.sp, weight = FontWeight.Bold)
                // The periods, the chosen one filled.
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("4", "5", "6", "7", "8").forEachIndexed { index, period ->
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (index == 2) colors.primary else colors.surfaceContainerHigh)
                                .padding(vertical = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            MockText(period, size = 10.sp, weight = FontWeight.SemiBold, color = if (index == 2) colors.onPrimary else colors.onSurface)
                        }
                    }
                }
                // The suggestion: the room, how long it's free, and the way there and on.
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.surfaceContainerLow).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MockText(SUGGESTED_ROOM, Modifier.weight(1f), size = 20.sp, weight = FontWeight.Bold)
                        MockText("13:20–15:20", size = 10.sp, weight = FontWeight.SemiBold, color = FreeGreen)
                    }
                    Box {
                        routes.forEachIndexed { index, route ->
                            RouteLine(route, Modifier.graphicsLayer { alpha = shown(index)() })
                        }
                    }
                }
                // The next rooms down the list.
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.surfaceContainerLow).padding(horizontal = 10.dp),
                ) {
                    repeat(2) {
                        Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { TextLine(0.4f, strong = true) }
                            TextLine(0.25f, color = FreeGreen.copy(alpha = 0.6f))
                        }
                    }
                }
            }
    }
}

private val FreeGreen = Color(0xFF2E9E5B)

/** The class before as a ring, the room as a dot, the class after as a ring, and the legs between. */
@Composable
private fun RouteLine(route: Route, modifier: Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Stop(filled = false)
            LegView(route.toRoom)
            Stop(filled = true)
            LegView(route.toNext)
            Stop(filled = false)
        }
        Row {
            MockText(route.before, size = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            MockText(route.after, size = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Stop(filled: Boolean) {
    val outline = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .then(if (filled) Modifier.background(FreeGreen) else Modifier.border(2.dp, outline, CircleShape)),
    )
}

/** A leg: stairs with the floors, a dotted walk with its minutes, or a bare line along the floor. */
@Composable
private fun RowScope.LegView(leg: Leg) {
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val dotted = leg is Leg.Walk
    Box(Modifier.weight(1f).height(22.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().height(2.dp)) {
            drawLine(
                ink.copy(alpha = 0.6f),
                Offset(4.dp.toPx(), size.height / 2),
                Offset(size.width - 4.dp.toPx(), size.height / 2),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = if (dotted) PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx())) else null,
            )
        }
        when (leg) {
            is Leg.Stairs -> LegBadge(Icons.Filled.Stairs, (if (leg.floors > 0) "↑" else "↓") + kotlin.math.abs(leg.floors))
            is Leg.Walk -> LegBadge(Icons.AutoMirrored.Filled.DirectionsWalk, stringResource(R.string.empty_classroom_walk_minutes, leg.minutes))
            Leg.Along -> Unit
        }
    }
}

@Composable
private fun LegBadge(icon: ImageVector, label: String) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(colors.surfaceContainerLow).padding(horizontal = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(1.dp))
        MockText(label, size = 9.sp, weight = FontWeight.SemiBold, color = colors.onSurfaceVariant)
    }
}
