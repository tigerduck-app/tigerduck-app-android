package org.ntust.app.tigerduck.ui.screen.home

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.ntust.app.tigerduck.data.model.Course
import kotlinx.coroutines.*
import java.util.Calendar
import java.util.Date
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

class TimeSliderViewModel(private val scope: CoroutineScope) {

    var timeSlots by mutableStateOf<List<CourseTimeSlot>>(emptyList())
        private set
    var selectedTime by mutableStateOf(Date())
    var isUserDragging by mutableStateOf(false)
        private set

    val hasCourses: Boolean get() = timeSlots.isNotEmpty()

    private var allCourses: List<Course> = emptyList()
    private var timelineCenterDate: Date = Date()
    private var lastHapticSlot: Int = 0
    private var autoReturnJob: Job? = null

    // Compressed position cache
    private var anchors: List<Pair<Date, Float>> = emptyList()

    fun configure(courses: List<Course>) {
        allCourses = courses
        rebuildTimeline(Date())
        if (!isUserDragging) {
            selectedTime = Date()
        }
    }

    private fun rebuildTimeline(center: Date) {
        timelineCenterDate = center
        timeSlots = CourseTimeSlot.buildMultiDaySlots(allCourses, center, TIMELINE_DAY_RADIUS)
        rebuildAnchors()
    }

    private fun rebuildAnchors() {
        if (timeSlots.isEmpty()) { anchors = emptyList(); return }

        val result = mutableListOf<Pair<Date, Float>>()
        var x = 0f

        // Padding before first slot
        val paddingBefore = compressedGapWidth(60.0)
        result.add(Date(timeSlots[0].start.time - 3600_000L) to x)
        x += paddingBefore

        for ((i, slot) in timeSlots.withIndex()) {
            result.add(slot.start to x)

            val durationMin = (slot.end.time - slot.start.time) / 60_000.0
            x += (durationMin * POINTS_PER_MINUTE).toFloat()

            result.add(slot.end to x)

            if (i + 1 < timeSlots.size) {
                val next = timeSlots[i + 1]
                val gapMin = (next.start.time - slot.end.time) / 60_000.0

                val cal = Calendar.getInstance(org.ntust.app.tigerduck.AppConstants.TAIPEI_TZ)
                cal.time = slot.date
                val slotDay = cal.get(Calendar.DAY_OF_YEAR)
                cal.time = next.date
                val nextDay = cal.get(Calendar.DAY_OF_YEAR)

                x += if (slotDay != nextDay) DAY_BOUNDARY_GAP else compressedGapWidth(gapMin)
            }
        }

        // Padding after last slot
        x += compressedGapWidth(60.0)
        result.add(Date(timeSlots.last().end.time + 3600_000L) to x)

        anchors = result
    }

    private fun compressedGapWidth(minutes: Double): Float {
        if (minutes <= 0) return MIN_GAP
        val linear = (minutes * POINTS_PER_MINUTE).toFloat()
        val compressed = (ln(1 + minutes / LOG_REF_MINUTES) * POINTS_PER_MINUTE * LOG_REF_MINUTES).toFloat()
        return min(max(min(linear, compressed), MIN_GAP), MAX_GAP)
    }

    fun tick(now: Date) {
        if (!isUserDragging) {
            selectedTime = now
        }
        checkTimelineRebuildNeeded()
    }

    private fun checkTimelineRebuildNeeded() {
        val diffDays = abs(timelineCenterDate.time - selectedTime.time) / (24 * 60 * 60 * 1000L)
        if (diffDays >= TIMELINE_DAY_RADIUS - REBUILD_TRIGGER_DAYS) {
            rebuildTimeline(selectedTime)
        }
    }

    fun courseState(at: Date): CourseState {
        for (slot in timeSlots) {
            if (at >= slot.start && at <= slot.end) {
                return CourseState.InClass(slot)
            }
        }
        val previous = timeSlots.lastOrNull { it.end <= at }
        val next = timeSlots.firstOrNull { it.start > at }

        return when {
            previous == null && next != null -> CourseState.BeforeFirst(next)
            previous != null && next == null -> CourseState.AfterLast(previous)
            else -> CourseState.Between(previous, next)
        }
    }

    val currentCourseState: CourseState get() = courseState(selectedTime)

    // Compressed X offset
    fun xOffset(time: Date): Float {
        val timeX = interpolateX(time)
        val selectedX = interpolateX(selectedTime)
        return timeX - selectedX
    }

    private fun interpolateX(time: Date): Float {
        if (anchors.size < 2) return 0f

        val first = anchors.first()
        if (time <= first.first) {
            val dist = (first.first.time - time.time) / 60_000.0
            return first.second - (dist * POINTS_PER_MINUTE).toFloat()
        }

        val last = anchors.last()
        if (time >= last.first) {
            val dist = (time.time - last.first.time) / 60_000.0
            return last.second + (dist * POINTS_PER_MINUTE).toFloat()
        }

        for (i in 0 until anchors.size - 1) {
            val a = anchors[i]
            val b = anchors[i + 1]
            if (time >= a.first && time <= b.first) {
                val totalMs = (b.first.time - a.first.time).toFloat()
                if (totalMs <= 0) return a.second
                val fraction = (time.time - a.first.time) / totalMs
                return a.second + fraction * (b.second - a.second)
            }
        }
        return 0f
    }

    fun onDragStarted() {
        isUserDragging = true
        autoReturnJob?.cancel()
        lastHapticSlot = hapticSlot(selectedTime)
    }

    fun onDragChanged(dx: Float, invertDirection: Boolean, context: Context?) {
        if (!isUserDragging) onDragStarted()
        autoReturnJob?.cancel()
        val direction = if (invertDirection) 1f else -1f

        val currentX = interpolateX(selectedTime)
        val newX = currentX + direction * dx
        selectedTime = interpolateTime(newX)

        val currentSlot = hapticSlot(selectedTime)
        if (currentSlot != lastHapticSlot) {
            context?.let { performHaptic(it) }
            lastHapticSlot = currentSlot
        }

        checkTimelineRebuildNeeded()
    }

    private fun interpolateTime(x: Float): Date {
        if (anchors.size < 2) return selectedTime

        val first = anchors.first()
        if (x <= first.second) {
            val dist = first.second - x
            val minutes = dist / POINTS_PER_MINUTE
            return Date(first.first.time - (minutes * 60_000).toLong())
        }

        val last = anchors.last()
        if (x >= last.second) {
            val dist = x - last.second
            val minutes = dist / POINTS_PER_MINUTE
            return Date(last.first.time + (minutes * 60_000).toLong())
        }

        for (i in 0 until anchors.size - 1) {
            val a = anchors[i]
            val b = anchors[i + 1]
            if (x >= a.second && x <= b.second) {
                val totalX = b.second - a.second
                if (totalX <= 0) return a.first
                val fraction = (x - a.second) / totalX
                val totalMs = b.first.time - a.first.time
                return Date(a.first.time + (fraction * totalMs).toLong())
            }
        }
        return selectedTime
    }

    private fun hapticSlot(time: Date): Int {
        val intervalMs = HAPTIC_INTERVAL_MINUTES * 60_000
        return floor(time.time.toDouble() / intervalMs).toInt()
    }

    fun onDragEnded() {
        startAutoReturn()
    }

    fun returnToNow() {
        autoReturnJob?.cancel()
        isUserDragging = false
        selectedTime = Date()
    }

    private fun startAutoReturn() {
        autoReturnJob?.cancel()
        autoReturnJob = scope.launch {
            delay(5000)
            isUserDragging = false
            selectedTime = Date()
        }
    }

    private fun performHaptic(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                mgr?.defaultVibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Exception) { }
    }

    companion object {
        const val POINTS_PER_MINUTE = 0.9f
        const val LOG_REF_MINUTES = 30.0
        const val MIN_GAP = 20f
        const val MAX_GAP = 80f
        const val DAY_BOUNDARY_GAP = 40f
        const val TIMELINE_DAY_RADIUS = 28
        const val REBUILD_TRIGGER_DAYS = 7
        const val HAPTIC_INTERVAL_MINUTES = 15.0

        const val FLUID_TRACK_HEIGHT = 36f
        const val FLUID_SEGMENT_HEIGHT = 20f
        const val SEGMENTED_BAR_HEIGHT = 44f
        const val MIN_SEGMENT_WIDTH = 28f
        const val SELECTION_THUMB_WIDTH = 2f
        const val SELECTION_THUMB_HEIGHT = 28f
        const val GLOW_DOT_SIZE = 8f
        const val MARKER_INTERVAL_MINUTES = 15.0
        const val MAJOR_MARKER_INTERVAL_MINUTES = 60.0
        const val MARKER_DOT_SIZE = 3f
        const val MAJOR_MARKER_HEIGHT = 14f
    }
}
