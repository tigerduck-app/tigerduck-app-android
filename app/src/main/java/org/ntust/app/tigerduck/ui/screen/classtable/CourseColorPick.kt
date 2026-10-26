package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.theme.pickFreeCourseColor
import java.util.Locale

/**
 * A colour pick on the class table: the picked course takes the colour, and
 * every other course pinned to it moves to a colour of its own.
 *
 * The moved course used to lose its pin and take whatever colour the palette
 * gave it here, while the server kept it on the picked colour: other devices
 * showed the two courses alike, and this one took the duplicate back at its
 * next sync. It is pinned to its new colour now, and reported in
 * [Result.changed] so the server hears of it too — as the iPhone app does
 * since 2.4.0 (tigerduck-app/tigerduck-app#252,
 * tigerduck-app/tigerduck-app-android#240).
 *
 * Every colour that comes out of it is a concrete hex, because the backend's
 * override PATCH reads a missing or null `color_hex` as "unchanged": a course
 * dropped back to its default colour is pinned to the colour that default
 * resolves to, and that is what the server is sent.
 */
internal object CourseColorPick {

    /**
     * [courses] after the pick of [courseNo]; [hex], the colour it now has;
     * and every course whose colour changed, with its new colour.
     */
    data class Result(
        val courses: List<Course>,
        val courseNo: String,
        val hex: String,
        val changed: Map<String, String>,
    ) {
        /**
         * The colour PATCHes the pick sends, by course number: the picked
         * course, always, then every other course it moved, in course-number
         * order. Left out, a moved course stayed on the picked colour on the
         * server.
         */
        val toSync: List<Pair<String, String>>
            get() = listOf(courseNo to hex) + changed.filterKeys { it != courseNo }.toSortedMap().toList()
    }

    /**
     * [courses] with [hex] ("#RRGGBB") on [courseNo], or with null the colour
     * the palette gives it among the colours the others show. Another course
     * pinned to [hex] moves to the colour the palette gives it among every
     * colour still shown, in course-number order, so it never lands on a
     * colour a third course shows. [shown] is the colour each course shows
     * now.
     */
    fun assign(
        courses: List<Course>,
        courseNo: String,
        hex: String?,
        shown: (String) -> Color,
    ): Result {
        val picked = hex?.uppercase()
        val displaced = if (picked == null) {
            emptyList()
        } else {
            courses.filter { it.courseNo != courseNo && it.customColorHex?.uppercase() == picked }
                .map { it.courseNo }
                .distinct()
                .sorted()
        }
        val taken = courses
            .filter { it.courseNo != courseNo && it.courseNo !in displaced }
            .mapTo(mutableSetOf()) { shown(it.courseNo) }
        val applied = picked ?: formatHex(pickFreeCourseColor(courseNo, taken))
        parseHex(applied)?.let(taken::add)
        val moved = mutableMapOf<String, String>()
        for (no in displaced) {
            val color = pickFreeCourseColor(no, taken)
            taken += color
            moved[no] = formatHex(color)
        }

        val changed = mutableMapOf<String, String>()
        val updated = courses.map { course ->
            val newHex = when (course.courseNo) {
                courseNo -> applied
                in moved -> moved.getValue(course.courseNo)
                else -> return@map course
            }
            if (course.customColorHex?.uppercase() != newHex) changed[course.courseNo] = newHex
            course.copy(customColorHex = newHex)
        }
        return Result(updated, courseNo, applied, changed)
    }

    /**
     * The colour each of [courses] shows: its pin, or the colour the palette
     * gives it among the pins, as the class table assigns them
     * ([org.ntust.app.tigerduck.ui.theme.buildCourseColorAssignments]). Here
     * because that one parses the pins through the platform, which a JVM test
     * of a sync cannot run.
     */
    fun shownColors(courses: List<Course>): (String) -> Color {
        val shown = mutableMapOf<String, Color>()
        for (course in courses) {
            course.customColorHex?.let(::parseHex)?.let { shown[course.courseNo] = it }
        }
        val taken = shown.values.toMutableSet()
        for (course in courses.filter { it.courseNo !in shown }.sortedBy { it.courseNo }) {
            val color = pickFreeCourseColor(course.courseNo, taken)
            shown[course.courseNo] = color
            taken += color
        }
        return { no -> shown[no] ?: pickFreeCourseColor(no, taken) }
    }

    private fun parseHex(hex: String): Color? =
        hex.removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }

    private fun formatHex(color: Color): String =
        String.format(Locale.ROOT, "#%06X", color.toArgb() and 0xFFFFFF)
}
