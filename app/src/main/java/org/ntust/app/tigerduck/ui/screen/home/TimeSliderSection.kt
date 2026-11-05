package org.ntust.app.tigerduck.ui.screen.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.academic.QuietDays
import org.ntust.app.tigerduck.shared.timeline.CourseChanges
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.ui.component.JumpToNowChip
import org.ntust.app.tigerduck.ui.component.courseChangeInk
import org.ntust.app.tigerduck.ui.component.courseNameForDisplay
import org.ntust.app.tigerduck.ui.screen.classtable.clubs.ClubActivityDetailSheet
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.ui.theme.LocalVisualStylePolicy
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
// import java.text.SimpleDateFormat  // 翹課 (parked) — isSkippedFor's date key
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun TimeSliderSection(
    courses: List<Course>,
    quietDays: QuietDays,
    invertDirection: Boolean,
    isLoggedIn: Boolean = true,
    initialLoadComplete: Boolean = true,
    /** Signed out: where its "Sign in" button goes. */
    onSignIn: () -> Unit = {},
    /**
     * The slider's state, the time picked above all. Home holds its own, so the time outlives
     * the section's turn as an outline while Home is arranged.
     */
    viewModel: TimeSliderViewModel = remember { TimeSliderViewModel() },
    /** The school's course changes, which split, move and cancel the classes shown. */
    courseChanges: CourseChanges = CourseChanges.EMPTY,
    // 翹課 — parked until after add-friend. See HomeViewModel._skippedDates.
    // skippedDates: Map<String, List<String>> = emptyMap(),
    // onSkipCourse: (Course, Date) -> Unit = { _, _ -> },
    onSelectCourse: (CourseTimeSlot) -> Unit
) {
    LaunchedEffect(courses, quietDays, courseChanges) {
        viewModel.configure(courses, changes = courseChanges, isQuietDay = quietDays::contains)
    }

    // Tick every second, on the second: the thumb moves with the time, a second at a time.
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.tick(Date(AppClock.nowMillis()))
            kotlinx.coroutines.delay(millisToNextSecond(AppClock.nowMillis()))
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
                JumpToNowChip(
                    label = stringResource(R.string.home_time_slider_now),
                    onClick = { viewModel.returnToNow() })
            }
        }

        if (viewModel.hasItems) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CourseTimeCard(
                    state = viewModel.currentState,
                    // skippedDates = skippedDates,
                    // onSkipCourse = onSkipCourse,
                    onSelect = onSelectCourse,
                    onSelectClub = { viewModel.openedClub = it },
                )

                // Time label
                Text(
                    text = formatTimeLabel(viewModel.selectedTime),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                )

                FluidTrack(viewModel, invertDirection)
            }
        } else if (isLoggedIn && !initialLoadComplete) {
            // Cache hasn't been consulted yet — don't flash "目前沒有課程"
            // while the on-disk cache is still being read.
        } else {
            EmptyState(isLoggedIn, onSignIn)
        }
    }
    // A club card's tap opens the same details as the class table's.
    viewModel.openedClub?.let { ClubActivityDetailSheet(it, onDismiss = { viewModel.openedClub = null }) }
}

/** Nothing to scrub: signed out, or no classes and no club activities. */
@Composable
private fun EmptyState(isLoggedIn: Boolean, onSignIn: () -> Unit) {
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
                stringResource(R.string.common_sign_in_required_feature)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
        )
        if (!isLoggedIn) {
            Button(onClick = onSignIn) {
                Text(stringResource(R.string.onboarding_sign_in_button))
            }
        }
    }
}

@Composable
internal fun SlotCard(
    slot: CourseTimeSlot,
    // Beside the class the slider is on, rather than on it: a fainter card.
    muted: Boolean,
    isSkipped: Boolean = false,
    onSkipToggle: (() -> Unit)? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = TigerDuckTheme.courseColorVibrant(slot.course.courseNo)
    val timeRange = "${formatClock(slot.start)} - ${formatClock(slot.end)}"
    val strike = if (slot.isCancelled) TextDecoration.LineThrough else null

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
                .fillMaxWidth()
                .fillBoundedHeight()
                .offset { IntOffset(swipeOffset.value.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            coroutineScope.launch {
                                if (swipeOffset.value <= -thresholdPx) {
                                    swipeOffset.animateTo(
                                        -2000f,
                                        animationSpec = tween(durationMillis = 200)
                                    )
                                    latestOnSkipToggle?.invoke()
                                    swipeOffset.snapTo(0f)
                                } else {
                                    swipeOffset.animateTo(0f, animationSpec = spring())
                                }
                            }
                        },
                        onDragCancel = {
                            coroutineScope.launch {
                                swipeOffset.animateTo(
                                    0f,
                                    animationSpec = spring()
                                )
                            }
                        },
                        onHorizontalDrag = { _, delta ->
                            coroutineScope.launch {
                                swipeOffset.snapTo(
                                    (swipeOffset.value + delta * 0.6f).coerceAtMost(
                                        0f
                                    )
                                )
                            }
                        }
                    )
                }
        } else {
            Modifier
                .fillMaxWidth()
                .fillBoundedHeight()
        }

        TimeSliderCardSurface(
            tint = color,
            muted = muted,
            date = slot.date,
            onClick = onClick,
            modifier = cardModifier,
            time = { ink ->
                val metaBoldStyle = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                if (slot.hasChangedTime && !slot.isCancelled) {
                    val changeInk = courseChangeInk()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Schedule, contentDescription = null, tint = changeInk, modifier = Modifier.size(11.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(timeRange, style = metaBoldStyle, color = changeInk)
                    }
                } else {
                    Text(timeRange, style = metaBoldStyle, color = ink.meta, textDecoration = strike)
                }
            },
        ) { ink ->
            val metaStyle = MaterialTheme.typography.labelSmall
            Text(
                courseNameForDisplay(slot.course.displayName, maxChars = 30),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = if (isSkipped) Color(0xFFFF2D55) else ink.name,
                textDecoration = strike,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (slot.isCancelled) {
                Text(
                    stringResource(R.string.course_card_cancelled),
                    style = metaStyle,
                    color = ink.meta,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            } else {
                InlineOrStackText(
                    primary = if (slot.hasChangedRoom) movedRoomText(slot.classroom, courseChangeInk()) else AnnotatedString(slot.classroom),
                    secondary = slot.course.instructor,
                    style = metaStyle,
                    color = ink.meta,
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
    var widthPx by remember { mutableFloatStateOf(0f) }

    val pxPerDp = with(density) { 1.dp.toPx() }
    val style = LocalVisualStylePolicy.current
    val activeOutline = style.timeSliderActiveOutline
    val activeOutlineWidthPx = with(density) { 1.5.dp.toPx() }
    val cancelledBorderPx = with(density) { 1.dp.toPx() }
    val cancelledDash = remember(density) {
        with(density) { PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())) }
    }
    val a11yLabel = stringResource(R.string.home_time_slider_title)
    val a11ySwipeHint = stringResource(R.string.a11y_time_slider_swipe_hint)
    val selectedTimeLabel = formatTimeLabel(viewModel.selectedTime)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(trackHeightDp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .semantics {
                contentDescription = "$a11yLabel. $a11ySwipeHint"
                stateDescription = selectedTimeLabel
            }
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
                val layouts = viewModel.slotLayouts
                val cornerRadiusPx = 4f

                // Draw the segments, a class's or a club's
                for (slot in viewModel.items) {
                    val startOff = viewModel.xOffset(slot.start) * pxPerDp
                    val endOff = viewModel.xOffset(slot.end) * pxPerDp
                    val segW = maxOf(
                        minSegWidthPx,
                        endOff - startOff
                    )
                    val segCenterX = centerX + (startOff + endOff) / 2
                    val left = segCenterX - segW / 2

                    if (left + segW > -50 && left < size.width + 50) {
                        val isActive = slot.isOn(viewModel.selectedTime)
                        val courseColor = slot.tint

                        // 衝堂: lanes meet flush inside the same band, so the
                        // bar still reads as one block with stacked colours.
                        // Outer corners round; inner edges are sharp.
                        val layout = layouts[slot.id]
                        val laneCount = layout?.laneCount ?: 1
                        val lane = layout?.lane ?: 0
                        val laneH = segHeightPx / laneCount
                        val bandTop = (trackH - segHeightPx) / 2
                        val laneTop = bandTop + lane * laneH

                        val isTop = lane == 0
                        val isBottom = lane == laneCount - 1
                        val topR = if (isTop) cornerRadiusPx else 0f
                        val bottomR = if (isBottom) cornerRadiusPx else 0f
                        // The lane's rounded box, drawn [inset] inside its edges.
                        fun laneShape(inset: Float) = androidx.compose.ui.graphics.Path().apply {
                            val topCorner = (topR - inset).coerceAtLeast(0f)
                            val bottomCorner = (bottomR - inset).coerceAtLeast(0f)
                            addRoundRect(
                                androidx.compose.ui.geometry.RoundRect(
                                    left = left + inset,
                                    top = laneTop + inset,
                                    right = left + segW - inset,
                                    bottom = laneTop + laneH - inset,
                                    topLeftCornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                        topCorner,
                                        topCorner
                                    ),
                                    topRightCornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                        topCorner,
                                        topCorner
                                    ),
                                    bottomLeftCornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                        bottomCorner,
                                        bottomCorner
                                    ),
                                    bottomRightCornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                        bottomCorner,
                                        bottomCorner
                                    ),
                                )
                            )
                        }
                        if (slot.isCancelled) {
                            // A cancelled class: a faint fill in a dashed outline, as on iOS.
                            drawPath(path = laneShape(0f), color = courseColor.copy(alpha = 0.06f))
                            drawPath(
                                path = laneShape(cancelledBorderPx / 2),
                                color = courseColor.copy(alpha = 0.6f),
                                style = Stroke(width = cancelledBorderPx, pathEffect = cancelledDash),
                            )
                            continue
                        }
                        drawPath(
                            path = laneShape(0f),
                            color = style.timeSliderSegmentColor(
                                courseColor,
                                tintAlpha = TigerDuckTheme.tintAlpha(if (isActive) 0.5f else 0.3f),
                            ),
                        )
                        if (isActive && activeOutline != null) {
                            // Inside the lane, so it stays clear of a 衝堂 lane next to it.
                            drawPath(
                                path = laneShape(activeOutlineWidthPx / 2),
                                color = activeOutline,
                                style = Stroke(width = activeOutlineWidthPx),
                            )
                        }
                    }
                }

                // Tick marks
                val markerInterval = (TimeSliderViewModel.MARKER_INTERVAL_MINUTES * 60_000).toLong()
                val majorInterval =
                    (TimeSliderViewModel.MAJOR_MARKER_INTERVAL_MINUTES * 60_000).toLong()
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
                val glowColor = viewModel.thumbItem?.let { style.timeSliderInClassDotColor(it.tint) } ?: Color.White
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
private fun dateTimeFmt() =
    java.time.format.DateTimeFormatter.ofPattern("M/d (EEEEE) HH:mm", appLocale())

private fun formatTimeLabel(date: Date): String {
    val instant = date.toInstant().atZone(AppConstants.TAIPEI_ZONE)
    val today = AppClock.localDateTime().toLocalDate()
    return if (instant.toLocalDate() == today) {
        timeFmt.format(instant)
    } else {
        dateTimeFmt().format(instant)
    }
}

private fun formatClock(date: Date): String = timeFmt.format(date.toInstant().atZone(AppConstants.TAIPEI_ZONE))

/** `⇄ room`, semibold in the change ink, for a class the school moved to [room]. As iOS `CourseTimeCard`. */
private fun movedRoomText(room: String, ink: Color): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = ink, fontWeight = FontWeight.SemiBold)) {
        appendInlineContent(MOVED_ROOM_ICON, "⇄")
        append(" ")
        append(room)
    }
}

private const val MOVED_ROOM_ICON = "moved-room"

/** The `⇄` of [movedRoomText], drawn as the class table draws it. */
@Composable
private fun movedRoomIcon(style: TextStyle): Map<String, InlineTextContent> {
    val ink = courseChangeInk()
    return mapOf(
        MOVED_ROOM_ICON to InlineTextContent(
            Placeholder(style.fontSize, style.fontSize, PlaceholderVerticalAlign.TextCenter),
        ) {
            Icon(Icons.Filled.SwapHoriz, contentDescription = null, tint = ink, modifier = Modifier.fillMaxSize())
        },
    )
}

/**
 * Renders `"$primary $separator $secondary"` on a single line when it fits;
 * otherwise stacks [primary] above [secondary] without the separator.
 */
@Composable
private fun InlineOrStackText(
    primary: AnnotatedString,
    secondary: String,
    style: TextStyle,
    color: Color,
    separator: String = " · ",
    modifier: Modifier = Modifier,
) {
    val inlineContent = movedRoomIcon(style)
    SubcomposeLayout(modifier) { constraints ->
        val unbounded = Constraints()
        val bounded = constraints.copy(minWidth = 0, maxHeight = Constraints.Infinity)
        val inline = subcompose("inline") {
            Text(
                primary + AnnotatedString(separator + secondary),
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                inlineContent = inlineContent,
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
                    inlineContent = inlineContent,
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
