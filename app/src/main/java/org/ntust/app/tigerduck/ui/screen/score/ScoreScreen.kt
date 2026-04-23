package org.ntust.app.tigerduck.ui.screen.score

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.data.model.CourseGrade
import org.ntust.app.tigerduck.data.model.CreditSummary
import org.ntust.app.tigerduck.data.model.CreditType
import org.ntust.app.tigerduck.data.model.GradeStatus
import org.ntust.app.tigerduck.data.model.SemesterRanking
import org.ntust.app.tigerduck.ui.component.EmptyStateView
import org.ntust.app.tigerduck.ui.component.PageHeader
import org.ntust.app.tigerduck.ui.component.SyncIndicator
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreScreen(viewModel: ScoreViewModel = hiltViewModel()) {
    val report by viewModel.report.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
    val rankingScope by viewModel.rankingScope.collectAsStateWithLifecycle()
    val collapsedTerms by viewModel.collapsedTerms.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }

    val pullState = rememberPullToRefreshState()
    var pulling by remember { mutableStateOf(false) }
    LaunchedEffect(isRefreshing) { if (!isRefreshing) pulling = false }

    var selectedCourse by remember { mutableStateOf<CourseGrade?>(null) }

    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            state = pullState,
            isRefreshing = pulling,
            onRefresh = {
                pulling = true
                viewModel.triggerRefresh()
            },
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                PageHeader(title = "歷年成績") {
                    SyncIndicator(isLoading = isRefreshing, showCheckmark = false)
                }

                when {
                    !isLoggedIn -> EmptyStateView(
                        icon = Icons.Filled.Lock,
                        title = "尚未登入",
                        message = "尚未登入，無法查看歷年成績",
                        modifier = Modifier.padding(top = 32.dp)
                    )
                    !viewModel.hasContent && !isRefreshing -> EmptyStateView(
                        icon = Icons.Filled.Search,
                        title = "沒有成績資料",
                        message = errorMessage ?: "下拉以重新整理，或稍後再試",
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

// MARK: - Student header

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
                    text = student.ifEmpty { "歷年成績" },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                if (currentTerm.isNotEmpty()) {
                    Text(
                        text = formatCurrentTerm(currentTerm),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
                    )
                }
            }
        }
    }
}

private fun formatCurrentTerm(code: String): String {
    if (code.length != 4) return code
    val year = code.take(3)
    val sem = code.last()
    val label = when (sem) { '1' -> "上"; '2' -> "下"; else -> sem.toString() }
    return "$year 學年度 · ${label}學期"
}

// MARK: - Credit summary

@Composable
private fun CreditSummaryCard(summary: CreditSummary) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "學分統計",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CreditStat("已實得", summary.earned.total, Modifier.weight(1f))
                CreditStat("修習中", summary.enrolled.total, Modifier.weight(1f))
                CreditStat("合計", summary.total.total, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CreditStat(label: String, value: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
        )
    }
}

// MARK: - GPA trend

private val trendAccent = Color(0xFF4ECDC4)

@Composable
private fun RankingsTrendCard(
    rankings: List<SemesterRanking>,
    scope: ScoreViewModel.RankingScope,
    onScopeChange: (ScoreViewModel.RankingScope) -> Unit,
) {
    // Reset the pinned term when the scope switches so the summary reflects
    // the newly-chosen series instead of carrying over a stale index.
    var selectedTerm by remember(scope, rankings) { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "GPA 趨勢",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f)
                )
                SingleChoiceSegmentedButtonRow {
                    ScoreViewModel.RankingScope.values().forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = scope == option,
                            onClick = { onScopeChange(option) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = ScoreViewModel.RankingScope.values().size
                            )
                        ) {
                            Text(option.displayName, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            if (rankings.isEmpty()) {
                Text(
                    text = "尚無排名資料",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    textAlign = TextAlign.Center
                )
                return@Column
            }

            Spacer(Modifier.height(12.dp))
            TrendChart(
                rankings = rankings,
                scope = scope,
                selectedTerm = selectedTerm,
                onSelect = { selectedTerm = it },
            )
            Spacer(Modifier.height(12.dp))
            TrendSummaryRow(
                rankings = rankings,
                scope = scope,
                selectedTerm = selectedTerm,
            )
        }
    }
}

@Composable
private fun TrendChart(
    rankings: List<SemesterRanking>,
    scope: ScoreViewModel.RankingScope,
    selectedTerm: String?,
    onSelect: (String) -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val axisColor = onSurface.copy(alpha = 0.25f)
    val ruleColor = onSurface.copy(alpha = 0.35f)
    val axisLabelColor = onSurface.copy(alpha = ContentAlpha.SECONDARY)
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall.copy(color = axisLabelColor)

    val values = remember(rankings, scope) {
        rankings.map { gpa(it, scope) }
    }
    val yDomain = remember(values) { computeYDomain(values) }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .pointerInput(rankings, scope) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        // minimumDistance-0 drag: we treat press and drag the
                        // same so a single tap pins a point, matching iOS.
                        val change = event.changes.firstOrNull() ?: continue
                        if (change.pressed) {
                            val plotLeft = with(density) { AXIS_LABEL_WIDTH.toPx() }
                            val plotRight = size.width.toFloat()
                            val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
                            val ratio = ((change.position.x - plotLeft) / plotWidth)
                                .coerceIn(0f, 1f)
                            val idx = ((rankings.size - 1) * ratio).roundToInt()
                                .coerceIn(0, rankings.lastIndex)
                            onSelect(rankings[idx].term)
                            change.consume()
                        }
                    }
                }
            }
    ) {
        val plotLeft = AXIS_LABEL_WIDTH.toPx()
        val plotTop = 8.dp.toPx()
        val plotRight = size.width
        val plotBottom = size.height - 8.dp.toPx()
        val plotWidth = plotRight - plotLeft
        val plotHeight = plotBottom - plotTop

        fun x(index: Int): Float = if (rankings.size == 1) {
            plotLeft + plotWidth / 2f
        } else {
            plotLeft + plotWidth * index / (rankings.size - 1).toFloat()
        }
        fun y(value: Double): Float {
            val range = (yDomain.second - yDomain.first).takeIf { it > 0 } ?: 1.0
            val frac = ((value - yDomain.first) / range).toFloat().coerceIn(0f, 1f)
            return plotBottom - frac * plotHeight
        }

        // Y-axis guide ticks + labels (4 tiers)
        val tickCount = 4
        for (i in 0 until tickCount) {
            val frac = i / (tickCount - 1f)
            val yPos = plotBottom - frac * plotHeight
            val tickValue = yDomain.first + frac * (yDomain.second - yDomain.first)
            drawLine(
                color = axisColor,
                start = Offset(plotLeft, yPos),
                end = Offset(plotRight, yPos),
                strokeWidth = 1f
            )
            val label = "%.1f".format(tickValue)
            val layout = textMeasurer.measure(label, axisStyle)
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    x = plotLeft - layout.size.width - 4.dp.toPx(),
                    y = yPos - layout.size.height / 2f
                )
            )
        }

        // Monotone cubic Bezier path connecting successive valid points —
        // same visual as iOS's Chart `.interpolationMethod(.monotone)`.
        val points = rankings.mapIndexedNotNull { index, _ ->
            values[index]?.let { v -> Offset(x(index), y(v)) }
        }
        if (points.size >= 2) {
            drawPath(
                path = monotoneCubicPath(points),
                color = trendAccent,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }

        // Plotted points — enlarged for the selected one
        val selected = resolvedSelection(rankings, selectedTerm)
        rankings.forEachIndexed { index, r ->
            val v = values[index] ?: return@forEachIndexed
            val center = Offset(x(index), y(v))
            val isSel = r.term == selected?.term
            val radius = if (isSel) 7.dp.toPx() else 3.5.dp.toPx()
            drawCircle(color = trendAccent, radius = radius, center = center)
        }

        // Crosshair + inner dot on the pinned point
        selected?.let { sel ->
            val index = rankings.indexOfFirst { it.term == sel.term }
            if (index < 0) return@let
            val v = values[index] ?: return@let
            val cx = x(index)
            drawLine(
                color = ruleColor,
                start = Offset(cx, plotTop),
                end = Offset(cx, plotBottom),
                strokeWidth = 1f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                    floatArrayOf(3.dp.toPx(), 3.dp.toPx())
                )
            )
            drawCircle(
                color = Color.White,
                radius = 3.dp.toPx(),
                center = Offset(cx, y(v))
            )
        }
    }
}

@Composable
private fun TrendSummaryRow(
    rankings: List<SemesterRanking>,
    scope: ScoreViewModel.RankingScope,
    selectedTerm: String?,
) {
    val source = resolvedSelection(rankings, selectedTerm) ?: return
    val stats = rank(source, scope)
    val gpaTitle = if (selectedTerm != null) "${displayTermShort(source.term)} GPA" else "最新 GPA"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SummaryCell(title = gpaTitle, value = stats.gpa?.let { "%.2f".format(it) } ?: "—")
        SummaryCell(title = "班排名", value = stats.classRank?.toString() ?: "—")
        SummaryCell(title = "系排名", value = stats.deptRank?.toString() ?: "—")
    }
}

@Composable
private fun SummaryCell(title: String, value: String) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
        )
    }
}

private val AXIS_LABEL_WIDTH = 28.dp

private fun gpa(ranking: SemesterRanking, scope: ScoreViewModel.RankingScope): Double? =
    rank(ranking, scope).gpa

private fun rank(ranking: SemesterRanking, scope: ScoreViewModel.RankingScope) =
    if (scope == ScoreViewModel.RankingScope.SEMESTER) ranking.semester else ranking.cumulative

private fun computeYDomain(values: List<Double?>): Pair<Double, Double> {
    val nonNull = values.filterNotNull()
    if (nonNull.isEmpty()) return 0.0 to 4.3
    val min = nonNull.min()
    val max = nonNull.max()
    val lower = maxOf(0.0, min - 0.3)
    val upper = minOf(4.3, max + 0.3)
    if (upper - lower < 0.2) return (lower - 0.1).coerceAtLeast(0.0) to (upper + 0.1).coerceAtMost(4.3)
    return lower to upper
}

private fun resolvedSelection(rankings: List<SemesterRanking>, selectedTerm: String?): SemesterRanking? {
    if (rankings.isEmpty()) return null
    if (selectedTerm != null) {
        val match = rankings.firstOrNull { it.term == selectedTerm }
        if (match != null) return match
    }
    return rankings.last()
}

private fun displayTermShort(code: String): String {
    if (code.length != 4) return code
    val year = code.take(3)
    val sem = code.last()
    val label = when (sem) { '1' -> "上"; '2' -> "下"; else -> sem.toString() }
    return "$year-$label"
}

// MARK: - Semester section

@Composable
private fun SemesterSection(
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
                        add("$totalCredits 學分")
                        ranking?.semester?.gpa?.let { add("GPA %.2f".format(it)) }
                        ranking?.semester?.let {
                            if (it.classRank != null && it.deptRank != null) {
                                add("排名 ${it.deptRank}(${it.classRank})")
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
                add("${course.credits ?: 0} 學分")
                course.geDimension?.let { add(it) }
                if (course.distanceLearning) add("遠距")
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

private fun gradeDescriptor(course: CourseGrade): Pair<String, Color> = when (course.status) {
    GradeStatus.PENDING -> "未到" to Color(0xFF95A5A6)
    GradeStatus.WITHDREW -> "退選" to Color(0xFF95A5A6)
    GradeStatus.EXEMPTED -> "抵免" to Color(0xFF85C1E9)
    GradeStatus.PASSED -> if (course.grade == "通過")
        "通過" to Color(0xFF4ECDC4) else "未過" to Color(0xFFE74C3C)
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

private fun creditTypeLabel(type: CreditType): String? = when (type) {
    CreditType.EDUCATION_PROGRAM -> "教育學程"
    CreditType.NOT_COUNTED -> "不計入"
    CreditType.NOT_REQUIRED -> "非必修"
    CreditType.NOT_EARNED -> "未取得"
    CreditType.NORMAL, CreditType.UNKNOWN -> null
}

// MARK: - Detail dialog

@Composable
private fun CourseDetailDialog(course: CourseGrade, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("關閉") }
        },
        title = { Text(course.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoLine("課號", course.code)
                InfoLine("學期", displayTerm(course.term))
                InfoLine("學分", "${course.credits ?: 0}")
                creditTypeLabel(course.creditType)?.let { InfoLine("學分類型", it) }
                course.geDimension?.let { InfoLine("通識向度", it) }
                InfoLine("成績", gradeDescriptor(course).first)
                if (course.distanceLearning) InfoLine("授課方式", "遠距")
                if (course.remark.isNotEmpty()) InfoLine("備註", course.remark)
            }
        },
        properties = DialogProperties()
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
            modifier = Modifier.width(72.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun displayTerm(code: String): String {
    if (code.length != 4) return code
    val year = code.take(3)
    val sem = code.last()
    val label = when (sem) { '1' -> "上"; '2' -> "下"; else -> sem.toString() }
    return "$year 學年度 ${label}學期"
}

/**
 * Monotone cubic Hermite spline (Fritsch–Carlson). Produces smooth curves
 * between [points] without overshoot — the curve is monotonic on any
 * interval where the input samples are monotonic, so GPA dips don't get
 * amplified into visual bumps that never happened.
 */
private fun monotoneCubicPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points[0].x, points[0].y)
    if (points.size == 1) return path
    if (points.size == 2) {
        path.lineTo(points[1].x, points[1].y)
        return path
    }

    val n = points.size
    val dx = FloatArray(n - 1)
    val slope = FloatArray(n - 1)
    for (i in 0 until n - 1) {
        dx[i] = points[i + 1].x - points[i].x
        slope[i] = if (dx[i] != 0f) (points[i + 1].y - points[i].y) / dx[i] else 0f
    }

    val tangent = FloatArray(n)
    tangent[0] = slope[0]
    tangent[n - 1] = slope[n - 2]
    for (i in 1 until n - 1) {
        tangent[i] = if (slope[i - 1] * slope[i] <= 0f) 0f
                    else (slope[i - 1] + slope[i]) / 2f
    }

    // Fritsch–Carlson adjustment to keep the spline monotone.
    for (i in 0 until n - 1) {
        if (slope[i] == 0f) {
            tangent[i] = 0f
            tangent[i + 1] = 0f
        } else {
            val a = tangent[i] / slope[i]
            val b = tangent[i + 1] / slope[i]
            val h = a * a + b * b
            if (h > 9f) {
                val t = 3f / kotlin.math.sqrt(h)
                tangent[i] = t * a * slope[i]
                tangent[i + 1] = t * b * slope[i]
            }
        }
    }

    for (i in 0 until n - 1) {
        val p0 = points[i]
        val p1 = points[i + 1]
        val c1x = p0.x + dx[i] / 3f
        val c1y = p0.y + tangent[i] * dx[i] / 3f
        val c2x = p1.x - dx[i] / 3f
        val c2y = p1.y - tangent[i + 1] * dx[i] / 3f
        path.cubicTo(c1x, c1y, c2x, c2y, p1.x, p1.y)
    }
    return path
}
