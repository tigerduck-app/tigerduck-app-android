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
        if (slot.isEmpty()) {
            var j = i + 1
            while (j < activePeriodIds.size &&
                coursesAt(courses, weekday, activePeriodIds[j]).isEmpty()
            ) j++
            out.add(ScheduleCell.Empty(j - i))
            i = j
            continue
        }

        // Route every non-empty cell through the transitive closure so a solo
        // course whose block extends into a later conflict (e.g. A on periods
        // 7–9, B on 8–9) is folded into the cluster at period 7 instead of
        // being emitted first as a Solo and then re-emitted as part of the
        // Conflict. Matches ClassTableViewModel.cellRole.
        val closure = buildClosure(activePeriodIds, courses, weekday, i)
        val entries = closure.values.toList()

        if (entries.size == 1) {
            val (course, first, span) = entries.first()
            // `first` should always equal `i` — we can only reach this branch
            // through its own earliest period since we advance past every
            // previously-emitted cluster.
            out.add(ScheduleCell.Solo(course, span))
            i = first + span
        } else {
            val kept = entries.take(2)
            val (courseA, firstA, spanA) = kept[0]
            val (courseB, firstB, spanB) = kept[1]
            val clusterStart = minOf(firstA, firstB)
            val clusterEnd = maxOf(firstA + spanA, firstB + spanB)
            val combined = clusterEnd - clusterStart
            out.add(
                ScheduleCell.Conflict(
                    courseA = courseA,
                    spanA = spanA,
                    offsetA = firstA - clusterStart,
                    courseB = courseB,
                    spanB = spanB,
                    offsetB = firstB - clusterStart,
                    combinedSpan = combined,
                )
            )
            i = clusterStart + combined
        }
    }
    return out
}

private fun coursesAt(courses: List<Course>, weekday: Int, periodId: String): List<Course> =
    courses.filter { it.schedule[weekday]?.contains(periodId) == true }

/**
 * Transitive closure of courses reachable from [startIndex]. For every course
 * in the closure, records its (course, first-index-in-activePeriodIds, span)
 * covering its full contiguous block on [weekday]. Adjacent courses that touch
 * any row of an already-added course are pulled in recursively, matching
 * ClassTableViewModel.blockFor + cellRole.
 */
private fun buildClosure(
    activePeriodIds: List<String>,
    courses: List<Course>,
    weekday: Int,
    startIndex: Int,
): LinkedHashMap<String, Triple<Course, Int, Int>> {
    val closure = linkedMapOf<String, Triple<Course, Int, Int>>()

    fun spanFor(course: Course): Pair<Int, Int> {
        val schedule = course.schedule[weekday] ?: return startIndex to 0
        val hitIdxs = activePeriodIds
            .mapIndexedNotNull { idx, pid -> if (pid in schedule) idx else null }
            .filter { it >= startIndex }
        if (hitIdxs.isEmpty()) return startIndex to 0
        val first = hitIdxs.first()
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
        for (k in first until first + span) {
            val pid = activePeriodIds.getOrNull(k) ?: continue
            for (other in coursesAt(courses, weekday, pid)) {
                if (other.courseNo !in closure) add(other)
            }
        }
    }

    coursesAt(courses, weekday, activePeriodIds[startIndex]).forEach { add(it) }
    return closure
}
