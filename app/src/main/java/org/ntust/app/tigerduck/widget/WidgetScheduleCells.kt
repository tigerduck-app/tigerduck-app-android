package org.ntust.app.tigerduck.widget

import org.ntust.app.tigerduck.data.model.Course

sealed class ScheduleCell {
    abstract val length: Int

    data class Empty(override val length: Int) : ScheduleCell()
    data class Solo(val course: Course, override val length: Int) : ScheduleCell()
    /**
     * Two courses that transitively share at least one period. Offsets and spans
     * are in period-row units within this cluster (0 = first row). `length` ==
     * `combinedSpan` so the outer layout can treat every cell kind uniformly.
     */
    data class Conflict(
        val courseA: Course,
        val spanA: Int,
        val offsetA: Int,
        val courseB: Course,
        val spanB: Int,
        val offsetB: Int,
        val combinedSpan: Int,
    ) : ScheduleCell() {
        override val length: Int get() = combinedSpan
    }
}

/**
 * Walks [activePeriodIds] for [weekday] and emits non-overlapping cells.
 * Mirrors the transitive-closure logic in ClassTableViewModel.cellRole so the
 * widget's grid matches the app's grid.
 */
fun buildScheduleCells(
    activePeriodIds: List<String>,
    courses: List<Course>,
    weekday: Int,
): List<ScheduleCell> {
    val out = mutableListOf<ScheduleCell>()
    var i = 0
    while (i < activePeriodIds.size) {
        val slot = coursesAt(courses, weekday, activePeriodIds[i])
        when {
            slot.isEmpty() -> {
                var j = i + 1
                while (j < activePeriodIds.size &&
                    coursesAt(courses, weekday, activePeriodIds[j]).isEmpty()
                ) j++
                out.add(ScheduleCell.Empty(j - i))
                i = j
            }

            slot.size == 1 -> {
                val course = slot.single()
                // Extend only while the next slot is *also* a single instance
                // of the same course. A slot that goes multi-course terminates
                // the solo run so the conflict cluster picks it up.
                var j = i + 1
                while (j < activePeriodIds.size) {
                    val next = coursesAt(courses, weekday, activePeriodIds[j])
                    if (next.size == 1 && next.single().courseNo == course.courseNo) j++
                    else break
                }
                out.add(ScheduleCell.Solo(course, j - i))
                i = j
            }

            else -> {
                val cluster = clusterStartingAt(activePeriodIds, courses, weekday, i)
                out.add(cluster)
                i += cluster.combinedSpan
            }
        }
    }
    return out
}

private fun coursesAt(courses: List<Course>, weekday: Int, periodId: String): List<Course> =
    courses.filter { it.schedule[weekday]?.contains(periodId) == true }

/**
 * Builds a conflict cluster rooted at [startIndex]. Follows transitive closure:
 * any course touching any row in the current cluster is pulled in. Caps output
 * at two courses (matches the in-app ConflictCourseCell, which also only shows
 * two); extras are dropped.
 */
private fun clusterStartingAt(
    activePeriodIds: List<String>,
    courses: List<Course>,
    weekday: Int,
    startIndex: Int,
): ScheduleCell.Conflict {
    // courseNo -> (course, firstIndexInActivePeriodIds, span)
    val closure = linkedMapOf<String, Triple<Course, Int, Int>>()

    fun spanFor(course: Course): Pair<Int, Int> {
        // First index in activePeriodIds where this course appears on this weekday.
        val schedule = course.schedule[weekday] ?: return startIndex to 0
        val hitIdxs = activePeriodIds
            .mapIndexedNotNull { idx, pid -> if (pid in schedule) idx else null }
            .filter { it >= startIndex }
        if (hitIdxs.isEmpty()) return startIndex to 0
        val first = hitIdxs.first()
        // Span = contiguous run starting at first, stopping when activePeriodIds
        // is not in schedule.
        var span = 1
        while (first + span < activePeriodIds.size &&
            activePeriodIds[first + span] in schedule
        ) span++
        return first to span
    }

    fun add(course: Course) {
        if (course.courseNo in closure) return
        val (first, span) = spanFor(course)
        if (span == 0) return
        closure[course.courseNo] = Triple(course, first, span)
        // Expand: any other course touching any row in [first, first+span)
        for (k in first until first + span) {
            val pid = activePeriodIds.getOrNull(k) ?: continue
            for (other in coursesAt(courses, weekday, pid)) {
                if (other.courseNo !in closure) add(other)
            }
        }
    }

    coursesAt(courses, weekday, activePeriodIds[startIndex]).forEach { add(it) }

    val entries = closure.values.toList().take(2)
    val (courseA, firstA, spanA) = entries[0]
    val (courseB, firstB, spanB) = entries.getOrElse(1) {
        // Degenerate: only one course after closure. Emit as a solo-length cluster.
        return ScheduleCell.Conflict(
            courseA = courseA,
            spanA = spanA,
            offsetA = 0,
            courseB = courseA,
            spanB = spanA,
            offsetB = 0,
            combinedSpan = spanA,
        )
    }

    val clusterStart = minOf(firstA, firstB)
    val clusterEnd = maxOf(firstA + spanA, firstB + spanB)
    val combined = clusterEnd - clusterStart
    return ScheduleCell.Conflict(
        courseA = courseA,
        spanA = spanA,
        offsetA = firstA - clusterStart,
        courseB = courseB,
        spanB = spanB,
        offsetB = firstB - clusterStart,
        combinedSpan = combined,
    )
}
