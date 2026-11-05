// The subscribed clubs' activities on the grid, a column at a time, as the iPhone's
// TimetableClubColumn and TimetableClubBlock: what is left of each once the day's courses and every
// higher club are taken out (ClassTableClubLayout), in the course block's fill with a 3dp bar of the
// club's color on its leading edge, the name from 24dp tall and the location from 44dp.

package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.clubs.ClubOccurrence
import org.ntust.app.tigerduck.data.model.TimetablePeriod
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.screen.classtable.clubs.clubDisplayColor
import org.ntust.app.tigerduck.ui.screen.classtable.clubs.details
import org.ntust.app.tigerduck.ui.theme.LocalVisualStylePolicy
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme

/** The width of the club's color bar on a block's leading edge, as on the iPhone. */
private val CLUB_BAR_WIDTH = 3.dp

/** What the grid does with a club's block: a tap lists what it meets, a long press its color. */
internal class ClubBlockActions(
    /** The tapped block's activity and the others it meets, highest priority first. */
    val onTap: (List<ClubOccurrence>) -> Unit,
    /** Null on a table with no edit menu. */
    val onPickColor: ((ClubOccurrence) -> Unit)?,
    val onLongPress: (() -> Unit)?,
)

/**
 * [clubs], the activities of [weekday]'s date, placed over the column at [x] the way the rows
 * stand, [cellHeight] each and touching, as the course cells are.
 */
@Composable
internal fun ClassTableClubColumn(
    clubs: List<ClubOccurrence>,
    courses: List<Course>,
    weekday: Int,
    periods: List<TimetablePeriod>,
    x: Dp,
    width: Dp,
    cellHeight: Dp,
    actions: ClubBlockActions,
) {
    if (clubs.isEmpty()) return
    val blocks = remember(clubs, courses, weekday, periods, cellHeight) {
        ClassTableClubLayout.blocks(
            ClassTableClubLayout.segments(clubs, ClassTableClubLayout.courseTimes(courses, weekday)),
            periods.map(ClassTableClubLayout::times),
            rowPitch = cellHeight.value,
            cellHeight = cellHeight.value,
        )
    }
    val drawn = blocks.map { it.segment.occurrence }
    blocks.forEach { block ->
        key(block.id) {
            ClubBlock(
                block = block,
                modifier = Modifier.absoluteOffset(x = x, y = block.top.dp).width(width).height(block.height.dp),
                onTap = { actions.onTap(ClassTableClubLayout.overlapping(block.segment.occurrence, drawn)) },
                onPickColor = actions.onPickColor,
                onLongPress = actions.onLongPress,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClubBlock(
    block: ClassTableClubLayout.Block,
    modifier: Modifier,
    onTap: () -> Unit,
    onPickColor: ((ClubOccurrence) -> Unit)?,
    onLongPress: (() -> Unit)?,
) {
    val club = block.segment.occurrence
    val style = LocalVisualStylePolicy.current
    val light = Color(0xFF000000.toInt() or club.color)
    val fill = style.classTableCourseFill(light, clubDisplayColor(club.color), TigerDuckTheme.isDarkMode)
    val shape = RoundedCornerShape(if (style.drawsPeriodTiles) PERIOD_TILE_CORNER else 6.dp)
    val spoken = (listOf(club.clubName) + club.details).joinToString(", ")
    var showMenu by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .padding(1.dp)
            .clip(shape)
            // Over the grid's bands, as a course block: the fill alone is see-through.
            .background(MaterialTheme.colorScheme.background)
            .background(fill)
            .combinedClickable(
                onClick = onTap,
                hapticFeedbackEnabled = false,
                onLongClick = if (onPickColor == null) null else ({ onLongPress?.invoke(); showMenu = true }),
            )
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
            },
    ) {
        Box(Modifier.width(CLUB_BAR_WIDTH).fillMaxHeight().background(light))
        if (block.showsName) {
            ClassTableCourseNameText(
                text = club.clubName,
                color = style.courseTextColor,
                maxLines = if (block.showsLocation) 3 else 2,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(start = CLUB_BAR_WIDTH + 2.dp, end = 2.dp, bottom = if (block.showsLocation) 14.dp else 0.dp),
            )
        }
        if (block.showsLocation && club.location.isNotEmpty()) {
            Text(
                text = club.location,
                style = MaterialTheme.typography.labelSmall,
                color = style.courseSecondaryTextColor,
                fontSize = 10.sp * TigerDuckTheme.courseNameScale * 0.85f,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = CLUB_BAR_WIDTH + 3.dp, end = 3.dp, bottom = 2.dp),
            )
        }
        if (onPickColor != null) {
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, shape = RoundedCornerShape(12.dp)) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.class_table_club_color_picker_title)) },
                    leadingIcon = { Icon(Icons.Filled.Palette, contentDescription = null) },
                    onClick = { showMenu = false; onPickColor(club) },
                )
            }
        }
    }
}
