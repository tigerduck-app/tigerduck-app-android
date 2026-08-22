// The 成績 tab: student header, credit summary, and the semester list it
// assembles from the sibling files.

package org.ntust.app.tigerduck.ui.screen.score

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import org.ntust.app.tigerduck.data.model.CreditSummary
import org.ntust.app.tigerduck.ui.component.EmptyStateView
import org.ntust.app.tigerduck.ui.component.PageHeader
import org.ntust.app.tigerduck.ui.component.ServerKind
import org.ntust.app.tigerduck.ui.component.ServerStatusIcons
import org.ntust.app.tigerduck.ui.component.SyncIndicator
import org.ntust.app.tigerduck.ui.component.TigerPullToRefresh
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreScreen(
    viewModel: ScoreViewModel = hiltViewModel(),
    onOpenSignInSettings: () -> Unit = {},
) {
    val report by viewModel.report.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val isSyncLocalOnly by viewModel.isSyncLocalOnly.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
    val rankingScope by viewModel.rankingScope.collectAsStateWithLifecycle()
    val collapsedTerms by viewModel.collapsedTerms.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }

    var pullProgress by remember { mutableFloatStateOf(0f) }

    var selectedCourse by remember { mutableStateOf<CourseGrade?>(null) }

    Box(Modifier.fillMaxSize()) {
        TigerPullToRefresh(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.triggerRefresh() },
            onDragProgress = { pullProgress = it },
            modifier = Modifier.fillMaxSize(),
            refreshingMessage = stringResource(R.string.refreshing_message),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                PageHeader(title = stringResource(R.string.feature_score)) {
                    SyncIndicator(
                        isLoading = isRefreshing,
                        showCheckmark = false,
                        dragProgress = pullProgress,
                        isLocalOnly = isSyncLocalOnly,
                    )
                    ServerStatusIcons(
                        servers = listOf(ServerKind.COURSE_SELECTION),
                    )
                }

                when {
                    !isLoggedIn -> EmptyStateView(
                        icon = Icons.Filled.Lock,
                        title = stringResource(R.string.common_not_signed_in),
                        message = stringResource(R.string.score_not_signed_in_message),
                        modifier = Modifier.padding(top = 32.dp),
                        onIconClick = onOpenSignInSettings,
                    )

                    !viewModel.hasContent && !isRefreshing -> EmptyStateView(
                        icon = Icons.Filled.Search,
                        title = stringResource(R.string.score_empty_title),
                        message = errorMessage ?: stringResource(R.string.score_empty_message),
                        modifier = Modifier.padding(top = 32.dp)
                    )

                    else -> {
                        StudentHeaderCard(
                            student = report.student,
                            currentTerm = report.currentTerm,
                        )
                        Spacer(Modifier.height(8.dp))
                        CreditSummaryCard(summary = report.creditSummary)
                        if (report.rankings.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            RankingsTrendCard(
                                rankings = viewModel.rankingTrend,
                                scope = rankingScope,
                                onScopeChange = viewModel::setRankingScope,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        viewModel.groupedCourses.forEach { (term, courses) ->
                            SemesterSection(
                                term = term,
                                courses = courses,
                                ranking = viewModel.ranking(term),
                                isCollapsed = term in collapsedTerms,
                                onToggle = { viewModel.toggleCollapse(term) },
                                onCourseTap = { selectedCourse = it }
                            )
                        }
                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }
    }

    selectedCourse?.let { course ->
        CourseDetailDialog(course = course, onDismiss = { selectedCourse = null })
    }
}

// --- Student header ---
@Composable
private fun StudentHeaderCard(student: String, currentTerm: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.School,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = student.ifEmpty { stringResource(R.string.feature_score) },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                if (currentTerm.isNotEmpty()) {
                    Text(
                        text = formatCurrentTerm(
                            stringResource(R.string.score_term_format),
                            currentTerm
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
                    )
                }
            }
        }
    }
}
private fun formatCurrentTerm(pattern: String, code: String): String {
    if (code.length != 4) return code
    val year = code.take(3)
    val label = when (val sem = code.last()) {
        '1' -> "1"; '2' -> "2"; else -> sem.toString()
    }
    return pattern.format(year, label)
}

// --- Credit summary ---
@Composable
private fun CreditSummaryCard(summary: CreditSummary) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.score_credit_summary_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CreditStat(
                    stringResource(R.string.score_credit_earned),
                    summary.earned.total,
                    Modifier.weight(1f)
                )
                CreditStat(
                    stringResource(R.string.score_credit_enrolled),
                    summary.enrolled.total,
                    Modifier.weight(1f)
                )
                CreditStat(
                    stringResource(R.string.score_credit_total),
                    summary.total.total,
                    Modifier.weight(1f)
                )
            }
        }
    }
}
@Composable
private fun CreditStat(label: String, value: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(vertical = 18.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
        )
    }
}

// --- GPA trend ---
@Composable
internal fun displayTerm(code: String): String {
    if (code.length != 4) return code
    val year = code.take(3)
    val label = when (val sem = code.last()) {
        '1' -> stringResource(R.string.score_semester_upper)
        '2' -> stringResource(R.string.score_semester_lower)
        else -> sem.toString()
    }
    return stringResource(R.string.score_academic_year_semester, year, label)
}
