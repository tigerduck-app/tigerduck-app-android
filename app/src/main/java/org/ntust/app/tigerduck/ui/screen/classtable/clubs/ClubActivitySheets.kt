// What a tap on a club's block opens, as on the iPhone: 選擇社團 when the block meets another
// club's, otherwise the activity's details (教室, 時間, 類型, then its text), built from the course
// detail's cards; and a club's color sheet, the course color sheet with the club's own labels.

package org.ntust.app.tigerduck.ui.screen.classtable.clubs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.clubs.ClubActivityKind
import org.ntust.app.tigerduck.clubs.ClubOccurrence
import org.ntust.app.tigerduck.ui.component.ColorPickerLabels
import org.ntust.app.tigerduck.ui.component.ColorPickerSheet
import org.ntust.app.tigerduck.ui.component.DetentSheet
import org.ntust.app.tigerduck.ui.component.colorPickerOffersDarkPalette
import org.ntust.app.tigerduck.ui.screen.classtable.EmphasisCard
import org.ntust.app.tigerduck.ui.screen.classtable.InfoRow
import org.ntust.app.tigerduck.ui.screen.classtable.SideBySideOrStacked
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.ui.theme.LocalVisualStylePolicy
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import org.ntust.app.tigerduck.ui.theme.courseColorPalette
import org.ntust.app.tigerduck.ui.theme.courseColorPaletteDark

/** `19:00 - 21:00`, as the iPhone's `ClassTimeline.timeRange`. */
internal fun clubTimeRange(start: Int, end: Int): String = "%02d:%02d - %02d:%02d".format(start / 60, start % 60, end / 60, end % 60)

/** The activity's name, time and location, as the picker's row and the block's spoken label give them. */
internal val ClubOccurrence.details: List<String>
    get() = listOf(name, clubTimeRange(start, end), location).filter { it.isNotEmpty() }

/** [rgb] as the club's color shows: the palette's dark variant where the class table draws one. */
@Composable
internal fun clubDisplayColor(rgb: Int): Color {
    val light = Color(0xFF000000.toInt() or rgb)
    val darkPalette = LocalVisualStylePolicy.current.colorPickerOffersDarkPalette(TigerDuckTheme.isDarkMode)
    val index = courseColorPalette.indexOfFirst { it.toArgb() == light.toArgb() }
    return if (darkPalette && index >= 0) courseColorPaletteDark[index] else light
}

/** 選擇社團: the activities a tapped block meets, highest priority first. */
@Composable
internal fun ClubPickerSheet(clubs: List<ClubOccurrence>, onPick: (ClubOccurrence) -> Unit, onDismiss: () -> Unit) {
    // At the largest text sizes the rows wrap rather than cut their details off.
    val wraps = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f
    DetentSheet(onDismissed = onDismiss, halfHeight = true) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.class_table_club_picker_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
            )
            Spacer(Modifier.height(4.dp))
            clubs.forEachIndexed { index, club ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                val spoken = (listOf(club.clubName) + club.details).joinToString(", ")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(club) }
                        .clearAndSetSemantics { contentDescription = spoken; role = Role.Button }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(clubDisplayColor(club.color)))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            club.clubName,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = if (wraps) Int.MAX_VALUE else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            club.details.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                            maxLines = if (wraps) Int.MAX_VALUE else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** An activity's details: the club and the activity's name over its color, 教室 and 時間, 類型, then its text. */
@Composable
internal fun ClubActivityDetailSheet(club: ClubOccurrence, onDismiss: () -> Unit) {
    DetentSheet(onDismissed = onDismiss, halfHeight = true, containerColor = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    club.clubName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
                if (club.name.isNotEmpty()) {
                    Text(club.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(clubDisplayColor(club.color)))
            }
            SideBySideOrStacked(gap = 12.dp) {
                EmphasisCard(stringResource(R.string.class_table_club_location_label), club.location.ifEmpty { "—" })
                EmphasisCard(stringResource(R.string.class_table_club_time_label), clubTimeRange(club.start, club.end))
            }
            InfoRow(stringResource(R.string.class_table_club_activity_type_label), kindText(club.kind))
            if (club.content.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(club.content, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun kindText(kind: ClubActivityKind): String = when (kind) {
    ClubActivityKind.Meeting -> stringResource(R.string.class_table_club_activity_type_meeting)
    ClubActivityKind.Event -> stringResource(R.string.class_table_club_activity_type_event)
    is ClubActivityKind.Other -> kind.name
}

/**
 * 編輯顏色: the course color sheet with the club's labels. A preset is kept as the palette's light
 * hex, so a change of theme shows its pair, and a color of the user's own as it is; [onApply]
 * gets `0xRRGGBB`.
 */
@Composable
internal fun ClubColorSheet(name: String, color: Int, onApply: (Int) -> Unit, onDismiss: () -> Unit) {
    val darkPalette = LocalVisualStylePolicy.current.colorPickerOffersDarkPalette(TigerDuckTheme.isDarkMode)
    val palette = if (darkPalette) courseColorPaletteDark else courseColorPalette
    ColorPickerSheet(
        courseName = name,
        currentColor = clubDisplayColor(color),
        presetPalette = palette,
        usedByOthers = emptySet(),
        onApply = { picked ->
            val argb = picked.toArgb() or 0xFF000000.toInt()
            val preset = palette.indexOfFirst { (it.toArgb() or 0xFF000000.toInt()) == argb }
            onApply((if (preset >= 0) courseColorPalette[preset].toArgb() else argb) and 0xFFFFFF)
        },
        onDismiss = onDismiss,
        labels = ColorPickerLabels.Club,
    )
}
