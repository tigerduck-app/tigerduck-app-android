// The detail dialog a course cell opens, plus its row/card primitives
// and the Moodle hand-off.

package org.ntust.app.tigerduck.ui.screen.classtable

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.component.isEnglishUiLanguage
import org.ntust.app.tigerduck.ui.component.middleEllipsize
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import org.ntust.app.tigerduck.ui.theme.courseColorPalette
import org.ntust.app.tigerduck.ui.theme.courseColorPaletteDark

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CourseDetailDialog(
    course: Course,
    title: String,
    classroom: String,
    timeRange: String?,
    assignments: List<org.ntust.app.tigerduck.data.model.Assignment>,
    moodleCourseId: Int?,
    onRename: () -> Unit,
    onOpenInMoodle: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val courseColor = TigerDuckTheme.courseColor(course.courseNo)
    val dash = "—"
    val classroomValue = classroom.trim().ifEmpty { dash }
    val timeValue = timeRange?.takeIf { it.isNotBlank() } ?: dash

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
            modifier = Modifier
                .widthIn(min = 280.dp, max = 560.dp)
                .padding(horizontal = 24.dp),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // Header: title + rename pencil, with a course-colored
                // accent bar pinned underneath so the two cards below read
                // as belonging to *this* course at a glance.
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = title,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        IconButton(onClick = onRename) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = stringResource(R.string.class_table_rename_title),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (moodleCourseId != null) {
                            IconButton(onClick = { onOpenInMoodle(moodleCourseId) }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = null,
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

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.height(IntrinsicSize.Min),
                ) {
                    EmphasisCard(
                        label = stringResource(R.string.course_detail_classroom_label),
                        value = classroomValue,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    EmphasisCard(
                        label = stringResource(R.string.course_detail_time_label),
                        value = timeValue,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
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
                    )
                    InfoRow(
                        label = stringResource(R.string.course_detail_credits_label),
                        value = course.credits.toString(),
                    )
                    InfoRow(
                        label = stringResource(R.string.course_detail_enrollment_label),
                        value = "${course.enrolledCount} / ${course.maxCount}",
                    )
                }

                if (assignments.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = stringResource(R.string.course_detail_incomplete_assignments),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        assignments.forEach { assignment ->
                            Row(verticalAlignment = Alignment.Top) {
                                Text(
                                    text = "•",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                                Text(
                                    text = assignment.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            }
        }
    }
}

/**
 * Big-text card used for the two fields that earn visual emphasis
 * (classroom and time). Label sits small at the top-start; the value
 * renders large and centered so paired cards read as a single
 * at-a-glance unit. Background uses `surfaceContainerHighest` because
 * the host dialog already renders `surface` + 6dp tonal elevation —
 * which matches `surfaceContainerHigh` — so anything lower than
 * `surfaceContainerHighest` would blend into the dialog with no
 * visible contrast.
 */
@Composable
private fun EmphasisCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
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
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // weight(1f) on the value Box lets sibling cards in an
            // IntrinsicSize.Min row equalize on the taller card's
            // intrinsic height by letting this Box absorb the extra
            // vertical space, vertically centering the value inside it.
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
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Open course id [moodleCourseId] in the Moodle Mobile app via the
 * `moodlemobile://` deep link envelope, falling back to the browser if
 * Moodle Mobile isn't installed or the OS refuses to route the intent.
 * Mirrors the assignment-row pattern in [HomeScreen.openAssignmentInMoodle]
 * so the two surfaces behave identically.
 */
internal fun openCourseInMoodle(context: Context, moodleCourseId: Int) {
    val redirect = "/course/view.php?id=$moodleCourseId"
    val targets = listOf(
        "moodlemobile://https://moodle2.ntust.edu.tw?redirect=$redirect",
        "https://moodle2.ntust.edu.tw$redirect",
    )
    for (target in targets) {
        val intent = Intent(Intent.ACTION_VIEW, target.toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        }
        val opened = runCatching { context.startActivity(intent) }.isSuccess
        if (opened) return
    }
}

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
