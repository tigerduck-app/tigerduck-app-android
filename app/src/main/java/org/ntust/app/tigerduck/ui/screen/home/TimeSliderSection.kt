package org.ntust.app.tigerduck.ui.screen.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.data.model.Course
import org.ntust.app.tigerduck.ui.component.JumpToNowChip
import org.ntust.app.tigerduck.ui.component.courseNameForDisplay
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun TimeSliderSection(
    courses: List<Course>,
    invertDirection: Boolean,
    skippedDates: Map<String, List<String>> = emptyMap(),
    isLoggedIn: Boolean = true,
    initialLoadComplete: Boolean = true,
    onSkipCourse: (Course, Date) -> Unit = { _, _ -> },
    onSelectCourse: (Course) -> Unit
) {
    val viewModel = remember { TimeSliderViewModel() }

    LaunchedEffect(courses.map { it.courseNo }) {
        viewModel.configure(courses)
    }

    // Tick every second
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.tick(Date())
            kotlinx.coroutines.delay(1000)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Header — fixed height so the 現在 chip appearing doesn't push
        // the slider and course card below it downward.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.home_time_slider_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.weight(1f))
            AnimatedVisibility(
                visible = viewModel.isUserDragging,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                JumpToNowChip(label = stringResource(R.string.home_time_slider_now), onClick = { viewModel.returnToNow() })
            }
        }

        if (viewModel.hasCourses) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Course card
                CourseTimeCard(
                    state = viewModel.currentCourseState,
                    skippedDates = skippedDates,
                    onSkipCourse = onSkipCourse,
                    onSelect = onSelectCourse
                )

                // Time label
                Text(
                    text = formatTimeLabel(viewModel.selectedTime),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                )

                // Track
                FluidTrack(viewModel, invertDirection)
            }
        } else if (isLoggedIn && !initialLoadComplete) {
            // Cache hasn't been consulted yet — don't flash "目前沒有課程"
            // while the on-disk cache is still being read.
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    if (isLoggedIn) Icons.Filled.CheckCircle else Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.DISABLED),
                    modifier = Modifier.size(32.dp)
                )
                Text(
                    if (isLoggedIn) {
                        stringResource(R.string.home_time_slider_no_courses)
                    } else {
                        stringResource(R.string.common_login_required_feature)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
                )
            }
        }
    }
}

@Composable
private fun CourseTimeCard(
    state: CourseState,
    skippedDates: Map<String, List<String>>,
    onSkipCourse: (Course, Date) -> Unit,
    onSelect: (Course) -> Unit
) {
    val isoFmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    val isSkippedFor: (CourseTimeSlot) -> Boolean = { slot ->
        val dateKey = isoFmt.format(slot.date)
        skippedDates[slot.course.courseNo]?.contains(dateKey) == true
    }

    // Pin the card area to the tallest height seen this session so the slider
    // track below doesn't bob up and down as the user scrubs across states
    // with different card layouts. `remember` resets on Home tab destruction.
    var maxHeightPx by remember { mutableIntStateOf(0) }
    val minHeightDp = with(LocalDensity.current) { maxHeightPx.toDp() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeightDp)
    ) {
        EqualHeightRow(
            spacing = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged {
                    if (it.height > maxHeightPx) maxHeightPx = it.height
                }
        ) {
            // 翹課 feature disabled — the onSkipToggle wiring below is commented out
            // so SlotCard falls back to its null default and the left-swipe gesture
            // + "翹課" indicator are inert. Re-enable by restoring the commented lines.
            when (state) {
                is CourseState.InClass -> {
                    SlotCard(
                        slot = state.slot,
                        alpha = 1f,
                        isSkipped = isSkippedFor(state.slot),
                        // onSkipToggle = { onSkipCourse(state.slot.course, state.slot.date) },
                        onClick = { onSelect(state.slot.course) },
                    )
                }
                is CourseState.Between -> {
                    state.previous?.let {
                        SlotCard(
                            slot = it, alpha = 0.8f,
                            isSkipped = isSkippedFor(it),
                            // onSkipToggle = { onSkipCourse(it.course, it.date) },
                            onClick = { onSelect(it.course) },
                        )
                    }
                    state.next?.let {
                        SlotCard(
                            slot = it, alpha = 0.8f,
                            isSkipped = isSkippedFor(it),
                            // onSkipToggle = { onSkipCourse(it.course, it.date) },
                            onClick = { onSelect(it.course) },
                        )
                    }
                }
                is CourseState.BeforeFirst -> {
                    SlotCard(
                        slot = state.next, alpha = 0.8f,
                        isSkipped = isSkippedFor(state.next),
                        // onSkipToggle = { onSkipCourse(state.next.course, state.next.date) },
                        onClick = { onSelect(state.next.course) },
                    )
                }
                is CourseState.AfterLast -> {
                    SlotCard(
                        slot = state.previous, alpha = 0.8f,
                        isSkipped = isSkippedFor(state.previous),
                        // onSkipToggle = { onSkipCourse(state.previous.course, state.previous.date) },
                        onClick = { onSelect(state.previous.course) },
                    )
                }
            }
        }
    }
}

/**
 * Lays children out in a single row, splitting available width equally and
 * forcing every child to the tallest natural height in the group. Unlike
 * `Modifier.height(IntrinsicSize.Min)`, this works even when children use
 * `SubcomposeLayout` internally (e.g. SlotCard's FitOrStack), because we
 * remeasure children with explicit constraints rather than querying intrinsics.
 */
@Composable
private fun EqualHeightRow(
    spacing: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    SubcomposeLayout(modifier) { constraints ->
        val measurables = subcompose(Unit, content)
        val n = measurables.size
        if (n == 0) return@SubcomposeLayout layout(constraints.maxWidth, 0) {}
        val spacingPx = spacing.roundToPx()
        val totalSpacing = if (n > 1) spacingPx * (n - 1) else 0
        val widthPerChild = ((constraints.maxWidth - totalSpacing) / n).coerceAtLeast(0)
        // Pass 1: find each child's natural height at the assigned width.
        val probeConstraints = Constraints(
            minWidth = widthPerChild, maxWidth = widthPerChild,
            minHeight = 0, maxHeight = Constraints.Infinity,
        )
        val maxH = measurables.maxOf { it.measure(probeConstraints).height }
        // Pass 2: re-measure with a fixed height so each child fills the row.
        val finalConstraints = Constraints(
            minWidth = widthPerChild, maxWidth = widthPerChild,
            minHeight = maxH, maxHeight = maxH,
        )
        val placeables = measurables.map { it.measure(finalConstraints) }
        layout(constraints.maxWidth, maxH) {
            var x = 0
            placeables.forEach { p ->
                p.place(x, 0)
                x += p.width + spacingPx
            }
        }
    }
}

@Composable
private fun SlotCard(
    slot: CourseTimeSlot,
    alpha: Float,
    isSkipped: Boolean = false,
    onSkipToggle: (() -> Unit)? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = TigerDuckTheme.courseColorVibrant(slot.course.courseNo)
    val cal = Calendar.getInstance(AppConstants.TAIPEI_TZ)
    val weekday = run {
        cal.time = slot.date
        when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 1; Calendar.TUESDAY -> 2; Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4; Calendar.FRIDAY -> 5; Calendar.SATURDAY -> 6
            Calendar.SUNDAY -> 7; else -> 1
        }
    }
    val periods = slot.course.schedule[weekday]?.sortedBy {
        org.ntust.app.tigerduck.AppConstants.Periods.chronologicalOrder.indexOf(it)
    }
    val timeRange = if (!periods.isNullOrEmpty()) {
        val first = org.ntust.app.tigerduck.AppConstants.PeriodTimes.mapping[periods.first()]
        val last = org.ntust.app.tigerduck.AppConstants.PeriodTimes.mapping[periods.last()]
        if (first != null && last != null) "${first.first} - ${last.second}" else ""
    } else ""

    val isToday = Calendar.getInstance(AppConstants.TAIPEI_TZ).let {
        val today = it.get(Calendar.DAY_OF_YEAR)
        cal.time = slot.date
        today == cal.get(Calendar.DAY_OF_YEAR) && it.get(Calendar.YEAR) == cal.get(Calendar.YEAR)
    }

    val latestOnSkipToggle by rememberUpdatedState(onSkipToggle)
    val density = LocalDensity.current
    val thresholdPx = with(density) { 100.dp.toPx() }
    val swipeOffset = remember(slot.course.courseNo, slot.date) { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    Box(modifier = modifier) {
        // Skip action indicator shown behind the card while swiping
        if (onSkipToggle != null) {
            val progress = (abs(swipeOffset.value) / thresholdPx).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(end = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Column(
                    modifier = Modifier
                        .alpha(progress)
                        .scale(0.5f + 0.5f * progress),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = if (isSkipped) Icons.AutoMirrored.Filled.Undo else Icons.AutoMirrored.Filled.DirectionsWalk,
                        contentDescription = null,
                        tint = if (isSkipped) MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
                               else Color(0xFFFF2D55),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (isSkipped) {
                            stringResource(R.string.home_skip_course_undo)
                        } else {
                            stringResource(R.string.home_skip_course)
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (isSkipped) MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
                                else Color(0xFFFF2D55),
                        fontSize = 11.sp
                    )
                }
            }
        }

        val cardModifier = if (onSkipToggle != null) {
            Modifier
                .fillMaxSize()
                .offset { IntOffset(swipeOffset.value.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            coroutineScope.launch {
                                if (swipeOffset.value <= -thresholdPx) {
                                    swipeOffset.animateTo(-2000f, animationSpec = tween(durationMillis = 200))
                                    latestOnSkipToggle?.invoke()
                                    swipeOffset.snapTo(0f)
                                } else {
                                    swipeOffset.animateTo(0f, animationSpec = spring())
                                }
                            }
                        },
                        onDragCancel = {
                            coroutineScope.launch { swipeOffset.animateTo(0f, animationSpec = spring()) }
                        },
                        onHorizontalDrag = { _, delta ->
                            coroutineScope.launch {
                                swipeOffset.snapTo((swipeOffset.value + delta * 0.6f).coerceAtMost(0f))
                            }
                        }
                    )
                }
        } else {
            Modifier.fillMaxSize()
        }

        val slotSurface = MaterialTheme.colorScheme.surface
        val slotLightAlpha = if (alpha >= 1f) 0.50f else 0.35f
        val slotDarkAlpha = if (alpha >= 1f) 0.55f else 0.40f
        val slotContainer = if (TigerDuckTheme.isDarkMode) {
            color.copy(alpha = slotDarkAlpha)
                .compositeOver(slotSurface)
        } else {
            color.copy(alpha = slotLightAlpha)
        }
        Card(
            onClick = onClick,
            modifier = cardModifier,
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = slotContainer)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                val metaStyle = MaterialTheme.typography.labelSmall
                val metaBoldStyle = metaStyle.copy(fontWeight = FontWeight.Bold)
                val metaColor = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
                if (isToday) {
                    Text(timeRange, style = metaBoldStyle, color = metaColor)
                } else {
                    FitOrStack(
                        modifier = Modifier.fillMaxWidth(),
                        first = { Text(timeRange, style = metaBoldStyle, color = metaColor) },
                        second = {
                            Text(formatDateLabel(slot.date), style = metaStyle, color = metaColor)
                        },
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    courseNameForDisplay(slot.course.courseName, maxChars = 30),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (isSkipped) Color(0xFFFF2D55) else Color.Unspecified,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                InlineOrStackText(
                    primary = slot.course.classroom,
                    secondary = slot.course.instructor,
                    style = metaStyle,
                    color = metaColor,
                )
            }
        }
    }
}

@Composable
private fun FluidTrack(viewModel: TimeSliderViewModel, invertDirection: Boolean) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val trackHeightDp = TimeSliderViewModel.FLUID_TRACK_HEIGHT.dp
    var widthPx by remember { mutableStateOf(0f) }

    val pxPerDp = with(density) { 1.dp.toPx() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(trackHeightDp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .pointerInput(invertDirection) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        viewModel.onDragStarted()
                    },
                    onDragEnd = { viewModel.onDragEnded() },
                    onDragCancel = { viewModel.onDragEnded() },
                    onHorizontalDrag = { _, dragAmount ->
                        viewModel.onDragChanged(dragAmount / pxPerDp, invertDirection, context)
                    }
                )
            }
            .drawBehind {
                val centerX = size.width / 2
                val trackH = size.height
                val segHeightPx = TimeSliderViewModel.FLUID_SEGMENT_HEIGHT.dp.toPx()
                val majorMarkerHeightPx = TimeSliderViewModel.MAJOR_MARKER_HEIGHT.dp.toPx()
                val thumbHeightPx = TimeSliderViewModel.SELECTION_THUMB_HEIGHT.dp.toPx()
                val minSegWidthPx = TimeSliderViewModel.MIN_SEGMENT_WIDTH.dp.toPx()

                // Draw course segments
                for (slot in viewModel.timeSlots) {
                    val startOff = viewModel.xOffset(slot.start) * pxPerDp
                    val endOff = viewModel.xOffset(slot.end) * pxPerDp
                    val segW = maxOf(
                        minSegWidthPx,
                        endOff - startOff
                    )
                    val segCenterX = centerX + (startOff + endOff) / 2
                    val left = segCenterX - segW / 2

                    if (left + segW > -50 && left < size.width + 50) {
                        val isActive = viewModel.selectedTime >= slot.start && viewModel.selectedTime <= slot.end
                        val courseColor = TigerDuckTheme.courseColorVibrant(slot.course.courseNo)

                        drawRoundRect(
                            color = courseColor.copy(
                                alpha = TigerDuckTheme.tintAlpha(if (isActive) 0.5f else 0.3f)
                            ),
                            topLeft = Offset(
                                left,
                                (trackH - segHeightPx) / 2
                            ),
                            size = Size(segW, segHeightPx),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f)
                        )
                    }
                }

                // Tick marks
                val markerInterval = (TimeSliderViewModel.MARKER_INTERVAL_MINUTES * 60_000).toLong()
                val majorInterval = (TimeSliderViewModel.MAJOR_MARKER_INTERVAL_MINUTES * 60_000).toLong()
                val visibleMinutes = (size.width / pxPerDp) / TimeSliderViewModel.POINTS_PER_MINUTE
                val selectedRef = viewModel.selectedTime.time
                val rangeStart = selectedRef - (visibleMinutes * 60_000).toLong()
                val rangeEnd = selectedRef + (visibleMinutes * 60_000).toLong()
                var t = (rangeStart / markerInterval) * markerInterval

                while (t <= rangeEnd) {
                    val markerDate = Date(t)
                    val x = centerX + viewModel.xOffset(markerDate) * pxPerDp
                    if (x > -10 && x < size.width + 10) {
                        val isMajor = t % majorInterval == 0L
                        if (isMajor) {
                            drawRect(
                                color = Color.White.copy(alpha = 0.15f),
                                topLeft = Offset(x - 0.5f, (trackH - majorMarkerHeightPx) / 2),
                                size = Size(1f, majorMarkerHeightPx)
                            )
                        } else {
                            val ds = TimeSliderViewModel.MARKER_DOT_SIZE
                            drawCircle(
                                color = Color.White.copy(alpha = 0.1f),
                                radius = ds / 2,
                                center = Offset(x, trackH / 2)
                            )
                        }
                    }
                    t += markerInterval
                }

                // Center indicator
                val tw = TimeSliderViewModel.SELECTION_THUMB_WIDTH
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.7f),
                    topLeft = Offset(centerX - tw / 2, (trackH - thumbHeightPx) / 2),
                    size = Size(tw, thumbHeightPx),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1f)
                )

                // Glow dot
                val glowColor = when (val s = viewModel.currentCourseState) {
                    is CourseState.InClass -> TigerDuckTheme.courseColorVibrant(s.slot.course.courseNo)
                    else -> Color.White
                }
                val gs = TimeSliderViewModel.GLOW_DOT_SIZE
                drawCircle(
                    color = glowColor,
                    radius = gs / 2,
                    center = Offset(centerX, trackH / 2)
                )
            }
    )
}

private val timeFmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
private fun dateTimeFmt() = java.time.format.DateTimeFormatter.ofPattern("M/d (EEEEE) HH:mm", Locale.getDefault())
private fun dateLabelFmt() = java.time.format.DateTimeFormatter.ofPattern("M/d (EEEEE)", Locale.getDefault())

private fun formatTimeLabel(date: Date): String {
    val instant = date.toInstant().atZone(AppConstants.TAIPEI_ZONE)
    val today = java.time.LocalDate.now(AppConstants.TAIPEI_ZONE)
    return if (instant.toLocalDate() == today) {
        timeFmt.format(instant)
    } else {
        dateTimeFmt().format(instant)
    }
}

private fun formatDateLabel(date: Date): String =
    dateLabelFmt().format(date.toInstant().atZone(AppConstants.TAIPEI_ZONE))

/**
 * Places [first] at the start and [second] at the end on a single row if both
 * fit; otherwise stacks them vertically with [first] above [second].
 */
@Composable
private fun FitOrStack(
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    minGap: Dp = 8.dp,
) {
    SubcomposeLayout(modifier) { constraints ->
        val unbounded = Constraints()
        val f = subcompose("first", first).first().measure(unbounded)
        val s = subcompose("second", second).first().measure(unbounded)
        val gap = minGap.roundToPx()
        val width = constraints.maxWidth
        if (f.width + gap + s.width <= width) {
            val h = maxOf(f.height, s.height)
            layout(width, h) {
                f.place(0, (h - f.height) / 2)
                s.place(width - s.width, (h - s.height) / 2)
            }
        } else {
            layout(width, f.height + s.height) {
                f.place(0, 0)
                s.place(0, f.height)
            }
        }
    }
}

/**
 * Renders `"$primary $separator $secondary"` on a single line when it fits;
 * otherwise stacks [primary] above [secondary] without the separator.
 */
@Composable
private fun InlineOrStackText(
    primary: String,
    secondary: String,
    style: TextStyle,
    color: Color,
    separator: String = " · ",
    modifier: Modifier = Modifier,
) {
    SubcomposeLayout(modifier) { constraints ->
        val unbounded = Constraints()
        val bounded = constraints.copy(minWidth = 0, maxHeight = Constraints.Infinity)
        val inline = subcompose("inline") {
            Text(
                primary + separator + secondary,
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }.first().measure(unbounded)

        if (inline.width <= constraints.maxWidth) {
            val w = inline.width.coerceAtMost(constraints.maxWidth)
            layout(w, inline.height) { inline.place(0, 0) }
        } else {
            val p = subcompose("primary") {
                Text(
                    primary, style = style, color = color,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }.first().measure(bounded)
            val s = subcompose("secondary") {
                Text(
                    secondary, style = style, color = color,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }.first().measure(bounded)
            layout(constraints.maxWidth, p.height + s.height) {
                p.place(0, 0)
                s.place(0, p.height)
            }
        }
    }
}
