package org.ntust.app.tigerduck.ui.screen.emptyclassroom

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Business
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.emptyclassroom.BuildingDirectory
import org.ntust.app.tigerduck.emptyclassroom.RoomUsage
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

/**
 * One building's free rooms by floor, under a row of building chips with their free-room counts.
 * A building with no free room offers the nearest ones that have one, and the next period.
 */
@Composable
internal fun BuildingSection(
    directory: BuildingDirectory,
    shown: String,
    siteNames: Map<String, String>,
    period: Int,
    ended: Int,
    onPick: (String) -> Unit,
    /** Null at the last period of the day. */
    onNextPeriod: (() -> Unit)?,
    onRoom: (RoomUsage) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = buildingName(shown, siteNames),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        BuildingChips(directory, shown, siteNames, onPick, Modifier.padding(top = 8.dp))
        val floors = directory.floors(shown)
        if (floors.isEmpty()) {
            NoFreeRoom(directory, shown, siteNames, onPick, onNextPeriod, Modifier.padding(16.dp))
        } else {
            floors.forEach { floor ->
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                    Text(
                        text = floor.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                    )
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        floor.rooms.forEachIndexed { index, room ->
                            if (index > 0) HorizontalDivider(Modifier.padding(start = 16.dp))
                            RoomRow(room, period, ended, onRoom)
                        }
                    }
                }
            }
        }
    }
}

/** Scrolled to the building shown, which can sort past the screen's edge. */
@Composable
private fun BuildingChips(
    directory: BuildingDirectory,
    shown: String,
    siteNames: Map<String, String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(shown, directory.buildings.size) {
        val index = directory.buildings.indexOfFirst { it.code == shown }
        if (index >= 0) listState.animateScrollToItem(maxOf(0, index - 1))
    }
    LazyRow(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(directory.buildings, key = { it.code }) { building ->
            BuildingChip(building, building.code == shown, siteNames, onPick)
        }
    }
}

@Composable
private fun BuildingChip(
    building: BuildingDirectory.Building,
    selected: Boolean,
    siteNames: Map<String, String>,
    onPick: (String) -> Unit,
) {
    val name = buildingName(building.code, siteNames)
    val count = stringResource(R.string.a11y_empty_classroom_free_count, building.free.size)
    val description = joinLabel(name, count)
    FilterChip(
        selected = selected,
        onClick = { onPick(building.code) },
        label = { Text(building.code, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)) },
        trailingIcon = { CountPill(building.free.size, selected) },
        shape = RoundedCornerShape(50),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
        // As one description, "研揚大樓, 5 free classrooms": a state description would take the
        // place of the selected state TalkBack reads for the chip.
        modifier = Modifier.semantics { contentDescription = description },
    )
}

@Composable
private fun CountPill(count: Int, selected: Boolean) {
    val fill = when {
        selected -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.24f)
        count > 0 -> FinderColors.freeFill.copy(alpha = 0.2f)
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    }
    val text = when {
        selected -> MaterialTheme.colorScheme.onPrimary
        count > 0 -> FinderColors.freeText
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
    }
    Box(
        Modifier
            .widthIn(min = 24.dp)
            .background(fill, RoundedCornerShape(50))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                fontFeatureSettings = TabularFigures,
            ),
            color = text,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoFreeRoom(
    directory: BuildingDirectory,
    shown: String,
    siteNames: Map<String, String>,
    onPick: (String) -> Unit,
    onNextPeriod: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Filled.Business,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
            )
            Text(
                stringResource(R.string.empty_classroom_no_free_rooms),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                directory.nearby(shown).forEach { building ->
                    BuildingChip(building, selected = false, siteNames, onPick)
                }
                if (onNextPeriod != null) {
                    FilledIconButton(onClick = onNextPeriod) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.a11y_empty_classroom_next_period),
                        )
                    }
                }
            }
        }
    }
}
