package org.ntust.app.tigerduck.ui.screen.coursemoodle

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.Badge
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.moodle.MoodleCourseStore
import org.ntust.app.tigerduck.data.moodle.MoodleSectionRow
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseSummary
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import java.text.NumberFormat

/**
 * The course detail's Moodle section, under its incomplete assignments: a header, one card with
 * a row for each kind of data the course has on Moodle, then the course's summary. A row shows
 * only once its data has something in it, and the section only once it has a row, a summary or
 * a failed fetch to say why, with Retry. Its data stays fresh while the course detail shows.
 */
@Composable
internal fun MoodleCourseSection(
    store: MoodleCourseStore,
    browserPreference: String,
    onOpenRoute: (String) -> Unit,
) {
    MoodleSectionRefresh(store)
    val rows by store.sectionRows.collectAsStateWithLifecycle()
    val summary by store.shownSummary.collectAsStateWithLifecycle()
    val failure by store.sectionFailure.collectAsStateWithLifecycle()
    AnimatedVisibility(
        visible = rows.isNotEmpty() || summary != null || failure != null,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = stringResource(R.string.calendar_source_moodle),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            failure?.let { MoodleRefreshFailure(it, onRetry = store::retrySection) }
            if (rows.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.animateContentSize(),
                ) {
                    Column {
                        rows.forEachIndexed { index, row ->
                            if (index > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 56.dp),
                                    color = cardDividerColor(),
                                )
                            }
                            MoodleSectionRowItem(row) { onOpenRoute(route(row.kind, store)) }
                        }
                    }
                }
            }
            summary?.let { MoodleCourseSummaryView(it, browserPreference) }
        }
    }
}

/** The Forums row opens the course's one forum itself, and the list of them otherwise. */
internal fun route(kind: MoodleSectionRow.Kind, store: MoodleCourseStore): String = when (kind) {
    MoodleSectionRow.Kind.ANNOUNCEMENTS -> MoodleRoutes.announcements(store.courseId)
    MoodleSectionRow.Kind.MATERIALS -> MoodleRoutes.materials(store.courseId)
    MoodleSectionRow.Kind.ASSIGNMENTS -> MoodleRoutes.assignments(store.courseId)
    MoodleSectionRow.Kind.GRADES -> MoodleRoutes.grades(store.courseId)
    MoodleSectionRow.Kind.MEMBERS -> MoodleRoutes.members(store.courseId)
    MoodleSectionRow.Kind.FORUMS -> store.generalForums.value.singleOrNull()
        ?.let { MoodleRoutes.forum(store.courseId, it.id) }
        ?: MoodleRoutes.forums(store.courseId)
}

/** Keeps the section's data fresh while the course detail shows, rows or none: the first fetch brings them. */
@Composable
private fun MoodleSectionRefresh(store: MoodleCourseStore) {
    MoodleAutoRefresh(store, store.forums.ttlMs, store::awaitCache, { store.forumsFetchedAtMs }) { store.refreshForums() }
    MoodleAutoRefresh(store, store.materials.ttlMs, store::awaitCache, { store.materials.state.value.fetchedAtMs }) { store.refreshMaterials() }
    MoodleAutoRefresh(store, store.grades.ttlMs, store::awaitCache, { store.grades.state.value.fetchedAtMs }) { store.refreshGrades() }
    MoodleAutoRefresh(store, store.members.ttlMs, store::awaitCache, { store.members.state.value.fetchedAtMs }) { store.refreshMembers() }
    MoodleAutoRefresh(store, store.summary.ttlMs, store::awaitCache, { store.summary.state.value.fetchedAtMs }) { store.refreshSummary() }
}

@Composable
private fun MoodleSectionRowItem(row: MoodleSectionRow, onClick: () -> Unit) {
    val (icon, title) = when (row.kind) {
        MoodleSectionRow.Kind.ANNOUNCEMENTS -> Icons.Outlined.Campaign to stringResource(R.string.feature_announcements)
        MoodleSectionRow.Kind.MATERIALS -> Icons.Outlined.Folder to stringResource(R.string.course_detail_files_label)
        MoodleSectionRow.Kind.ASSIGNMENTS ->
            Icons.AutoMirrored.Outlined.Assignment to stringResource(R.string.course_detail_assignments_label)
        MoodleSectionRow.Kind.GRADES -> Icons.Outlined.BarChart to stringResource(R.string.course_detail_moodle_grades_label)
        MoodleSectionRow.Kind.MEMBERS -> Icons.Outlined.Groups to stringResource(R.string.course_detail_members_label)
        MoodleSectionRow.Kind.FORUMS -> Icons.AutoMirrored.Outlined.Chat to stringResource(R.string.course_detail_forums_label)
    }
    MoodleCountRow(icon = icon, title = title, count = row.count, hasUnread = row.hasUnread, onClick = onClick)
}

/** A row that opens a list: icon, title, unread dot, count and chevron. */
@Composable
internal fun MoodleCountRow(
    icon: ImageVector,
    title: String,
    count: Int,
    hasUnread: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
) {
    val countText = remember(count) { NumberFormat.getIntegerInstance().format(count) }
    val unreadLabel = stringResource(R.string.bulletin_unread_dot_label)
    ListItem(
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = listOfNotNull(title, subtitle).joinToString(", ")
                stateDescription = if (hasUnread) "$countText, $unreadLabel" else countText
            },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        leadingContent = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasUnread) Badge(containerColor = MaterialTheme.colorScheme.primary)
                Text(
                    text = countText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                )
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.DISABLED),
                )
            }
        },
    )
}

/**
 * A course's summary on Moodle: four lines of plain text that open in place to the whole
 * summary, links working. The open summary keeps its links tappable, so the chevron under it,
 * not the text, closes it again.
 */
@Composable
private fun MoodleCourseSummaryView(summary: MoodleCourseSummary, browserPreference: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val label = stringResource(R.string.course_detail_summary_label)
    val expandedText = stringResource(R.string.course_moodle_grade_expanded)
    val collapsedText = stringResource(R.string.course_moodle_grade_collapsed)
    Column(
        modifier = Modifier
            .padding(top = 8.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        if (expanded) {
            rememberMoodleMarkdown(summary.html)?.let {
                MoodleMarkdown(it, browserPreference, Modifier.fillMaxWidth(), compact = true)
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                IconButton(
                    onClick = { expanded = false },
                    modifier = Modifier.semantics { stateDescription = expandedText },
                ) {
                    Icon(Icons.Filled.ExpandLess, contentDescription = label)
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { expanded = true }
                    .semantics(mergeDescendants = true) { stateDescription = collapsedText },
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = summary.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
