package org.ntust.app.tigerduck.ui.screen.coursemoodle

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Comment
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.data.moodle.MoodleCourseStore
import org.ntust.app.tigerduck.data.moodle.MoodleGrades
import org.ntust.app.tigerduck.network.model.moodle.MoodleGradeItem
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

/**
 * A course's grades on Moodle for the signed-in student: the course total, then each graded item
 * in the gradebook's order. An item with feedback opens in place to show it, and a long press
 * opens the item's activity in Moodle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodleGradesScreen(
    browserPreference: String,
    onBack: () -> Unit,
    viewModel: MoodleCourseViewModel = hiltViewModel(),
) {
    val store by viewModel.store.collectAsStateWithLifecycle()
    MoodleSignedOutEffect(viewModel, onBack)
    val current = store
    Scaffold(
        topBar = {
            MoodleTopBar(
                title = stringResource(R.string.course_detail_moodle_grades_label),
                onBack = onBack,
                moodlePath = current?.let { MoodlePage.gradeReport(it.courseId) },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (current == null) MoodleFirstLoad(null, {}) else GradesList(current, browserPreference)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GradesList(store: MoodleCourseStore, browserPreference: String) {
    val state by store.grades.state.collectAsStateWithLifecycle()
    MoodleAutoRefresh(store, store.grades.ttlMs, store::awaitCache, { store.grades.state.value.fetchedAtMs }) {
        store.refreshGrades()
    }
    var pulling by remember { mutableStateOf(false) }
    val refresher = rememberRefresher { store.refreshGrades() }
    val scope = rememberCoroutineScope()
    var expanded by rememberSaveable { mutableStateOf(setOf<Int>()) }

    PullToRefreshBox(
        isRefreshing = pulling,
        onRefresh = { pulling = true; refresher { pulling = false } },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val grades = state.value
            if (grades == null) {
                item {
                    MoodleFirstLoad(
                        failure = if (state.isRefreshing) null else state.failure,
                        onRetry = { scope.launch { store.refreshGrades() } },
                    )
                }
            } else {
                state.failureBesideCopy?.let { failure ->
                    item(key = "failure") { MoodleRefreshFailure(failure, onRetry = { scope.launch { store.refreshGrades() } }) }
                }
                val total = MoodleGrades.total(grades)
                val items = MoodleGrades.graded(grades)
                total?.let { item(key = "total") { GradeTotalCard(it) } }
                if (items.isNotEmpty()) {
                    item(key = "items") {
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                            Column {
                                items.forEachIndexed { index, gradeItem ->
                                    if (index > 0) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = 56.dp),
                                            color = cardDividerColor(),
                                        )
                                    }
                                    GradeRow(
                                        item = gradeItem,
                                        expanded = gradeItem.id in expanded,
                                        onToggle = {
                                            expanded = if (gradeItem.id in expanded) expanded - gradeItem.id else expanded + gradeItem.id
                                        },
                                        browserPreference = browserPreference,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The course total, styled as the course detail's classroom and time cards. */
@Composable
private fun GradeTotalCard(total: MoodleGradeItem) {
    val label = stringResource(R.string.score_credit_total)
    val spoken = spokenGrade(total)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = label
                stateDescription = spoken
            },
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = MoodleGrades.shownGrade(total).orEmpty(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                MoodleGrades.maxText(total)?.let {
                    Text(
                        text = "/ $it",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                    )
                }
            }
        }
    }
}

/**
 * A graded item: its kind's icon, its name, and its grade over the most it could be. An item with
 * feedback is a button that shows the feedback under it; the rest cannot be tapped. Long-pressed,
 * an item an activity gives opens that activity in Moodle.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GradeRow(item: MoodleGradeItem, expanded: Boolean, onToggle: () -> Unit, browserPreference: String) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val activity = MoodleGrades.activityPath(item)
    val hasFeedback = item.feedback.isNotEmpty()
    val openInMoodle = stringResource(R.string.action_open_in_moodle)
    val spoken = spokenGrade(item)
    val state = if (!hasFeedback) spoken else {
        "$spoken, " + stringResource(if (expanded) R.string.course_moodle_grade_expanded else R.string.course_moodle_grade_collapsed)
    }
    Box {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .combinedClickable(
                        enabled = hasFeedback || activity != null,
                        role = Role.Button,
                        onClickLabel = null,
                        onLongClickLabel = if (activity != null) openInMoodle else null,
                        onLongClick = if (activity != null) ({ menu = true }) else null,
                        onClick = { if (hasFeedback) onToggle() },
                    )
                    .clearAndSetSemantics {
                        contentDescription = item.name
                        stateDescription = state
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = gradeIcon(item),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Box(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                    )
                    if (hasFeedback) {
                        Icon(
                            Icons.AutoMirrored.Outlined.Comment,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Box(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = MoodleGrades.shownGrade(item).orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    MoodleGrades.maxText(item)?.let {
                        Text(
                            text = "/ $it",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = expanded && hasFeedback,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                MoodleMarkdown(
                    markdown = item.feedback,
                    browserPreference = browserPreference,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 56.dp, end = 16.dp, bottom = 12.dp),
                    compact = true,
                )
            }
        }
        if (activity != null) {
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(openInMoodle) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                    onClick = {
                        menu = false
                        openMoodlePage(context, activity)
                    },
                )
            }
        }
    }
}

private fun gradeIcon(item: MoodleGradeItem): ImageVector = when (item.module) {
    "assign" -> Icons.Outlined.Description
    "quiz" -> Icons.Outlined.Checklist
    else -> Icons.Outlined.Numbers
}

/** "95, out of 100" for a number, the grade alone otherwise. */
@Composable
private fun spokenGrade(item: MoodleGradeItem): String {
    val grade = MoodleGrades.shownGrade(item) ?: return ""
    val max = MoodleGrades.maxText(item) ?: return grade
    return "$grade, " + stringResource(R.string.course_moodle_grade_out_of, max)
}
