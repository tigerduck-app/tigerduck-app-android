// Pure timetable-grid geometry, extracted from ClassTableViewModel.
//
// Deciding what a grid cell shows is the fiddliest logic in the app: a
// course can span several contiguous periods, two courses can 衝堂 into one
// cell, and three or more can chain transitively (A on 6-7, B on 6-8, C on
// 8-9 — A and C never share a period, but B bridges them, so all three must
// be laid out together). None of that needs a ViewModel, an Android
// runtime, or Hilt: it is a function of the course list and the visible
// period list, and nothing else.
//
// It lived inside the ViewModel, which meant it could not be unit-tested at
// all. Here it can be, and ClassTableCellLayoutTest does.

package org.ntust.app.tigerduck.ui.screen.classtable

import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.data.model.TimetablePeriod
import org.ntust.app.tigerduck.shared.Course

sealed class CellRole {
    object Empty : CellRole()
    data class SoloStart(val course: Course, val spanCount: Int) : CellRole()

    /**
     * Two overlapping courses occupying (possibly partially) this cluster.
     * [combinedSpan] is the total row count of the union. [offsetA]/[offsetB]
     * are 0-indexed row positions within the cluster where each course's
     * block begins. [spanA]/[spanB] are each course's own contiguous block
     * length. The L-split is drawn only on rows where both appear.
     */
    data class ConflictStart(
        val courseA: Course, val spanA: Int, val offsetA: Int,
        val courseB: Course, val spanB: Int, val offsetB: Int,
        val combinedSpan: Int
    ) : CellRole()

    /**
     * 3+ courses transitively connected by overlap — e.g. A on periods 6-7,
     * B on 6-8, C on 8-9: A and C don't share a period but B bridges them.
     * The L-split tile of [ConflictStart] only fits 2 courses, so callers
     * render this variant as vertical lanes (greedy interval-graph coloring
     * by [Member.lane] within [laneCount], same approach as the home
     * slider's 衝堂 stacking).
     */
    data class MultiConflictStart(
        val members: List<Member>,
        val combinedSpan: Int,
        val laneCount: Int,
    ) : CellRole() {
        data class Member(
            val course: Course,
            val span: Int,
            val offset: Int,
            val lane: Int,
            /** First period this course occupies — needed so the detail
             *  popup resolves the correct per-(weekday, period) room. */
            val firstPeriodId: String,
        )
    }

    object Skip : CellRole()
}

data class TripleConflictError(
    val weekday: Int,
    val periodId: String,
    val newCourseName: String,
    val existingA: Course,
    val existingB: Course,
)

object ClassTableCellLayout {

    /** Weekdays worth showing: Mon-Fri always, plus any weekend day in use. */
    fun activeWeekdays(courses: List<Course>): List<Int> {
        val days = courses.flatMap { it.schedule.keys }.toMutableSet()
        val result = (1..5).toMutableList()
        if (6 in days) result.add(6)
        if (7 in days) result.add(7)
        return result
    }

    /** Visible periods: the default set, widened to cover every period in use. */
    fun activePeriods(courses: List<Course>): List<TimetablePeriod> {
        val periodIds = AppConstants.Periods.defaultVisible.toMutableSet()
        courses.forEach { course ->
            course.schedule.values.forEach { periods -> periodIds.addAll(periods) }
        }
        val order = AppConstants.Periods.chronologicalOrder
        return order.filter { it in periodIds }.mapNotNull { TimetablePeriod.byId[it] }
    }

    fun coursesAt(courses: List<Course>, weekday: Int, period: String): List<Course> =
        courses.filter { it.schedule[weekday]?.contains(period) == true }

    /**
     * Contiguous block within [weekday] that contains [startIndex], for [course].
     * Returns (firstIndex, span). Adjacent periods in
     * [AppConstants.Periods.chronologicalOrder] count as contiguous.
     */
    fun blockFor(
        courses: List<Course>,
        periods: List<TimetablePeriod>,
        weekday: Int,
        startIndex: Int,
        course: Course,
    ): Pair<Int, Int> {
        val courseNo = course.courseNo
        // Walk backward to find the block start
        var first = startIndex
        while (first - 1 >= 0) {
            val prev = periods[first - 1]
            val prevPresent = courses.any {
                it.courseNo == courseNo && it.schedule[weekday]?.contains(prev.id) == true
            }
            if (prevPresent) first-- else break
        }
        // Walk forward to find the block end
        var last = startIndex
        while (last + 1 < periods.size) {
            val next = periods[last + 1]
            val nextPresent = courses.any {
                it.courseNo == courseNo && it.schedule[weekday]?.contains(next.id) == true
            }
            if (nextPresent) last++ else break
        }
        return first to (last - first + 1)
    }

    fun roleAt(
        courses: List<Course>,
        periods: List<TimetablePeriod>,
        weekday: Int,
        periodIndex: Int,
    ): CellRole {
        if (periodIndex < 0 || periodIndex >= periods.size) return CellRole.Empty
        val period = periods[periodIndex]
        val coursesHere = coursesAt(courses, weekday, period.id)
        if (coursesHere.isEmpty()) return CellRole.Empty

        // Build transitive closure of courses whose blocks overlap with any
        // course already in the cluster, rooted at the courses present in this
        // cell. This guarantees we emit a ConflictStart at the earliest row
        // of the union and Skip thereafter.
        val closure =
            LinkedHashMap<String, Triple<Course, Int, Int>>() // courseNo -> (course, firstIndex, span)

        fun addCourse(c: Course, seedIndex: Int) {
            if (closure.containsKey(c.courseNo)) return
            val (first, span) = blockFor(courses, periods, weekday, seedIndex, c)
            closure[c.courseNo] = Triple(c, first, span)
            // Expand: any other course touching any row in [first, first+span)
            for (i in first until first + span) {
                val pid = periods.getOrNull(i)?.id ?: continue
                for (other in coursesAt(courses, weekday, pid)) {
                    if (!closure.containsKey(other.courseNo)) addCourse(other, i)
                }
            }
        }
        coursesHere.forEach { addCourse(it, periodIndex) }

        val clusterStart = closure.values.minOf { it.second }
        if (clusterStart < periodIndex) return CellRole.Skip

        if (closure.size == 1) {
            val (course, _, span) = closure.values.first()
            return CellRole.SoloStart(course, span)
        }

        val entries = closure.values.toList()
        val clusterEnd = entries.maxOf { it.second + it.third }
        val combined = clusterEnd - clusterStart

        if (entries.size == 2) {
            val (courseA, firstA, spanA) = entries[0]
            val (courseB, firstB, spanB) = entries[1]
            return CellRole.ConflictStart(
                courseA = courseA, spanA = spanA, offsetA = firstA - clusterStart,
                courseB = courseB, spanB = spanB, offsetB = firstB - clusterStart,
                combinedSpan = combined,
            )
        }

        // 3+ courses: lay out as vertical lanes via greedy interval-graph
        // coloring (each course takes the lowest-indexed lane whose previous
        // occupant has ended). Mirrors TimeSliderViewModel.computeSlotLayouts.
        val sortedByStart = entries.sortedBy { it.second }
        val laneEnds = mutableListOf<Int>()
        val laneAssignments = IntArray(sortedByStart.size)
        for ((i, e) in sortedByStart.withIndex()) {
            val (_, first, span) = e
            val end = first + span
            var lane = -1
            for (j in laneEnds.indices) {
                if (laneEnds[j] <= first) {
                    lane = j; break
                }
            }
            if (lane < 0) {
                laneEnds.add(end)
                lane = laneEnds.size - 1
            } else {
                laneEnds[lane] = end
            }
            laneAssignments[i] = lane
        }
        val members = sortedByStart.mapIndexed { i, e ->
            val (course, first, span) = e
            val firstPeriodId = periods.getOrNull(first)?.id ?: period.id
            CellRole.MultiConflictStart.Member(
                course = course,
                span = span,
                offset = first - clusterStart,
                lane = laneAssignments[i],
                firstPeriodId = firstPeriodId,
            )
        }
        return CellRole.MultiConflictStart(
            members = members,
            combinedSpan = combined,
            laneCount = laneEnds.size,
        )
    }

    /**
     * Scans every (weekday, period) the candidate course would occupy and
     * returns the first slot that already has two courses — i.e. adding the
     * candidate would push that slot to three. Null if the add is safe.
     */
    fun findTripleConflict(courses: List<Course>, candidate: Course): TripleConflictError? {
        for ((weekday, periodIds) in candidate.schedule) {
            for (pid in periodIds) {
                val existing = coursesAt(courses, weekday, pid)
                if (existing.size >= 2) {
                    return TripleConflictError(
                        weekday = weekday,
                        periodId = pid,
                        newCourseName = candidate.courseName,
                        existingA = existing[0],
                        existingB = existing[1],
                    )
                }
            }
        }
        return null
    }
}
