package org.ntust.app.tigerduck.ui.screen.coursemoodle

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.flowOf
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.AssignmentStatus
import org.ntust.app.tigerduck.data.moodle.MoodleAssignmentEntry
import org.ntust.app.tigerduck.data.moodle.MoodleAssignmentLists
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.ui.component.rememberAppClockVersion
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

/**
 * A course's assignments as the last assignment round recorded them: those still to come by due
 * time, then the ones with no due date, then the past ones, the latest first, each with what
 * Moodle says of it on the right. A row opens the assignment's page.
 */
@Composable
fun MoodleAssignmentsScreen(
    onBack: () -> Unit,
    onOpenRoute: (String) -> Unit,
    viewModel: MoodleCourseViewModel = hiltViewModel(),
) {
    val store by viewModel.store.collectAsStateWithLifecycle()
    MoodleSignedOutEffect(viewModel, onBack)
    val assignments by viewModel.assignments.collectAsStateWithLifecycle()
    val current = store
    val entries by remember(assignments, current?.courseId) {
        current?.let { assignments?.entries(it.courseId) } ?: flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    // The rows read the app's clock, so the debug clock applies, and move with the minute.
    val clockVersion by rememberAppClockVersion()
    val rows = remember(entries, clockVersion) { MoodleAssignmentLists.rows(entries, AppClock.nowMillis()) }

    Scaffold(
        topBar = {
            MoodleTopBar(
                title = stringResource(R.string.course_detail_assignments_label),
                onBack = onBack,
                moodlePath = current?.let { MoodlePage.assignments(it.courseId) },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (current == null) {
                MoodleFirstLoad(failure = null, onRetry = {})
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    item(key = "assignments") {
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                            Column {
                                rows.fastForEachIndexed { index, row ->
                                    if (index > 0) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = 16.dp),
                                            color = cardDividerColor(),
                                        )
                                    }
                                    AssignmentListRow(row) {
                                        onOpenRoute(MoodleRoutes.assignment(current.courseId, row.entry.assignmentId))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One assignment of the list: its name, what Moodle says of the submission, and its due time. */
@Composable
internal fun AssignmentListRow(row: MoodleAssignmentLists.Row, onClick: () -> Unit) {
    val entry: MoodleAssignmentEntry = row.entry
    // The student's own date, an extension included, as the list is ordered by.
    val due = entry.studentDueSeconds.takeIf { it > 0 }?.let(MoodleDates::minute)
    val status = row.status?.let { statusLabel(it) }
    ListItem(
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        headlineContent = { Text(entry.record.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    status?.let { (label, color) ->
                        Text(text = label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = color)
                    }
                    due?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                        )
                    }
                }
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.DISABLED),
                )
            }
        },
    )
}

/** Home's badge words and tints for what Moodle says of a submission; see `AssignmentItem`. */
@Composable
private fun statusLabel(status: AssignmentStatus): Pair<String, Color> = when (status) {
    AssignmentStatus.SUBMITTED -> stringResource(R.string.assignment_status_submitted) to Color(0xFF34C759)
    AssignmentStatus.SUBMITTED_LATE -> stringResource(R.string.assignment_status_submitted_late) to Color(0xFFFF9500)
    AssignmentStatus.OVERDUE_ACCEPTABLE, AssignmentStatus.OVERDUE_REJECTED ->
        stringResource(R.string.assignment_status_overdue) to Color(0xFFFF3B30)
    AssignmentStatus.PENDING -> "" to Color.Unspecified
}
