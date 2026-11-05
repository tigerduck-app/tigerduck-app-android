package org.ntust.app.tigerduck.ui.screen.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.clubs.ClubOccurrence
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.ui.theme.LocalVisualStylePolicy
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The card for what the slider is on: one per item in session (衝堂 gives several), or the ones
 * either side of the moment, each a class's [SlotCard] or a club's [ClubCard]. As iOS `CourseTimeCard`.
 */
@Composable
internal fun CourseTimeCard(
    state: CourseState<TimeSliderItem>,
    onSelect: (CourseTimeSlot) -> Unit,
    onSelectClub: (ClubOccurrence) -> Unit,
) {
    // Pin the card area to the tallest height seen this session so the slider
    // track below doesn't bob up and down as the user scrubs across states
    // with different card layouts. `remember` resets on Home tab destruction.
    var maxHeightPx by remember { mutableIntStateOf(0) }
    val minHeightDp = with(LocalDensity.current) { maxHeightPx.toDp() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeightDp)
    ) {
        EqualHeightRow(
            spacing = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged {
                    if (it.height > maxHeightPx) maxHeightPx = it.height
                }
        ) {
            // 翹課 is parked, so every SlotCard takes the `isSkipped = false` /
            // `onSkipToggle = null` defaults: no red course name, no left-swipe
            // gesture, nothing to discover. SlotCard itself still implements both
            // — passing them here is what turns the feature back on. See
            // HomeViewModel._skippedDates for when.
            state.cards().forEach { (item, muted) ->
                when (item) {
                    is TimeSliderItem.Course -> SlotCard(slot = item.slot, muted = muted, onClick = { onSelect(item.slot) })
                    is TimeSliderItem.Club -> ClubCard(item, muted = muted, onClick = { onSelectClub(item.occurrence) })
                }
            }
        }
    }
}

/** The items the cards stand for, each with whether it is beside the moment rather than on it. */
private fun CourseState<TimeSliderItem>.cards(): List<Pair<TimeSliderItem, Boolean>> = when (this) {
    is CourseState.InClass -> slots.map { it to false }
    is CourseState.Between -> listOfNotNull(previous, next).map { it to true }
    is CourseState.BeforeFirst -> listOf(next to true)
    is CourseState.AfterLast -> listOf(previous to true)
}

/** The colors a card's lines are drawn in. */
internal class CardInk(val name: Color, val meta: Color)

/**
 * What every card is made of, a class's or a club's: the [tint]ed fill, [time] on the first line
 * (with the date beside it unless it is today's), then [content] under it. As iOS `cardSurface`.
 */
@Composable
internal fun TimeSliderCardSurface(
    tint: Color,
    muted: Boolean,
    date: Date,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    time: @Composable (CardInk) -> Unit,
    content: @Composable ColumnScope.(CardInk) -> Unit,
) {
    val style = LocalVisualStylePolicy.current
    val isDark = TigerDuckTheme.isDarkMode
    val container = style.courseCardFill(
        courseColor = tint,
        tintAlpha = when {
            isDark -> if (muted) 0.40f else 0.55f
            else -> if (muted) 0.35f else 0.50f
        },
        muted = muted,
        surface = MaterialTheme.colorScheme.surface,
        isDark = isDark,
    )
    // Also the card's content color, so its press ripple reads on the fill.
    val nameColor = style.courseCardTextColor(LocalContentColor.current)
    val ink = CardInk(nameColor, style.courseCardSecondaryTextColor(MaterialTheme.colorScheme.onSurface))
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = nameColor)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            if (isToday(date)) {
                time(ink)
            } else {
                FitOrStack(
                    modifier = Modifier.fillMaxWidth(),
                    first = { time(ink) },
                    second = { Text(formatDateLabel(date), style = MaterialTheme.typography.labelSmall, color = ink.meta) },
                )
            }
            Spacer(Modifier.height(4.dp))
            content(ink)
        }
    }
}

private fun isToday(date: Date): Boolean {
    val cal = Calendar.getInstance(AppConstants.TAIPEI_TZ)
    return AppClock.calendar().let {
        val today = it.get(Calendar.DAY_OF_YEAR)
        cal.time = date
        today == cal.get(Calendar.DAY_OF_YEAR) && it.get(Calendar.YEAR) == cal.get(Calendar.YEAR)
    }
}

internal fun appLocale(): Locale =
    LocaleListCompat.getAdjustedDefault()[0] ?: Locale.getDefault()

private fun dateLabelFmt() =
    java.time.format.DateTimeFormatter.ofPattern("M/d (EEEEE)", appLocale())

internal fun formatDateLabel(date: Date): String =
    dateLabelFmt().format(date.toInstant().atZone(AppConstants.TAIPEI_ZONE))

/**
 * Lays children out in a single row, splitting available width equally and
 * forcing every child to the tallest natural height in the group. Avoids
 * `Modifier.height(IntrinsicSize.Min)` which crashes for SubcomposeLayout
 * children (SlotCard contains FitOrStack / InlineOrStackText). Uses two
 * subcompositions — one to probe natural heights, one for placement —
 * because Compose only permits a single `measure()` call per Measurable.
 */
@Composable
private fun EqualHeightRow(
    spacing: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    SubcomposeLayout(modifier) { constraints ->
        val spacingPx = spacing.roundToPx()
        val probeMeasurables = subcompose(EqualHeightSlot.Probe, content)
        val n = probeMeasurables.size
        if (n == 0) return@SubcomposeLayout layout(constraints.maxWidth, 0) {}
        val totalSpacing = if (n > 1) spacingPx * (n - 1) else 0
        val widthPerChild = ((constraints.maxWidth - totalSpacing) / n).coerceAtLeast(0)
        val probeConstraints = Constraints(
            minWidth = widthPerChild, maxWidth = widthPerChild,
            minHeight = 0, maxHeight = Constraints.Infinity,
        )
        val maxH = probeMeasurables.maxOf { it.measure(probeConstraints).height }
        val finalMeasurables = subcompose(EqualHeightSlot.Place, content)
        val finalConstraints = Constraints(
            minWidth = widthPerChild, maxWidth = widthPerChild,
            minHeight = maxH, maxHeight = maxH,
        )
        val placeables = finalMeasurables.map { it.measure(finalConstraints) }
        layout(constraints.maxWidth, maxH) {
            var x = 0
            placeables.forEach { p ->
                p.place(x, 0)
                x += p.width + spacingPx
            }
        }
    }
}

private enum class EqualHeightSlot { Probe, Place }

/**
 * Expands a child to its parent's max height only when the parent supplies a
 * bounded height constraint. Lets EqualHeightRow's place pass stretch the
 * Card to the equalized slot height while still allowing the probe pass
 * (unbounded max height) to measure natural content height without crashing.
 */
internal fun Modifier.fillBoundedHeight(): Modifier = layout { measurable, constraints ->
    val adjusted = if (constraints.hasBoundedHeight) {
        constraints.copy(minHeight = constraints.maxHeight)
    } else {
        constraints
    }
    val placeable = measurable.measure(adjusted)
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/**
 * Places [first] at the start and [second] at the end on a single row if both
 * fit; otherwise stacks them vertically with [first] above [second].
 */
@Composable
private fun FitOrStack(
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    minGap: Dp = 8.dp,
) {
    SubcomposeLayout(modifier) { constraints ->
        val unbounded = Constraints()
        val f = subcompose("first", first).first().measure(unbounded)
        val s = subcompose("second", second).first().measure(unbounded)
        val gap = minGap.roundToPx()
        val width = constraints.maxWidth
        if (f.width + gap + s.width <= width) {
            val h = maxOf(f.height, s.height)
            layout(width, h) {
                f.place(0, (h - f.height) / 2)
                s.place(width - s.width, (h - s.height) / 2)
            }
        } else {
            layout(width, f.height + s.height) {
                f.place(0, 0)
                s.place(0, f.height)
            }
        }
    }
}

