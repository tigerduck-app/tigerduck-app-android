// The dialog a course row opens: the score page's own facts about the course
// in a header, then what the class table knows of it — instructor, enrolment,
// classroom, schedule — or a card saying it knows nothing, then the school's
// note. Mirrors iOS ScoreCourseDetailSheet.

package org.ntust.app.tigerduck.ui.screen.score

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.CourseGrade
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.component.TigerDuckDialog
import org.ntust.app.tigerduck.util.formatCredits

/** What the class table knows of a score row: not looked up yet, nothing, or its course. */
internal sealed interface RosterLookup {
    data object Pending : RosterLookup
    data object None : RosterLookup
    data class Found(val course: Course) : RosterLookup
}

@Composable
internal fun CourseDetailDialog(
    course: CourseGrade,
    /** The class-table course this row is, or null; see [ScoreViewModel.rosterCourse]. */
    lookupRoster: suspend (CourseGrade) -> Course?,
    onDismiss: () -> Unit,
) {
    // Looked up once per course shown. Until the cache answers, the roster
    // part is left out rather than shown as "no roster": the answer is a
    // file read away, and a course the cache does know must not flash the
    // card first.
    val roster by produceState<RosterLookup>(RosterLookup.Pending, course) {
        value = lookupRoster(course)?.let { RosterLookup.Found(it) } ?: RosterLookup.None
    }
    TigerDuckDialog(
        onDismissRequest = onDismiss,
        title = course.name,
        confirmText = stringResource(R.string.action_close),
        onConfirm = onDismiss,
        content = { CourseDetailDialogContent(course = course, roster = roster) },
    )
}

@Composable
internal fun CourseDetailDialogContent(course: CourseGrade, roster: RosterLookup) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HeaderRow(course)

        // The score page's rows beyond the header, then the class table's.
        // iOS dropped the former for the latter; the issue keeps both. Each
        // is left out when it has nothing to say, so the column is built
        // first and only drawn when there is one.
        val rows = buildList {
            creditTypeLabel(course.creditType)?.let {
                add(stringResource(R.string.score_info_credit_type) to it)
            }
            course.geDimension?.let {
                add(stringResource(R.string.score_info_general_dimension) to it)
            }
            if (course.distanceLearning) {
                add(
                    stringResource(R.string.score_info_teaching_method) to
                        stringResource(R.string.score_distance_learning)
                )
            }
            if (roster is RosterLookup.Found) {
                ScoreCourseRoster.rows(roster.course).forEach { (label, value) ->
                    add(stringResource(label) to value)
                }
            }
        }
        if (rows.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { (label, value) -> InfoRow(label, value) }
            }
        }
        if (roster == RosterLookup.None) NoRosterCard()

        if (course.remark.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.score_info_note),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = course.remark,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Code and "term · credits" on the left, the grade large on the right, as the iPhone's header card. */
@Composable
private fun HeaderRow(course: CourseGrade) {
    val (gradeLabel, gradeColor) = gradeDescriptor(course)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = course.code,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = ScoreCourseRoster.creditsMeta(
                    template = stringResource(R.string.score_course_credits_meta),
                    term = displayTerm(course.term),
                    credits = (course.credits ?: 0f).formatCredits(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = gradeLabel,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = gradeColor,
            maxLines = 1,
        )
    }
}

/** The card for a course the class-table cache has never held, as the iPhone shows it. */
@Composable
private fun NoRosterCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Decorative for TalkBack: the sentence beside it says it all.
        Icon(
            imageVector = Icons.Outlined.Inbox,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = stringResource(R.string.course_detail_no_roster),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A label/value row in the class-table detail's style: label left, value right, both wrapping. */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
    }
}
