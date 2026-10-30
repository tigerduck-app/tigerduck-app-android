// The course detail a course cell opens, the page of its sheet (see
// CourseDetailSheet). The Moodle screens it leads to open full screen over the
// sheet's page, and back returns to the sheet. Its row and card primitives, and
// the Moodle hand-off, live here too.

package org.ntust.app.tigerduck.ui.screen.classtable

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.coursechanges.ClassSlot
import org.ntust.app.tigerduck.coursechanges.CourseChangeRow
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.component.SheetContentScroll
import org.ntust.app.tigerduck.ui.component.WholeWordsText
import org.ntust.app.tigerduck.ui.component.courseChangeInk
import org.ntust.app.tigerduck.ui.haptics.HapticScenario
import org.ntust.app.tigerduck.ui.haptics.Haptics
import org.ntust.app.tigerduck.ui.screen.coursemoodle.MoodleCourseSection
import org.ntust.app.tigerduck.ui.screen.coursemoodle.MoodleCourseViewModel
import org.ntust.app.tigerduck.ui.screen.coursemoodle.MoodlePage
import org.ntust.app.tigerduck.ui.screen.coursemoodle.MoodleRoutes
import org.ntust.app.tigerduck.ui.screen.coursemoodle.cardDividerColor
import org.ntust.app.tigerduck.ui.screen.coursemoodle.openMoodlePage
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.ui.theme.LocalVisualStylePolicy
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import org.ntust.app.tigerduck.util.formatCredits
import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The class table's selected course, in full, as its sheet's page:
 *   1. Title, rename and "Open in Moodle", with the course colour under the title
 *   2. Two emphasis cards side by side: classroom | time
 *   3. The info rows: instructor, code (tap to copy), dimension, duration, credits, enrollment
 *   4. Incomplete assignments, each opening its page full screen, then the course's Moodle
 *      section, whose rows open the Moodle screens full screen
 *
 * A course on someone else's class table shows read-only: no rename, and none of the Moodle
 * course or assignments, which are the signed-in student's. One opened from Home is the user's own
 * whatever the class table shows. The sheet closes when there is no
 * selected course to show, as after the account signed out or the class table switched.
 */
@Composable
internal fun CourseDetailSheetPage(
    viewModel: ClassTableViewModel,
    browserPreference: String,
    onOpenRoute: (String) -> Unit,
    /** Kept by the sheet's page, so the sheet comes back from a page it opened as far down as it was. */
    scrollState: ScrollState = rememberScrollState(),
    moodleViewModel: MoodleCourseViewModel = hiltViewModel(),
) {
    val selected by viewModel.selectedCourse.collectAsStateWithLifecycle()
    val courses by viewModel.courses.collectAsStateWithLifecycle()
    val liveCourses by viewModel.liveSemesterCourses.collectAsStateWithLifecycle()
    val outstanding by viewModel.outstandingAssignments.collectAsStateWithLifecycle()
    val moodleIds by viewModel.moodleCourseIdMap.collectAsStateWithLifecycle()
    val shown by viewModel.shownTable.collectAsStateWithLifecycle()
    val readOnly = viewModel.selectedCourseIsSomeoneElses
    val showsMoodle = !readOnly
    // Every edit of the user's own class table waits while someone else's shows, a rename of a
    // course opened from Home included.
    val canRename = !readOnly && shown.person == null

    // The course as it is now, so a rename made here shows at once. Someone else's from their
    // class table, not the user's copy of a course both take.
    val course = ClassTableSelection.detailCourse(
        selected,
        if (readOnly) shown.courses else courses,
        liveCourses,
        viewModel.selectedFromAnotherTerm,
    ) ?: return

    val context = LocalContext.current
    val title = viewModel.detailTitle(course)
    val classroom = viewModel.selectedCourseClassroom
    val timeRange = viewModel.selectedCourseTimeRange
    // The school's changes are read once, as the detail opens, as on iOS.
    val meeting = remember(course) { viewModel.selectedMeeting }
    val cancelledLabel = stringResource(R.string.course_card_cancelled)
    val locale = LocalConfiguration.current.locales[0]
    val changeRows = remember(course, meeting, cancelledLabel, locale) {
        viewModel.selectedChangeRows(meeting, cancelledLabel, locale)
    }
    val assignments = remember(outstanding, course.courseNo, readOnly) {
        if (readOnly) emptyList() else outstanding.filter { it.courseNo == course.courseNo }
    }
    val moodleCourseId = remember(course.courseNo, moodleIds) { viewModel.moodleCourseIdFor(course) }
    LaunchedEffect(moodleCourseId, showsMoodle) {
        moodleViewModel.bind(moodleCourseId.takeIf { showsMoodle }, course.courseNo)
    }
    val store by moodleViewModel.store.collectAsStateWithLifecycle()
    var renaming by rememberSaveable { mutableStateOf(false) }

    val sheetScroll = remember(scrollState) { SheetContentScroll(scrollState) }
    // Centred and held to a readable width on a tablet or in landscape.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(sheetScroll.modifier)
            .verticalScroll(scrollState),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            CourseDetailContent(
                course = course,
                title = title,
                classroom = classroom,
                timeRange = timeRange,
                assignments = assignments,
                onRename = if (canRename) ({ renaming = true }) else null,
                onOpenInMoodle = moodleCourseId?.takeIf { showsMoodle }?.let { id -> { openCourseInMoodle(context, id) } },
                onOpenAssignment = { onOpenRoute(MoodleRoutes.assignment(moodleCourseId ?: 0, it.assignmentId)) },
                meeting = meeting,
                changeRows = changeRows,
            )
            val current = store
            if (showsMoodle && moodleCourseId != null && current != null && current.courseId == moodleCourseId) {
                MoodleCourseSection(current, browserPreference, onOpenRoute)
            }
        }
    }

    if (renaming && canRename) {
        CourseRenameDialog(viewModel = viewModel, course = course, onDismiss = { renaming = false })
    }
}

/** Everything above the Moodle section. */
@Composable
internal fun CourseDetailContent(
    course: Course,
    title: String,
    classroom: String,
    timeRange: String?,
    assignments: List<Assignment>,
    /** Null on someone else's class table, which offers no rename. */
    onRename: (() -> Unit)?,
    /** Null for a course with no Moodle course of the user's. */
    onOpenInMoodle: (() -> Unit)?,
    /** Opens an incomplete assignment's page, full screen. */
    onOpenAssignment: (Assignment) -> Unit = {},
    /** The shown day's meeting as the school changed it, which the cards show; null for the timetable's. */
    meeting: ClassSlot? = null,
    /** The 課程異動 list's days; none for a detail opened without a date. */
    changeRows: List<CourseChangeRow> = emptyList(),
) {
    val courseColor = LocalVisualStylePolicy.current.courseAccentColor(
        courseColor = TigerDuckTheme.courseColorVibrant(course.courseNo),
        tint = TigerDuckTheme.courseColor(course.courseNo),
    )
    val dash = "—"
    val ink = courseChangeInk()
    val cancelledLabel = stringResource(R.string.course_card_cancelled)
    // The shown day's change takes the card's value, in the change ink, as on iOS.
    val movedRoom = meeting?.takeIf { it.hasChangedRoom }?.room
    val changedTime = when {
        meeting == null -> null
        meeting.isCancelled -> cancelledLabel
        meeting.hasChangedTime -> "${hourMinute(meeting.start)} - ${hourMinute(meeting.end)}"
        else -> null
    }
    val classroomValue = movedRoom ?: classroom.trim().ifEmpty { dash }
    val timeValue = changedTime ?: timeRange?.takeIf { it.isNotBlank() } ?: dash

    // The course code is what students paste into 加退選, the portal search
    // and group chats, so the row that shows it also hands it over.
    val context = LocalContext.current
    val view = LocalView.current
    val codeCopiedMessage = stringResource(R.string.course_detail_code_copied)
    var copyCount by remember { mutableIntStateOf(0) }
    var codeCopied by remember { mutableStateOf(false) }
    // Keyed on the count, so a second copy inside the window restarts the
    // timer instead of having the first one clear its checkmark early.
    LaunchedEffect(copyCount) {
        if (copyCount == 0) return@LaunchedEffect
        codeCopied = true
        delay(1_500)
        codeCopied = false
    }
    val copyCourseCode: () -> Unit = {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("course_no", course.courseNo))
        // Through the app's own scenario, like every other haptic here, so
        // the Settings → Vibration sliders govern it too.
        Haptics.perform(context, HapticScenario.CourseCodeCopy)
        // The checkmark and the haptic are both invisible to TalkBack.
        @Suppress("DEPRECATION")
        view.announceForAccessibility(codeCopiedMessage)
        copyCount++
    }

    // Title with a course-coloured accent bar under it, so the cards below
    // read as belonging to *this* course at a glance.
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            if (onRename != null) {
                IconButton(onClick = onRename) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.class_table_rename_title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onOpenInMoodle != null) {
                IconButton(onClick = onOpenInMoodle) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = stringResource(R.string.a11y_course_detail_open_moodle),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(courseColor),
        )
    }

    SideBySideOrStacked(gap = 12.dp) {
        EmphasisCard(
            label = stringResource(R.string.course_detail_classroom_label),
            value = classroomValue,
            valueColor = if (movedRoom != null) ink else null,
        )
        EmphasisCard(
            label = stringResource(R.string.course_detail_time_label),
            value = timeValue,
            valueColor = if (changedTime != null) ink else null,
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        InfoRow(
            label = stringResource(R.string.course_detail_instructor_label),
            value = course.instructor.ifBlank { dash },
        )
        InfoRow(
            label = stringResource(R.string.course_detail_code_label),
            value = course.courseNo,
            copied = codeCopied,
            onCopy = copyCourseCode,
        )
        CourseDetailMetadata.dimension(course.dimension)?.let {
            InfoRow(
                label = stringResource(R.string.course_detail_dimension_label),
                value = it,
            )
        }
        CourseDetailMetadata.durationLabel(course.allYear)?.let {
            InfoRow(
                label = stringResource(R.string.course_detail_duration_label),
                value = stringResource(it),
            )
        }
        // Required or elective, between the duration and the credits as on the iPhone.
        CourseDetailMetadata.requireOptionLabel(course.requireOption)?.let {
            InfoRow(
                label = stringResource(R.string.course_detail_require_option_label),
                value = stringResource(it),
            )
        }
        InfoRow(
            label = stringResource(R.string.course_detail_credits_label),
            value = course.credits.formatCredits(),
        )
        InfoRow(
            label = stringResource(R.string.course_detail_enrollment_label),
            value = "${course.enrolledCount} / ${course.maxCount}",
        )
    }

    if (changeRows.isNotEmpty()) {
        CourseChangesSection(changeRows)
    }

    if (assignments.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = stringResource(R.string.course_detail_incomplete_assignments),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                Column {
                    assignments.forEachIndexed { index, assignment ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 56.dp),
                                color = cardDividerColor(),
                            )
                        }
                        AssignmentRow(assignment) { onOpenAssignment(assignment) }
                    }
                }
            }
        }
    }
}

/**
 * 課程異動: the course's changes on the days ahead, a line each, the day as `M/d (EEEEE)`. As the
 * iPhone's detail sheet.
 */
@Composable
private fun CourseChangesSection(rows: List<CourseChangeRow>) {
    val locale = LocalConfiguration.current.locales[0]
    val dayFormat = remember(locale) { DateTimeFormatter.ofPattern("M/d (EEEEE)", locale) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = stringResource(R.string.course_detail_changes),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = row.day.format(dayFormat),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = row.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun hourMinute(time: java.time.LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

/** An outstanding assignment: its title and due time, opening its page. */
@Composable
private fun AssignmentRow(assignment: Assignment, onClick: () -> Unit) {
    val due = remember(assignment.studentDueDate) {
        SimpleDateFormat("M/d HH:mm", Locale.getDefault()).apply { timeZone = org.ntust.app.tigerduck.AppConstants.TAIPEI_TZ }
            .format(assignment.studentDueDate)
    }
    ListItem(
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        leadingContent = {
            Icon(Icons.Outlined.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        headlineContent = { Text(assignment.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                text = stringResource(R.string.course_detail_due_prefix, due),
                color = if (assignment.isOverdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.DISABLED),
            )
        },
    )
}

/**
 * Big-text card used for the two fields that earn visual emphasis
 * (classroom and time). Label sits small at the top-start; the value
 * renders large and centered so paired cards read as a single
 * at-a-glance unit. On the page's background in the cards' `surface`,
 * as every grouped card in the app is drawn.
 */
@Composable
private fun EmphasisCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    /** The value's color when it is not the usual one, as a school's change. */
    valueColor: Color? = null,
) {
    // titleMedium (not titleLarge) so longer time strings like
    // "09:10 - 12:00" fit on one line without auto-shrinking, and both
    // cards in the pair use the same size for visual symmetry.
    val valueStyle = MaterialTheme.typography.titleMedium.copy(
        fontWeight = FontWeight.SemiBold,
    )
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            WholeWordsText(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // weight(1f) on the value Box lets side-by-side cards share
            // the taller card's height (see SideBySideOrStacked) by
            // letting this Box absorb the extra vertical space,
            // vertically centering the value inside it.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = value,
                    style = valueStyle,
                    color = valueColor ?: MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Its cards in a row, sharing the width and the height of the taller, while each card's longest
 * word fits its share. At the app's largest sizes "Classroom" no longer does, and used to break
 * as "Classroo" / "m", so the cards then go one under the other, each across the whole width.
 */
@Composable
private fun SideBySideOrStacked(gap: Dp, content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val gaps = gapPx * (measurables.size - 1)
        // Unbounded, as an intrinsic measurement asks: each card as wide as its longest word.
        val share = if (constraints.hasBoundedWidth) {
            (constraints.maxWidth - gaps) / measurables.size
        } else {
            measurables.maxOf { it.minIntrinsicWidth(Constraints.Infinity) }
        }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else share * measurables.size + gaps
        if (measurables.all { it.minIntrinsicWidth(Constraints.Infinity) <= share }) {
            // The last card takes what flooring the shares left over.
            val widths = measurables.indices.map { if (it == measurables.lastIndex) width - gaps - share * it else share }
            val height = measurables.indices.maxOf { measurables[it].minIntrinsicHeight(widths[it]) }
            val placeables = measurables.indices.map { measurables[it].measure(Constraints.fixed(widths[it], height)) }
            layout(width, height) {
                var x = 0
                placeables.forEach {
                    it.placeRelative(x, 0)
                    x += it.width + gapPx
                }
            }
        } else {
            val placeables = measurables.map { it.measure(Constraints.fixed(width, it.minIntrinsicHeight(width))) }
            layout(width, placeables.sumOf { it.height } + gaps) {
                var y = 0
                placeables.forEach {
                    it.placeRelative(0, y)
                    y += it.height + gapPx
                }
            }
        }
    }
}

/**
 * Open course id [moodleCourseId] as every "Open in Moodle" opens a page,
 * through [openMoodlePage]: in the Moodle app where it is installed and the
 * Open Moodle in setting asks for it, else in the TigerDuck browser.
 */
internal fun openCourseInMoodle(context: Context, moodleCourseId: Int) {
    openMoodlePage(context, MoodlePage.course(moodleCourseId))
}

/**
 * A label/value row. A non-null [onCopy] makes the whole row a copy button,
 * marked by a glyph in front of the value — the value is what gets copied, so
 * the glyph reads as a marker on it rather than as a separate control. It
 * turns into a checkmark while [copied].
 */
@Composable
private fun InfoRow(
    label: String,
    value: String,
    copied: Boolean = false,
    onCopy: (() -> Unit)? = null,
) {
    val rowModifier = if (onCopy != null) {
        Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = stringResource(R.string.course_detail_copy_code),
                role = Role.Button,
                onClick = onCopy,
            )
    } else {
        Modifier.fillMaxWidth()
    }
    Row(
        modifier = rowModifier,
        verticalAlignment = if (onCopy != null) Alignment.CenterVertically else Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        if (onCopy != null) {
            Icon(
                imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                contentDescription = null,
                tint = if (copied) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(16.dp),
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
    }
}
