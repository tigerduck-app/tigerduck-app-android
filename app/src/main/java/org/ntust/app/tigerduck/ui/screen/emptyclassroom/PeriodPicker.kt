package org.ntust.app.tigerduck.ui.screen.emptyclassroom

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.emptyclassroom.EmptyClassroomSchedule
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import java.time.LocalDate

/** The date menu, offering today and the 13 days after it, with the chosen period's times. */
@Composable
internal fun ClassroomTimeBar(
    days: List<LocalDate>,
    today: LocalDate,
    selectedDay: LocalDate,
    period: Int,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box {
            AssistChip(
                onClick = { expanded = true },
                label = {
                    Text(
                        dayLabel(selectedDay, today),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingIcon = {
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null, Modifier.size(AssistChipDefaults.IconSize))
                },
                shape = RoundedCornerShape(50),
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                days.forEach { day ->
                    val isSelected = day == selectedDay
                    DropdownMenuItem(
                        text = { Text(dayLabel(day, today)) },
                        onClick = {
                            expanded = false
                            onSelect(day)
                        },
                        leadingIcon = {
                            if (isSelected) Icon(Icons.Filled.Check, contentDescription = null)
                            else Spacer(Modifier.size(24.dp))
                        },
                        modifier = Modifier.semantics { selected = isSelected },
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = EmptyClassroomSchedule.timeRangeText(period),
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = TabularFigures),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
            maxLines = 1,
        )
    }
}

/**
 * The 14 periods as chips, each its label over its start time. The current period's label is in
 * the accent colour, as a calendar marks today, and the periods already over are dimmed but can
 * still be chosen. The row opens on the current period, since the ones before it rarely matter.
 */
@Composable
internal fun PeriodChipRow(
    selected: Int,
    current: Int?,
    ended: Int,
    onSelect: (Int) -> Unit,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, (current ?: selected) - 1))
    LaunchedEffect(selected) {
        val visible = listState.layoutInfo.visibleItemsInfo
        val fullyVisible = visible.any {
            it.index == selected && it.offset >= 0 &&
                it.offset + it.size <= listState.layoutInfo.viewportEndOffset
        }
        if (!fullyVisible) listState.animateScrollToItem(maxOf(0, selected - 1))
    }
    val now = stringResource(R.string.home_time_slider_now)
    LazyRow(
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(EmptyClassroomSchedule.periodIds) { index, id ->
            val isSelected = index == selected
            val times = EmptyClassroomSchedule.timeRangeText(index)
            val periodLabel = stringResource(R.string.a11y_empty_classroom_period, id)
            val description = if (index == current) joinLabel(periodLabel, times, now) else joinLabel(periodLabel, times)
            Surface(
                selected = isSelected,
                onClick = { onSelect(index) },
                shape = RoundedCornerShape(14.dp),
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .alpha(if (index < ended && !isSelected) 0.4f else 1f)
                    // The times go in the description: a state description would take the
                    // place of the selected state TalkBack reads for the chip.
                    .semantics {
                        contentDescription = description
                        role = Role.RadioButton
                    },
            ) {
                Column(
                    modifier = Modifier
                        .clearAndSetSemantics {}
                        .widthIn(min = 48.dp)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = id,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = when {
                            isSelected -> MaterialTheme.colorScheme.onPrimary
                            index == current -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                    )
                    Text(
                        text = EmptyClassroomSchedule.startTime(index),
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = TabularFigures),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
                        },
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
