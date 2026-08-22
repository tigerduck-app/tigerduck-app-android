// Per-semester course rows and their grade chips, including the colour
// and label mapping for pass-fail and withdrawn statuses.

package org.ntust.app.tigerduck.ui.screen.score

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.CourseGrade
import org.ntust.app.tigerduck.data.model.CreditType
import org.ntust.app.tigerduck.data.model.GradeStatus
import org.ntust.app.tigerduck.data.model.SemesterRanking
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import kotlin.math.roundToInt

@Composable
internal fun SemesterSection(
    term: String,
    courses: List<CourseGrade>,
    ranking: SemesterRanking?,
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
                    val totalCredits = courses.sumOf { it.credits ?: 0 }
                    val parts = buildList {
                        add(stringResource(R.string.score_semester_total_credits, totalCredits))
                        ranking?.semester?.gpa?.let { add("GPA %.2f".format(it)) }
                        ranking?.semester?.let {
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
            val meta = buildList {
                add(course.code)
                add(stringResource(R.string.score_course_credits, course.credits ?: 0))
                course.geDimension?.let { add(it) }
                if (course.distanceLearning) add(stringResource(R.string.score_distance_learning))
                creditTypeLabel(course.creditType)?.let { add(it) }
            }
            Text(
                text = meta.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
            )
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
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = color,
            fontSize = 18.sp
        )
    }
}
@Composable
internal fun gradeDescriptor(course: CourseGrade): Pair<String, Color> = when (course.status) {
    GradeStatus.PENDING -> stringResource(R.string.score_grade_pending) to Color(0xFF95A5A6)
    GradeStatus.WITHDREW -> stringResource(R.string.score_grade_withdrew) to Color(0xFF95A5A6)
    GradeStatus.EXEMPTED -> stringResource(R.string.score_grade_exempted) to Color(0xFF85C1E9)
    GradeStatus.PASS_FAIL_GRADED -> if (course.grade == "通過")
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

// --- Detail dialog ---
