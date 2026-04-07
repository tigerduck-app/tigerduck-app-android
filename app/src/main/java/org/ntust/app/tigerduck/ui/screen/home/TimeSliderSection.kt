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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ntust.app.tigerduck.data.model.Course
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
    sliderStyle: String,
    invertDirection: Boolean,
    skippedDates: Map<String, List<String>> = emptyMap(),
    onSkipCourse: (Course, Date) -> Unit = { _, _ -> },
    onSelectCourse: (Course) -> Unit
) {
    val sliderScope = rememberCoroutineScope()
    val viewModel = remember(sliderScope) { TimeSliderViewModel(sliderScope) }

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
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "時光機",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(Modifier.weight(1f))
            AnimatedVisibility(
                visible = viewModel.isUserDragging,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                FilledTonalButton(
                    onClick = { viewModel.returnToNow() },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("回到現在", style = MaterialTheme.typography.labelMedium)
                }
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
                when (sliderStyle) {
                    "segmentedBar" -> SegmentedBarTrack(viewModel, invertDirection)
                    else -> FluidTrack(viewModel, invertDirection)
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.size(32.dp)
                )
                Text(
                    "目前沒有課程",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
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

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        when (state) {
            is CourseState.InClass -> {
                SlotCard(
                    slot = state.slot,
                    alpha = 1f,
                    isSkipped = isSkippedFor(state.slot),
                    onSkipToggle = { onSkipCourse(state.slot.course, state.slot.date) },
                    onClick = { onSelect(state.slot.course) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            is CourseState.Between -> {
                state.previous?.let {
                    SlotCard(
                        slot = it, alpha = 0.5f,
                        isSkipped = isSkippedFor(it),
                        onClick = { onSelect(it.course) },
                        modifier = Modifier.weight(1f)
                    )
                }
                state.next?.let {
                    SlotCard(
                        slot = it, alpha = 0.5f,
                        isSkipped = isSkippedFor(it),
                        onClick = { onSelect(it.course) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            is CourseState.BeforeFirst -> {
                SlotCard(
                    slot = state.next, alpha = 0.5f,
                    isSkipped = isSkippedFor(state.next),
                    onClick = { onSelect(state.next.course) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            is CourseState.AfterLast -> {
                SlotCard(
                    slot = state.previous, alpha = 0.5f,
                    isSkipped = isSkippedFor(state.previous),
                    onClick = { onSelect(state.previous.course) },
                    modifier = Modifier.fillMaxWidth()
                )
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
    val color = TigerDuckTheme.courseColor(slot.course.courseNo)
    val cal = Calendar.getInstance()
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

    val isToday = Calendar.getInstance().let {
        val today = it.get(Calendar.DAY_OF_YEAR)
        cal.time = slot.date
        today == cal.get(Calendar.DAY_OF_YEAR) && it.get(Calendar.YEAR) == cal.get(Calendar.YEAR)
    }

    val density = LocalDensity.current
    val thresholdPx = with(density) { 100.dp.toPx() }
    val swipeOffset = remember { Animatable(0f) }
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
                        tint = if (isSkipped) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                               else Color(0xFFFF2D55),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (isSkipped) "取消翹課" else "翹課",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (isSkipped) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                else Color(0xFFFF2D55),
                        fontSize = 11.sp
                    )
                }
            }
        }

        val cardModifier = if (onSkipToggle != null) {
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(swipeOffset.value.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            coroutineScope.launch {
                                if (swipeOffset.value <= -thresholdPx) {
                                    swipeOffset.animateTo(-2000f, animationSpec = tween(durationMillis = 200))
                                    onSkipToggle()
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
            Modifier.fillMaxWidth()
        }

        Card(
            onClick = onClick,
            modifier = cardModifier,
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.15f * alpha))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row {
                    Text(
                        timeRange,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.weight(1f))
                    if (!isToday) {
                        Text(
                            formatDateLabel(slot.date),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    slot.course.courseName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (isSkipped) Color(0xFFFF2D55) else Color.Unspecified,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${slot.course.classroom} · ${slot.course.instructor}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(trackHeightDp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .pointerInput(invertDirection) {
                var lastX = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        lastX = 0f
                        viewModel.onDragStarted()
                    },
                    onDragEnd = { viewModel.onDragEnded() },
                    onDragCancel = { viewModel.onDragEnded() },
                    onHorizontalDrag = { _, dragAmount ->
                        viewModel.onDragChanged(dragAmount, invertDirection, context)
                    }
                )
            }
            .drawBehind {
                val centerX = size.width / 2
                val trackH = size.height

                // Draw course segments
                for (slot in viewModel.timeSlots) {
                    val startOff = viewModel.xOffset(slot.start)
                    val endOff = viewModel.xOffset(slot.end)
                    val segW = maxOf(
                        TimeSliderViewModel.MIN_SEGMENT_WIDTH,
                        endOff - startOff
                    )
                    val segCenterX = centerX + (startOff + endOff) / 2
                    val left = segCenterX - segW / 2

                    if (left + segW > -50 && left < size.width + 50) {
                        val isActive = viewModel.selectedTime >= slot.start && viewModel.selectedTime <= slot.end
                        val courseColor = TigerDuckTheme.courseColor(slot.course.courseNo)

                        drawRoundRect(
                            color = courseColor.copy(alpha = if (isActive) 0.5f else 0.3f),
                            topLeft = Offset(
                                left,
                                (trackH - TimeSliderViewModel.FLUID_SEGMENT_HEIGHT) / 2
                            ),
                            size = Size(segW, TimeSliderViewModel.FLUID_SEGMENT_HEIGHT),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f)
                        )
                    }
                }

                // Tick marks
                val markerInterval = (TimeSliderViewModel.MARKER_INTERVAL_MINUTES * 60_000).toLong()
                val majorInterval = (TimeSliderViewModel.MAJOR_MARKER_INTERVAL_MINUTES * 60_000).toLong()
                val visibleMinutes = size.width / TimeSliderViewModel.POINTS_PER_MINUTE
                val selectedRef = viewModel.selectedTime.time
                val rangeStart = selectedRef - (visibleMinutes * 60_000).toLong()
                val rangeEnd = selectedRef + (visibleMinutes * 60_000).toLong()
                var t = (rangeStart / markerInterval) * markerInterval

                while (t <= rangeEnd) {
                    val markerDate = Date(t)
                    val x = centerX + viewModel.xOffset(markerDate)
                    if (x > -10 && x < size.width + 10) {
                        val isMajor = t % majorInterval == 0L
                        if (isMajor) {
                            val mh = TimeSliderViewModel.MAJOR_MARKER_HEIGHT
                            drawRect(
                                color = Color.White.copy(alpha = 0.15f),
                                topLeft = Offset(x - 0.5f, (trackH - mh) / 2),
                                size = Size(1f, mh)
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
                val th = TimeSliderViewModel.SELECTION_THUMB_HEIGHT
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.7f),
                    topLeft = Offset(centerX - tw / 2, (trackH - th) / 2),
                    size = Size(tw, th),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1f)
                )

                // Glow dot
                val glowColor = when (val s = viewModel.currentCourseState) {
                    is CourseState.InClass -> TigerDuckTheme.courseColor(s.slot.course.courseNo)
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

@Composable
private fun SegmentedBarTrack(viewModel: TimeSliderViewModel, invertDirection: Boolean) {
    val context = LocalContext.current
    val barHeightDp = TimeSliderViewModel.SEGMENTED_BAR_HEIGHT.dp
    var widthPx by remember { mutableStateOf(0f) }
    val density = LocalDensity.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(barHeightDp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .pointerInput(invertDirection) {
                val scale = 3f
                detectHorizontalDragGestures(
                    onDragStart = { viewModel.onDragStarted() },
                    onDragEnd = { viewModel.onDragEnded() },
                    onDragCancel = { viewModel.onDragEnded() },
                    onHorizontalDrag = { _, dragAmount ->
                        viewModel.onDragChanged(dragAmount / scale, invertDirection, context)
                    }
                )
            }
    ) {
        if (widthPx > 0) {
            val scale = 3f
            val centerXDp = with(density) { (widthPx / 2).toDp() }

            viewModel.timeSlots.forEach { slot ->
                val startOff = viewModel.xOffset(slot.start) * scale
                val endOff = viewModel.xOffset(slot.end) * scale
                val segW = maxOf(80f, endOff - startOff)
                val segCenterX = (startOff + endOff) / 2
                val leftPx = widthPx / 2 + segCenterX - segW / 2

                if (leftPx + segW > -50 && leftPx < widthPx + 50) {
                    val isSelected = viewModel.selectedTime >= slot.start && viewModel.selectedTime <= slot.end
                    val courseColor = TigerDuckTheme.courseColor(slot.course.courseNo)
                    val segWDp = with(density) { segW.toDp() }
                    val leftDp = with(density) { leftPx.toDp() }

                    Box(
                        modifier = Modifier
                            .absoluteOffset(x = leftDp)
                            .width(segWDp)
                            .height(barHeightDp - 4.dp)
                            .align(Alignment.CenterStart)
                            .clip(RoundedCornerShape(12.dp))
                            .background(courseColor.copy(alpha = if (isSelected) 0.4f else 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = slot.course.courseName,
                            style = if (isSelected) {
                                MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                            } else {
                                MaterialTheme.typography.labelSmall
                            },
                            color = courseColor.copy(alpha = if (isSelected) 1f else 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            // Center indicator
            Box(
                modifier = Modifier
                    .absoluteOffset(x = centerXDp - 1.dp)
                    .width(2.dp)
                    .height(barHeightDp - 8.dp)
                    .align(Alignment.CenterStart)
                    .background(Color.White.copy(alpha = 0.4f))
            )
        }
    }
}

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
private val dateTimeFmt = SimpleDateFormat("M/d (EEE) HH:mm", Locale.TRADITIONAL_CHINESE)
private val dateLabelFmt = SimpleDateFormat("M/d (EEE)", Locale.TRADITIONAL_CHINESE)

private fun formatTimeLabel(date: Date): String {
    val cal = Calendar.getInstance()
    val today = cal.get(Calendar.DAY_OF_YEAR)
    val year = cal.get(Calendar.YEAR)
    cal.time = date
    return if (today == cal.get(Calendar.DAY_OF_YEAR) && year == cal.get(Calendar.YEAR)) {
        timeFmt.format(date)
    } else {
        dateTimeFmt.format(date)
    }
}

private fun formatDateLabel(date: Date): String = dateLabelFmt.format(date)
