// Per-semester course rows and their grade chips, including the colour,
// label and status-icon mapping for pass-fail and withdrawn statuses, and
// the credit-type and GE badges beside each row's meta line.

package org.ntust.app.tigerduck.ui.screen.score

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.CourseGrade
import org.ntust.app.tigerduck.data.model.CreditType
import org.ntust.app.tigerduck.data.model.GradeStatus
import org.ntust.app.tigerduck.data.model.GpaTrendPoint
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.util.formatCredits

@Composable
internal fun SemesterSection(
    term: String,
    courses: List<CourseGrade>,
    gpaPoint: GpaTrendPoint?,
    isCollapsed: Boolean,
    onToggle: () -> Unit,
    onCourseTap: (CourseGrade) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = displayTerm(term),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(Modifier.height(2.dp))
                    val totalCredits = courses.fold(0f) { acc, c -> acc + (c.credits ?: 0f) }
                    val parts = buildList {
                        add(stringResource(R.string.score_semester_total_credits, totalCredits.formatCredits()))
                        // Before the school posts the ranking the GPA here is
                        // the estimate from the grades in so far, and says so
                        // — an unlabelled number this close to the official
                        // one would be read as the official one.
                        val gpa = gpaPoint?.semester?.gpa
                        if (gpa != null) {
                            val estimate = if (gpaPoint.isProvisional) {
                                " · " + stringResource(R.string.score_gpa_provisional)
                            } else {
                                ""
                            }
                            add("GPA %.2f".format(gpa) + estimate)
                        }
                        // Ranks only ever come from a published ranking, so
                        // this row simply does not appear on an estimate.
                        gpaPoint?.semester?.let {
                            if (it.classRank != null && it.deptRank != null) {
                                add(
                                    stringResource(
                                        R.string.score_semester_ranking,
                                        it.deptRank,
                                        it.classRank
                                    )
                                )
                            }
                        }
                    }
                    Text(
                        text = parts.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
                    )
                }
                Icon(
                    imageVector = if (isCollapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
                )
            }
            AnimatedVisibility(visible = !isCollapsed) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    courses.forEachIndexed { index, course ->
                        if (index > 0) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                                modifier = Modifier.padding(start = 16.dp)
                            )
                        }
                        CourseRow(course = course, onClick = { onCourseTap(course) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CourseRow(course: CourseGrade, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = course.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 2
            )
            Spacer(Modifier.height(2.dp))
            // Code, credits and delivery as one line of text; the credit type
            // and the GE dimension as badges after it, as iOS draws them, so
            // a credit that does not count reads at a glance instead of as
            // one more word in the line. A FlowRow, so a long dimension name
            // wraps under the line on a narrow screen rather than clipping.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val meta = buildList {
                    add(course.code)
                    add(stringResource(R.string.score_course_credits, (course.credits ?: 0f).formatCredits()))
                    if (course.distanceLearning) add(stringResource(R.string.score_distance_learning))
                }
                Text(
                    text = meta.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                CreditTypeBadge(course.creditType, Modifier.align(Alignment.CenterVertically))
                course.geDimension?.let {
                    MetaBadge(
                        text = it,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                        background = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        GradeChip(course)
    }
}

@Composable
private fun GradeChip(course: CourseGrade) {
    val (label, color) = gradeDescriptor(course)
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            gradeStatusIcon(course)?.let {
                // Decorative for TalkBack: the label beside it names the
                // status, and a description here would have the row read
                // "Pending, Pending".
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = color,
                fontSize = 18.sp
            )
        }
    }
}

/**
 * The glyph the grade chip puts before its label, as the iPhone's does: an
 * hourglass while the grade is pending, a crossed circle for a withdrawal, a
 * seal for an exemption, and a tick or a cross for a pass-fail course. A
 * letter grade speaks for itself and gets none.
 */
internal fun gradeStatusIcon(course: CourseGrade): ImageVector? = when (course.status) {
    GradeStatus.PENDING -> Icons.Outlined.HourglassEmpty
    GradeStatus.WITHDREW -> Icons.Outlined.Cancel
    GradeStatus.EXEMPTED -> Icons.Outlined.Verified
    GradeStatus.PASS_FAIL_GRADED -> if (course.passedPassFail) Icons.Filled.Check else Icons.Filled.Close
    GradeStatus.GRADED, GradeStatus.UNKNOWN -> null
}

/** The outcome of a pass-fail course, which [GradeStatus.PASS_FAIL_GRADED] leaves in the raw grade. */
private val CourseGrade.passedPassFail: Boolean
    get() = grade == "通過"

@Composable
internal fun gradeDescriptor(course: CourseGrade): Pair<String, Color> = when (course.status) {
    GradeStatus.PENDING -> stringResource(R.string.score_grade_pending) to Color(0xFF95A5A6)
    GradeStatus.WITHDREW -> stringResource(R.string.score_grade_withdrew) to Color(0xFF95A5A6)
    GradeStatus.EXEMPTED -> stringResource(R.string.score_grade_exempted) to Color(0xFF85C1E9)
    GradeStatus.PASS_FAIL_GRADED -> if (course.passedPassFail)
        stringResource(R.string.score_grade_passed) to Color(0xFF4ECDC4)
    else stringResource(R.string.score_grade_failed) to Color(0xFFE74C3C)

    GradeStatus.GRADED -> course.grade to gradeColor(course.grade)
    GradeStatus.UNKNOWN -> (course.grade.ifEmpty { "—" }) to Color(0xFF95A5A6)
}
private fun gradeColor(grade: String): Color {
    val upper = grade.uppercase()
    return when {
        upper.startsWith("A") -> Color(0xFF2ECC71)
        upper.startsWith("B") -> Color(0xFF3498DB)
        upper.startsWith("C") -> Color(0xFFF1C40F)
        upper.startsWith("D") || upper.startsWith("E") || upper.startsWith("F") ->
            Color(0xFFFF6B6B)

        else -> Color(0xFF95A5A6)
    }
}
@Composable
internal fun creditTypeLabel(type: CreditType): String? = when (type) {
    CreditType.EDUCATION_PROGRAM -> stringResource(R.string.score_credit_type_education_program)
    CreditType.NOT_COUNTED -> stringResource(R.string.score_credit_type_not_counted)
    CreditType.NOT_REQUIRED -> stringResource(R.string.score_credit_type_not_required)
    CreditType.NOT_EARNED -> stringResource(R.string.score_credit_type_not_earned)
    CreditType.NORMAL, CreditType.UNKNOWN -> null
}

// --- Credit type and GE badges ---

/** A credit type's badge: the short label it shows, the full name TalkBack reads, and its colour. */
internal data class CreditTypeBadgeSpec(
    @StringRes val shortLabel: Int,
    @StringRes val fullLabel: Int,
    val color: Color,
)

/** Null for a normal credit, which stays unmarked so the list is quiet for the common case, as on iOS. */
internal fun creditTypeBadgeSpec(type: CreditType): CreditTypeBadgeSpec? = when (type) {
    CreditType.EDUCATION_PROGRAM -> CreditTypeBadgeSpec(
        R.string.score_credit_type_education_program_short,
        R.string.score_credit_type_education_program,
        Color(0xFF9B59B6),
    )
    CreditType.NOT_COUNTED -> CreditTypeBadgeSpec(
        R.string.score_credit_type_not_counted_short,
        R.string.score_credit_type_not_counted,
        Color(0xFF95A5A6),
    )
    CreditType.NOT_REQUIRED -> CreditTypeBadgeSpec(
        R.string.score_credit_type_not_required_short,
        R.string.score_credit_type_not_required,
        Color(0xFFE67E22),
    )
    CreditType.NOT_EARNED -> CreditTypeBadgeSpec(
        R.string.score_credit_type_not_earned_short,
        R.string.score_credit_type_not_earned,
        Color(0xFFE74C3C),
    )
    CreditType.NORMAL, CreditType.UNKNOWN -> null
}

@Composable
private fun CreditTypeBadge(type: CreditType, modifier: Modifier = Modifier) {
    val spec = creditTypeBadgeSpec(type) ?: return
    val fullLabel = stringResource(spec.fullLabel)
    MetaBadge(
        text = stringResource(spec.shortLabel),
        color = spec.color,
        background = spec.color.copy(alpha = 0.18f),
        // "EP" and "Opt" are abbreviations for the eye; TalkBack reads the
        // credit type in full instead.
        modifier = modifier.clearAndSetSemantics { contentDescription = fullLabel },
    )
}

/** A small capsule of text beside the meta line, as iOS's CreditTypeBadge and dimension pill. */
@Composable
private fun MetaBadge(text: String, color: Color, background: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color = color,
        modifier = modifier
            .clip(CircleShape)
            .background(background)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
