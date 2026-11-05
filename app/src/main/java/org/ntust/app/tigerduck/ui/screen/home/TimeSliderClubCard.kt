package org.ntust.app.tigerduck.ui.screen.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.clubs.ClubOccurrence
import org.ntust.app.tigerduck.ui.screen.classtable.clubs.clubTimeRange

/** The activity's name, else its club's: the card's headline. */
internal val ClubOccurrence.headline: String get() = name.ifEmpty { clubName }

/** Under the headline: the club's name, unless that is the headline, then the location. */
internal val ClubOccurrence.subtitle: String
    get() = listOf(if (name.isEmpty()) "" else clubName, location).filter { it.isNotEmpty() }.joinToString(" · ")

/**
 * A club's card on the time machine, [TimeSliderCardSurface] in the club's color: the activity's
 * name over its club, location and whole time, which [item] cut short on the track does not shorten.
 * As iOS `CourseTimeCard.clubSurface`.
 */
@Composable
internal fun ClubCard(item: TimeSliderItem.Club, muted: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val club = item.occurrence
    val time = clubTimeRange(club.start, club.end)
    val hint = stringResource(R.string.a11y_club_activity_open_details_hint)
    val spoken = (listOf(club.headline, club.subtitle, time, shownDateLabel(item.date).orEmpty()).filter { it.isNotEmpty() }).joinToString(", ")
    TimeSliderCardSurface(
        tint = item.tint,
        muted = muted,
        date = item.date,
        onClick = onClick,
        modifier = modifier.fillMaxWidth().fillBoundedHeight().clearAndSetSemantics {
            contentDescription = "$spoken. $hint"
            role = Role.Button
        },
        time = { ink -> Text(time, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = ink.meta) },
    ) { ink ->
        Text(
            club.headline,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = ink.name,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (club.subtitle.isNotEmpty()) {
            Text(club.subtitle, style = MaterialTheme.typography.labelSmall, color = ink.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
