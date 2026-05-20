package org.ntust.app.tigerduck.ui.screen.home

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.ntust.app.tigerduck.data.model.Course
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.ui.haptics.HapticScenario
import org.ntust.app.tigerduck.ui.haptics.Haptics
import java.util.Calendar
import java.util.Date
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** Layout info for a single [CourseTimeSlot] inside a 衝堂 overlap cluster. */
data class SlotLayout(val lane: Int, val laneCount: Int)

class TimeSliderViewModel {

    var timeSlots by mutableStateOf<List<CourseTimeSlot>>(emptyList())
        private set
    /**
     * Per-slot lane assignment for vertical stacking when courses 衝堂.
     * Keyed by [CourseTimeSlot.id]; missing entries mean a solo slot
     * (laneCount = 1).
     */
    var slotLayouts by mutableStateOf<Map<String, SlotLayout>>(emptyMap())
        private set
    var selectedTime by mutableStateOf(Date(AppClock.nowMillis()))
    var isUserDragging by mutableStateOf(false)
        private set

    val hasCourses: Boolean get() = timeSlots.isNotEmpty()

    private var allCourses: List<Course> = emptyList()
    private var timelineCenterDate: Date = Date(AppClock.nowMillis())
    private var lastHapticSlot: Int = 0

    // Compressed position cache
    private var anchors: List<Pair<Date, Float>> = emptyList()

    fun configure(courses: List<Course>) {
        allCourses = courses
        rebuildTimeline(Date(AppClock.nowMillis()))
        if (!isUserDragging) {
            selectedTime = Date(AppClock.nowMillis())
        }
    }

    private fun rebuildTimeline(center: Date) {
        timelineCenterDate = center
        timeSlots = CourseTimeSlot.buildMultiDaySlots(allCourses, center, TIMELINE_DAY_RADIUS)
        slotLayouts = computeSlotLayouts(timeSlots)
        rebuildAnchors()
    }

    /**
     * Anchors are emitted per *overlap cluster* rather than per slot so 衝堂
     * (two simultaneous classes) don't get smeared sideways into a fake
     * sequential range. Within a cluster, linear x = (t - clusterStart) ·
     * POINTS_PER_MINUTE, so individual slots interpolate to the right
     * x-range and the renderer stacks them on separate lanes.
     */
    private fun rebuildAnchors() {
        if (timeSlots.isEmpty()) {
            anchors = emptyList(); return
        }

        val clusters = buildClusters(timeSlots)
        val result = mutableListOf<Pair<Date, Float>>()
        var x = 0f

        // Padding before first cluster
        val paddingBefore = compressedGapWidth(60.0)
        result.add(Date(clusters[0].start.time - 3600_000L) to x)
        x += paddingBefore

        for ((i, cluster) in clusters.withIndex()) {
            result.add(cluster.start to x)

            val durationMin = (cluster.end.time - cluster.start.time) / 60_000.0
            x += (durationMin * POINTS_PER_MINUTE).toFloat()

            result.add(cluster.end to x)

            if (i + 1 < clusters.size) {
                val next = clusters[i + 1]
                val gapMin = (next.start.time - cluster.end.time) / 60_000.0

                val cal = Calendar.getInstance(org.ntust.app.tigerduck.AppConstants.TAIPEI_TZ)
                cal.time = cluster.date
                val slotDay = cal.get(Calendar.DAY_OF_YEAR)
                cal.time = next.date
                val nextDay = cal.get(Calendar.DAY_OF_YEAR)

                x += if (slotDay != nextDay) DAY_BOUNDARY_GAP else compressedGapWidth(gapMin)
            }
        }

        // Padding after last cluster
        x += compressedGapWidth(60.0)
        result.add(Date(clusters.last().end.time + 3600_000L) to x)

        anchors = result
    }

    /**
     * Groups time-sorted slots into overlap clusters. A new cluster starts
     * once a slot begins at or after the current cluster's running max-end.
     */
    private fun buildClusters(slots: List<CourseTimeSlot>): List<TimeCluster> {
        val out = mutableListOf<TimeCluster>()
        var members = mutableListOf(slots[0])
        var curEnd = slots[0].end
        for (i in 1 until slots.size) {
            val s = slots[i]
            if (s.start.time >= curEnd.time) {
                out.add(TimeCluster(members[0].start, curEnd, members[0].date, members))
                members = mutableListOf(s)
                curEnd = s.end
            } else {
                members.add(s)
                if (s.end.time > curEnd.time) curEnd = s.end
            }
        }
        out.add(TimeCluster(members[0].start, curEnd, members[0].date, members))
        return out
    }

    /**
     * Greedy interval-graph coloring per cluster: each slot takes the
     * lowest-indexed lane whose previous occupant has already ended.
     * Solo slots (clusters of size 1) are omitted — callers treat a
     * missing entry as `SlotLayout(0, 1)`.
     */
    private fun computeSlotLayouts(slots: List<CourseTimeSlot>): Map<String, SlotLayout> {
        if (slots.isEmpty()) return emptyMap()
        val out = mutableMapOf<String, SlotLayout>()
        for (cluster in buildClusters(slots)) {
            if (cluster.slots.size <= 1) continue
            val laneEnds = mutableListOf<Long>()
            val laneOf = IntArray(cluster.slots.size)
            for ((i, s) in cluster.slots.withIndex()) {
                var lane = -1
                for (j in laneEnds.indices) {
                    if (laneEnds[j] <= s.start.time) { lane = j; break }
                }
                if (lane < 0) {
                    laneEnds.add(s.end.time)
                    lane = laneEnds.size - 1
                } else {
                    laneEnds[lane] = s.end.time
                }
                laneOf[i] = lane
            }
            val count = laneEnds.size
            for ((i, s) in cluster.slots.withIndex()) {
                out[s.id] = SlotLayout(laneOf[i], count)
            }
        }
        return out
    }

    private data class TimeCluster(
        val start: Date,
        val end: Date,
        val date: Date,
        val slots: List<CourseTimeSlot>,
    )

    private fun compressedGapWidth(minutes: Double): Float {
        if (minutes <= 0) return MIN_GAP
        val linear = (minutes * POINTS_PER_MINUTE).toFloat()
        val compressed =
            (ln(1 + minutes / LOG_REF_MINUTES) * POINTS_PER_MINUTE * LOG_REF_MINUTES).toFloat()
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
        lastHapticSlot = hapticSlot(selectedTime)
    }

    fun onDragChanged(dx: Float, invertDirection: Boolean, context: Context?) {
        if (!isUserDragging) onDragStarted()
        val direction = if (invertDirection) 1f else -1f

        val currentX = interpolateX(selectedTime)
        val newX = currentX + direction * dx
        selectedTime = interpolateTime(newX)

        val currentSlot = hapticSlot(selectedTime)
        if (currentSlot != lastHapticSlot) {
            context?.let {
                Haptics.perform(
                    it,
                    HapticScenario.TimeSliderTick,
                )
            }
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
        // Keep the user's selected time in place. The 現在 button remains
        // visible (isUserDragging stays true) until the user taps it.
    }

    fun returnToNow() {
        isUserDragging = false
        selectedTime = Date(AppClock.nowMillis())
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
        const val FLUID_SEGMENT_HEIGHT = 18f
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
