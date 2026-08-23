// The rankings trend chart — axis domain, monotone-cubic path fitting,
// the scrubbing summary row, and the digit-rolling text it drives. This
// is the one genuinely mathematical part of the score screen.

package org.ntust.app.tigerduck.ui.screen.score

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.SemesterRanking
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import kotlin.math.roundToInt

private val trendAccent = Color(0xFF4ECDC4)
private val AXIS_LABEL_WIDTH = 28.dp
@Composable
internal fun RankingsTrendCard(
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
                    text = stringResource(R.string.score_gpa_trend_title),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f)
                )
                val primary = MaterialTheme.colorScheme.primary
                val activeContainer = if (TigerDuckTheme.isDarkMode) {
                    lerp(primary, Color.White, 0.7f).copy(alpha = 0.22f)
                } else {
                    primary.copy(alpha = 0.08f)
                }
                val segmentColors = SegmentedButtonDefaults.colors(
                    activeContainerColor = activeContainer,
                    activeContentColor = primary,
                    activeBorderColor = primary,
                )
                SingleChoiceSegmentedButtonRow {
                    // .entries is cached on the enum class; .values() allocates
                    // a fresh array on every call (and is now deprecated).
                    val scopes = ScoreViewModel.RankingScope.entries
                    scopes.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = scope == option,
                            onClick = { onScopeChange(option) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = scopes.size,
                            ),
                            colors = segmentColors,
                        ) {
                            Text(
                                when (option) {
                                    ScoreViewModel.RankingScope.SEMESTER -> stringResource(R.string.score_scope_semester)
                                    ScoreViewModel.RankingScope.CUMULATIVE -> stringResource(R.string.score_scope_cumulative)
                                },
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }

            if (rankings.isEmpty()) {
                Text(
                    text = stringResource(R.string.score_no_ranking_data),
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
                style = Stroke(
                    width = 2.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
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
    // Cumulative mode shows a running total, so the label calls out which
    // term the value is accumulated *through* — "累計至 114-上". Semester
    // mode just identifies the pinned term's GPA.
    val gpaTitle = when {
        scope == ScoreViewModel.RankingScope.CUMULATIVE ->
            stringResource(R.string.score_gpa_cumulative_to, displayTermShort(source.term))

        selectedTerm != null -> stringResource(
            R.string.score_gpa_term_label,
            displayTermShort(source.term)
        )

        else -> stringResource(R.string.score_gpa_latest)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        SummaryCell(
            title = gpaTitle,
            value = stats.gpa?.let { "%.2f".format(it) } ?: "—",
            modifier = Modifier.weight(1f)
        )
        SummaryCell(
            title = stringResource(R.string.score_rank_class),
            value = stats.classRank?.toString() ?: "—",
            modifier = Modifier.weight(1f)
        )
        SummaryCell(
            title = stringResource(R.string.score_rank_department),
            value = stats.deptRank?.toString() ?: "—",
            modifier = Modifier.weight(1f)
        )
    }
}
@Composable
private fun SummaryCell(title: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RollingText(
            value = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
        )
        Spacer(Modifier.height(4.dp))
        RollingText(
            value = value,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
/**
 * Per-character scroll-wheel animation.
 *
 * Each position runs its own [AnimatedContent]. When a digit increases
 * (e.g. 5 → 7) it rolls upward — new digit slides in from below, old digit
 * slides out upward. When it decreases, it rolls downward. Non-digit swaps
 * (or digit↔non-digit) default to the upward direction so text transitions
 * (e.g. "最新 GPA" → "114-上 GPA") stay visually coherent.
 */
@Composable
private fun RollingText(
    value: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        value.forEachIndexed { index, targetChar ->
            key(index) {
                AnimatedContent(
                    targetState = targetChar,
                    transitionSpec = {
                        val from = initialState.digitToIntOrNull()
                        val to = targetState.digitToIntOrNull()
                        val goingUp = if (from != null && to != null) to >= from else true
                        val dur = 320
                        if (goingUp) {
                            (slideInVertically(tween(dur)) { h -> h } +
                                    fadeIn(tween(dur))) togetherWith
                                    (slideOutVertically(tween(dur)) { h -> -h } +
                                            fadeOut(tween(dur)))
                        } else {
                            (slideInVertically(tween(dur)) { h -> -h } +
                                    fadeIn(tween(dur))) togetherWith
                                    (slideOutVertically(tween(dur)) { h -> h } +
                                            fadeOut(tween(dur)))
                        }
                    },
                    label = "roll-$index",
                ) { c ->
                    Text(text = c.toString(), style = style, color = color)
                }
            }
        }
    }
}
internal fun gpa(ranking: SemesterRanking, scope: ScoreViewModel.RankingScope): Double? =
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
    if (upper - lower < 0.2) return (lower - 0.1).coerceAtLeast(0.0) to (upper + 0.1).coerceAtMost(
        4.3
    )
    return lower to upper
}
private fun resolvedSelection(
    rankings: List<SemesterRanking>,
    selectedTerm: String?
): SemesterRanking? {
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
    val label = when (val sem = code.last()) {
        '1' -> "1"; '2' -> "2"; else -> sem.toString()
    }
    return "$year-$label"
}

// --- Semester section ---
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
