package org.ntust.app.tigerduck.ui.screen.whatsnew

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.ui.navigation.icon

// A demo that first walks to its feature, as a user would find it: from More,
// through Settings' screens, a tap on each, before the feature's own animation.
// Rows on the way are a fixed height, so the fingertip's place doesn't depend
// on the fonts.

/** Where the way to a feature starts, in More. */
internal sealed interface PathStart {
    /** The gear in More's corner, to Settings. */
    data object SettingsGear : PathStart

    /** [feature]'s card in More's grid. */
    data class MoreCard(val feature: AppFeature) : PathStart
}

/**
 * One screen on the way: [title] over the list, which scrolls down to
 * [section] when [scrolled] (with other sections above it), whose [rows] the
 * finger taps the [tapped] one of.
 */
internal class PathStop(
    val title: String,
    val section: String?,
    val rows: List<String>,
    val tapped: Int,
    val scrolled: Boolean = true,
)

// The way's steps, in milliseconds, whatever the feature's own part: More's
// tap, each screen on the way, a screen sliding in, and the way back to More.
private const val MORE_MILLIS = 1_000
private const val STOP_MILLIS = 2_000
private const val SLIDE_MILLIS = 360
private const val END_MILLIS = 450

/**
 * When a path demo's steps happen, as parts (0 to 1) of its loop: More, then
 * [stops] screens, then the feature's [featureMillis], then the way back.
 */
internal class PathTiming(stops: Int, featureMillis: Int) {
    val loopMillis = MORE_MILLIS + STOP_MILLIS * stops + featureMillis + END_MILLIS
    val more = part(MORE_MILLIS)
    val stop = part(STOP_MILLIS)
    val slide = part(SLIDE_MILLIS)
    val pathEnd = more + stop * stops
    val end = 1f - part(END_MILLIS)

    /** Where screen [index] on the way starts. */
    fun stopFrom(index: Int) = more + stop * index

    private fun part(millis: Int) = millis.toFloat() / loopMillis
}

private val RowHeight = 26.dp
private val TitleHeight = 28.dp
private val SectionHeight = 16.dp
private val Gap = 6.dp
private val PathScroll = 120.dp

/**
 * [feature]'s demo after the way to it from [start] through [stops]: the
 * feature gets [featureMillis] of the loop, and its own progress (0 to 1) as
 * [feature]'s argument; [below] draws under the screen, given the same. With
 * animations off, the feature's [featureStill].
 */
@Composable
internal fun PathDemo(
    description: String,
    animate: Boolean,
    featureMillis: Int,
    start: PathStart,
    stops: List<PathStop>,
    featureStill: Float,
    below: (@Composable (feature: () -> Float) -> Unit)? = null,
    feature: @Composable BoxScope.(feature: () -> Float) -> Unit,
) {
    val timing = remember(stops.size, featureMillis) { PathTiming(stops.size, featureMillis) }
    val more = timing.more
    val stopPart = timing.stop
    val pathEnd = timing.pathEnd
    val end = timing.end
    val clock = demoClock(animate, timing.loopMillis, still = pathEnd + featureStill * (end - pathEnd))
    val t = { clock.value }
    val featureT = { ((t() - pathEnd) / (end - pathEnd)).coerceIn(0f, 1f) }
    // Everything on the way goes as the loop ends, back to More.
    val ending = { 1f - span(t(), end, end + (1f - end) * 0.875f) }
    Column(Modifier.demoDescription(description), horizontalAlignment = Alignment.CenterHorizontally) {
        MockScreen {
            when (start) {
                PathStart.SettingsGear -> MoreWithGear()
                is PathStart.MoreCard -> MoreWithCard(start.feature)
            }
            // A screen's moments, as parts of its own step: the list scrolls, then a row is tapped.
            stops.forEachIndexed { index, stop ->
                val from = timing.stopFrom(index)
                StopScreen(
                    stop,
                    Modifier.graphicsLayer {
                        translationX = (1f - span(t(), from - timing.slide, from) * ending()) * size.width
                    },
                    scroll = { if (stop.scrolled) span(t(), from + stopPart * 0.12f, from + stopPart * 0.5f) else 0f },
                    tapped = { window(t(), from + stopPart * 0.69f, from + stopPart * 1.12f) },
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = (1f - span(t(), pathEnd - timing.slide, pathEnd) * ending()) * size.width },
            ) { feature(featureT) }
            // The taps on the way: More's gear or card, then each screen's row.
            when (start) {
                PathStart.SettingsGear ->
                    Fingertip(196.dp, 22.dp, shown = { window(t(), more * 0.12f, more) }, press = { span(t(), more * 0.62f, more * 0.88f) })
                is PathStart.MoreCard ->
                    Fingertip("more card", shown = { window(t(), more * 0.12f, more) }, press = { span(t(), more * 0.62f, more * 0.88f) })
            }
            stops.forEachIndexed { index, stop ->
                val from = timing.stopFrom(index)
                Fingertip(
                    110.dp,
                    rowCenter(stop),
                    shown = { window(t(), from + stopPart * 0.5f, from + stopPart * 0.94f) },
                    press = { span(t(), from + stopPart * 0.69f, from + stopPart * 0.88f) },
                )
            }
        }
        if (below != null) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.height(24.dp), contentAlignment = Alignment.Center) { below(featureT) }
        }
    }
}

/** Where [stop]'s tapped row sits once the list has scrolled, from the screen's top. */
private fun rowCenter(stop: PathStop) =
    (if (stop.scrolled) TitleHeight + Gap + (SectionHeight + Gap + RowHeight * 3 + Gap) * 2 - PathScroll else TitleHeight + Gap) +
        (if (stop.section != null) SectionHeight + Gap else 0.dp) + RowHeight * stop.tapped + RowHeight / 2

/** More: its title and the gear that opens Settings, over its grid. */
@Composable
private fun MoreWithGear() {
    val colors = MaterialTheme.colorScheme
    MoreFrame {
        Row(Modifier.height(TitleHeight), verticalAlignment = Alignment.CenterVertically) {
            MockText(stringResource(AppFeature.MORE.shortDisplayNameRes), Modifier.weight(1f), size = 15.sp, weight = FontWeight.Bold)
            Icon(Icons.Filled.Settings, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        MoreCards(picked = null)
    }
}

/** More, its [feature] card in the grid to tap. */
@Composable
private fun MoreWithCard(feature: AppFeature) {
    MoreFrame {
        Row(Modifier.height(TitleHeight), verticalAlignment = Alignment.CenterVertically) {
            MockText(stringResource(AppFeature.MORE.shortDisplayNameRes), size = 15.sp, weight = FontWeight.Bold)
        }
        MoreCards(picked = feature)
    }
}

@Composable
private fun MoreFrame(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** Three rows of cards; [picked]'s is the second row's right one, named, with its icon. */
@Composable
private fun MoreCards(picked: AppFeature?) {
    val colors = MaterialTheme.colorScheme
    repeat(3) { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(2) { column ->
                val feature = picked?.takeIf { row == 1 && column == 1 }
                Column(
                    Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (feature != null) colors.primaryContainer else colors.surface)
                        .then(if (feature != null) Modifier.tapTarget("more card") else Modifier)
                        .padding(8.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (feature != null) {
                        Icon(feature.icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(14.dp))
                        MockText(stringResource(feature.displayNameRes), size = 10.sp, weight = FontWeight.SemiBold)
                    } else {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(colors.primary.copy(alpha = 0.6f)))
                        TextLine(0.6f, strong = true)
                    }
                }
            }
        }
    }
}

/** A screen on the way: its list, scrolled by [scroll] (0 to 1), its tapped row lit by [tapped]. */
@Composable
private fun StopScreen(stop: PathStop, modifier: Modifier, scroll: () -> Float, tapped: () -> Float) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.fillMaxSize().background(colors.surfaceContainerLow).clipToBounds()) {
        Column(
            Modifier
                .wrapContentHeight(Alignment.Top, unbounded = true)
                .padding(horizontal = 12.dp)
                .graphicsLayer { translationY = -scroll() * PathScroll.toPx() },
            verticalArrangement = Arrangement.spacedBy(Gap),
        ) {
            Box(Modifier.height(TitleHeight), contentAlignment = Alignment.CenterStart) {
                MockText(stop.title, size = 15.sp, weight = FontWeight.Bold)
            }
            if (stop.scrolled) {
                repeat(2) {
                    Box(Modifier.height(SectionHeight), contentAlignment = Alignment.CenterStart) { TextLine(0.25f, height = 5.dp) }
                    PathCard { repeat(3) { PathRow(null) } }
                }
            }
            if (stop.section != null) {
                Box(Modifier.height(SectionHeight), contentAlignment = Alignment.CenterStart) {
                    MockText(stop.section, size = 9.sp, weight = FontWeight.SemiBold, color = colors.primary)
                }
            }
            PathCard {
                stop.rows.forEachIndexed { index, label ->
                    PathRow(
                        label,
                        if (index == stop.tapped) Modifier.drawBehind { drawRect(colors.primary.copy(alpha = 0.16f * tapped())) } else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun PathCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface), content = content)
}

/** A row [RowHeight] tall, whatever its text: [label], or a grey line for one. */
@Composable
private fun PathRow(label: String?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(RowHeight).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (label != null) MockText(label, Modifier.weight(1f), size = 10.sp) else Box(Modifier.weight(1f)) { TextLine(0.5f, strong = true) }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
    }
}
