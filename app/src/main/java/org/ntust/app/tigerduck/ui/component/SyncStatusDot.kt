// The one status mark in a page header.
//
// Replaces SyncIndicator + ServerStatusIcons, which between them put a
// spinner and three coloured glyphs in every header — four marks competing
// with the page title, three of which meant nothing to a reader who did not
// already know which icon was Moodle and which was the backend.
//
// Colour is the worst known state of the sources the page depends on
// (red > green). Grey means nothing has reported yet or the source is
// switched off, and never wins: an unconfigured backend must not paint the
// header as though Moodle were down. While a fetch is running the dot
// becomes a spinning ring. Tapping it lists every source with its own state,
// which is where the detail the three icons used to carry now lives.
//
// On a screen that pulls to refresh, the dot also carries that gesture's
// progress: a ring closes around it as the finger travels, completing
// exactly where a release would trigger the refresh, and handing over to
// the spinning ring once one is running.
//
// Ported from iOS SharedUI/SyncStatusDot.swift (a46c27b, ab2261f).

package org.ntust.app.tigerduck.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import kotlinx.coroutines.delay
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

/** A quiet second with the popover closed fades the dot. */
private const val IDLE_DELAY_MS = 1_000L

/**
 * Low enough to read as "background", high enough that red vs green still
 * tells apart at a glance.
 */
private const val IDLE_ALPHA = 0.5f

private const val RING_SPIN_MS = 600
private const val CROSSFADE_MS = 150

/**
 * Every mark below is drawn into a canvas that fills the whole 28dp target
 * and is sized off the shared [androidx.compose.ui.graphics.drawscope.DrawScope.center],
 * rather than being a differently-sized child centred by the parent.
 *
 * That is deliberate: `Alignment.Center` rounds each child's offset to whole
 * pixels *independently*, so at some densities two concentric children land
 * on centres half a pixel apart. At density 2.75 the 10dp dot rounds to
 * offset 25 (centre 39.0) while the 22dp ring rounds to offset 8
 * (centre 38.5) — the dot sits visibly down and to the right inside its own
 * ring. Sharing one canvas and one centre makes concentricity exact at
 * every density.
 */
private val DOT_SIZE = 10.dp

private val SPINNER_SIZE = 12.dp
private val SPINNER_STROKE = 2.dp

/**
 * Outer diameter of the pull ring. A 2dp stroke here puts the ring's inner
 * edge at radius 9dp against the dot's 5dp, so 4dp of clear space separates
 * them — enough that they read as two marks rather than one thick blob —
 * while the whole thing still sits inside the 28dp touch target.
 */
private val PULL_RING_SIZE = 22.dp
private val PULL_RING_STROKE = 2.dp

/** Below this the arc is a speck, and a round cap would draw it as a smudge. */
private const val PULL_RING_MIN = 0.02f

/**
 * @param isLoading drives the ring. Pass the page's own refresh state; the
 *   dot does not infer it, because "the page is fetching" and "a server last
 *   answered badly" are different questions and only the page knows the first.
 */
@Composable
fun SyncStatusDot(
    servers: List<ServerKind>,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    val statuses by ServerStatusTracker.statuses.collectAsState()
    val cloudSyncEnabled by ServerStatusTracker.cloudSyncEnabled.collectAsState()
    val sources = servers.map { server ->
        SyncSourceRow(
            server = server,
            status = statuses[server] ?: ServerStatus.UNKNOWN,
            minimal = server == ServerKind.BACKEND && !cloudSyncEnabled,
        )
    }
    val summary = summarize(sources.map { if (it.minimal) ServerStatus.UNKNOWN else it.status })

    var showDetails by remember { mutableStateOf(false) }
    var dimmed by remember { mutableStateOf(false) }

    val pullProgress = LocalPullProgress.current
    // Only the crossing matters up here — the arc reads the float in its own
    // draw scope, so a pull repaints without recomposing anything.
    val pulling by remember(pullProgress) {
        derivedStateOf { pullProgress.value >= PULL_RING_MIN }
    }

    // Any change or interaction brings the dot back to full strength. A
    // running fetch never fades — the ring is the progress indicator, and a
    // half-faded spinner reads as a rendering bug. Nor does a pull in
    // progress, which the ring is drawn around.
    LaunchedEffect(summary, isLoading, showDetails, pulling) {
        dimmed = false
        if (showDetails || isLoading || pulling) return@LaunchedEffect
        delay(IDLE_DELAY_MS)
        dimmed = true
    }

    val syncingLabel = stringResource(R.string.sync_status_syncing)
    val summaryLabel = statusText(summary)

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable { showDetails = true }
                .alpha(if (dimmed) IDLE_ALPHA else 1f)
                // Always attach the semantics node so TalkBack sees an
                // existing live-region whose contentDescription changes —
                // node creation alone does not reliably announce.
                .semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = if (isLoading) syncingLabel else summaryLabel
                },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = isLoading,
                transitionSpec = {
                    (fadeIn(tween(CROSSFADE_MS)) + scaleIn(tween(CROSSFADE_MS))) togetherWith
                        (fadeOut(tween(CROSSFADE_MS)) + scaleOut(tween(CROSSFADE_MS)))
                },
                label = "sync_status_dot",
            ) { loading ->
                if (loading) SpinningRing(statusColor(summary)) else Dot(statusColor(summary))
            }

            // Outside the dot rather than in place of it, so the status
            // colour stays readable while the gesture is in flight. Absent
            // during a fetch: a second pull cannot trigger anything, and an
            // arc that fills without doing something is a promise the
            // gesture does not keep.
            if (!isLoading) PullRing(statusColor(summary), pullProgress)
        }

        DropdownMenu(
            expanded = showDetails,
            onDismissRequest = { showDetails = false },
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                // IntrinsicSize.Max sizes the column to its widest row, which
                // is what lets each row fillMaxWidth without the popup
                // stretching to the window — DropdownMenu hands its content
                // the full screen width as a max constraint.
                modifier = Modifier
                    .width(IntrinsicSize.Max)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isLoading) {
                    SourceRow(
                        color = Color.Transparent,
                        icon = Icons.Filled.Sync,
                        name = syncingLabel,
                        text = null,
                    )
                }
                sources.forEach { source ->
                    SourceRow(
                        color = statusColor(if (source.minimal) ServerStatus.UNKNOWN else source.status),
                        icon = source.server.icon,
                        name = stringResource(source.server.labelRes),
                        text = if (source.minimal) {
                            stringResource(R.string.sync_status_minimal)
                        } else {
                            statusText(source.status)
                        },
                    )
                }
            }
        }
    }
}

private data class SyncSourceRow(
    val server: ServerKind,
    val status: ServerStatus,
    /**
     * Cloud sync switched off.
     *
     * Reported as *minimal* rather than off, and never as a failure: the
     * backend is still doing work for this device. The academic calendar —
     * semester dates and holidays — and the bulletin feed are public GETs
     * that carry no account and are fetched regardless of the sync setting,
     * so "Off" would tell the user the class table is getting nothing from
     * the server when it is still getting the dates it silences reminders
     * by. The colour stays grey, as when it read "Off".
     */
    val minimal: Boolean,
)

@Composable
private fun Dot(color: Color) {
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(color, radius = DOT_SIZE.toPx() / 2f)
    }
}

@Composable
private fun SpinningRing(color: Color) {
    val transition = rememberInfiniteTransition(label = "sync_ring")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(RING_SPIN_MS, easing = LinearEasing)),
        label = "sync_ring_angle",
    )
    Canvas(Modifier.fillMaxSize()) {
        val strokePx = SPINNER_STROKE.toPx()
        val radius = SPINNER_SIZE.toPx() / 2f - strokePx / 2f
        drawArc(
            color = color,
            // Matches iOS's trim(from: 0.2): a gap is what reads as motion on
            // a ring this small, and a full circle would look static.
            startAngle = angle,
            sweepAngle = 288f,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(width = strokePx, cap = StrokeCap.Round),
        )
    }
}

/**
 * The pull-to-refresh gesture, drawn as an arc closing clockwise around the
 * dot. Full circle lands exactly where releasing would start a refresh, so
 * the ring is the answer to "have I pulled far enough yet".
 */
@Composable
private fun PullRing(color: Color, progress: State<Float>) {
    Canvas(Modifier.fillMaxSize()) {
        val fraction = progress.value
        if (fraction < PULL_RING_MIN) return@Canvas
        val strokePx = PULL_RING_STROKE.toPx()
        val radius = PULL_RING_SIZE.toPx() / 2f - strokePx / 2f
        drawArc(
            color = color,
            // Twelve o'clock, then clockwise — the direction the finger went.
            startAngle = -90f,
            sweepAngle = 360f * fraction,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(width = strokePx, cap = StrokeCap.Round),
        )
    }
}

/**
 * Dot, icon, name, state. The icon sits in a fixed-width slot so glyphs of
 * different widths still leave every name starting on one column.
 */
@Composable
private fun SourceRow(color: Color, icon: ImageVector, name: String, text: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Canvas(Modifier.size(8.dp)) { drawCircle(color) }
        Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
            )
        }
        Text(name, style = MaterialTheme.typography.bodyMedium)
        if (text != null) {
            // Takes the slack so every state lands on the right edge, in one
            // column. A fixed spacer left them ragged, tracking each name's
            // length rather than the popup's edge.
            Spacer(Modifier.weight(1f).widthIn(min = 12.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
            )
        }
    }
}

/**
 * Worst-of reduction, ignoring UNKNOWN unless nothing else is known.
 *
 * Internal and pure so the rule is testable: "grey never wins" is the part
 * that stops a switched-off backend or a not-yet-contacted server from
 * painting the header as if something had failed.
 */
internal fun summarize(statuses: List<ServerStatus>): ServerStatus = when {
    ServerStatus.FAILED in statuses -> ServerStatus.FAILED
    ServerStatus.OK in statuses -> ServerStatus.OK
    else -> ServerStatus.UNKNOWN
}

@Composable
private fun statusText(status: ServerStatus): String = stringResource(
    when (status) {
        ServerStatus.OK -> R.string.sync_status_ok
        ServerStatus.FAILED -> R.string.sync_status_failed
        ServerStatus.UNKNOWN -> R.string.bulletin_push_status_unknown
    }
)

private fun statusColor(status: ServerStatus): Color = when (status) {
    ServerStatus.UNKNOWN -> Color.Gray
    ServerStatus.OK -> Color(0xFF34C759)
    ServerStatus.FAILED -> Color(0xFFFF3B30)
}

private val ServerKind.icon: ImageVector
    get() = when (this) {
        ServerKind.MOODLE -> Icons.Filled.School
        ServerKind.COURSE_SELECTION -> Icons.Outlined.Checklist
        ServerKind.BACKEND -> Icons.Filled.Cloud
    }

private val ServerKind.labelRes: Int
    get() = when (this) {
        ServerKind.MOODLE -> R.string.calendar_source_moodle
        ServerKind.COURSE_SELECTION -> R.string.feature_course_selection
        ServerKind.BACKEND -> R.string.cloud_sync_title
    }
