package org.ntust.app.tigerduck.data.sharing

import org.ntust.app.tigerduck.data.sharing.ClassTableShareCode.Format
import org.ntust.app.tigerduck.data.sharing.ClassTableShareCode.Term
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.Periods

// TAT's share code, which the app reads in versions 1 and 2 and writes in version 2. Its layout
// is in the decision record ClassTableShareCode names.

internal const val TAT_PREFIX = "HTTPS://NTUST-TAT.WEB.APP/S/"

private const val TAT_STUDENT_ID_LENGTH = 9
private const val TAT_COURSE_NO_LENGTH = 9

/**
 * One character per period of [Periods.chronologicalOrder], in its order: the noon period, the
 * app's 5, is `N`, so `5` to `9` stand for the app's 6 to 10.
 */
private const val TAT_PERIOD_CODES = "1234N56789ABCD"

internal fun ClassTableShareCode.Companion.decodeTat(code: String): ClassTableShareCode {
    val body = code.substring(TAT_PREFIX.length)
    val magic = body.take(4)
    val format = when (magic) {
        "TAT2" -> Format.TAT2
        "TAT1" -> Format.TAT1
        else -> {
            val isVersion = magic.length == 4 && magic.startsWith("TAT") && magic[3] in '0'..'9'
            throw if (isVersion) ClassTableShareCodeError.UnknownVersion() else ClassTableShareCodeError.Malformed()
        }
    }
    val semester = body.drop(4).take(4)
    if (!isAscii(body) || !isSemester(semester)) throw ClassTableShareCodeError.Malformed()

    // A shorter ID is padded with spaces. Trimming a code with no courses takes the padding with
    // it, so the field may come up short.
    val studentId = body.drop(8).take(TAT_STUDENT_ID_LENGTH).trimEnd(' ')
    if (studentId.isNotEmpty() && !isUpperAlphanumerics(studentId)) throw ClassTableShareCodeError.Malformed()

    val courses = body.drop(8 + TAT_STUDENT_ID_LENGTH)
    val chunks = if (courses.isEmpty()) emptyList() else courses.split('-')
    val courseNos = chunks.map { chunk ->
        val parts = chunk.split('.')
        if (parts[0].length != TAT_COURSE_NO_LENGTH || !parts.drop(1).all(::isTatDay)) {
            throw ClassTableShareCodeError.Malformed()
        }
        parts[0]
    }
    return ClassTableShareCode(
        format = format,
        studentId = studentId.ifEmpty { null },
        sharedAt = null,
        terms = listOf(Term(semester, checkedCourseNos(courseNos))),
    )
}

/**
 * TAT's version 2 code for one term of the user's class table, with each course's weekdays and
 * periods. A course code TAT cannot hold, any but nine characters long, is left out; null when
 * no course is left or the student ID is longer than TAT's nine characters.
 */
fun ClassTableShareCode.Companion.encodeTat(studentId: String, semester: String, courses: List<Course>): String? {
    val id = studentId.uppercase()
    val term = semester.uppercase()
    if (id.length > TAT_STUDENT_ID_LENGTH || !isUpperAlphanumerics(id) || !isSemester(term)) return null
    val written = mutableSetOf<String>()
    val chunks = courses.mapNotNull { course ->
        val courseNo = course.courseNo.uppercase()
        if (courseNo.length != TAT_COURSE_NO_LENGTH || !CourseService.isCourseNo(courseNo) || !written.add(courseNo)) {
            return@mapNotNull null
        }
        val schedule = course.schedule
        courseNo + schedule.keys.sorted().joinToString("") { weekday -> tatDay(weekday, schedule[weekday].orEmpty()) }
    }
    if (chunks.isEmpty()) return null
    return TAT_PREFIX + "TAT2" + term + id.padEnd(TAT_STUDENT_ID_LENGTH, ' ') + chunks.joinToString("-")
}

/**
 * `.`, the weekday from `1` (Monday) to `7`, and the day's periods; empty for a weekday out of
 * range or a day with no period TAT can hold.
 */
private fun tatDay(weekday: Int, periods: List<String>): String {
    val indices = periods.map { Periods.chronologicalOrder.indexOf(it) }.filter { it >= 0 }.toSortedSet()
    if (weekday !in 1..7 || indices.isEmpty()) return ""
    return "." + weekday + indices.joinToString("") { TAT_PERIOD_CODES[it].toString() }
}

/** A weekday part: the weekday and at least one period. */
private fun isTatDay(part: String): Boolean =
    part.length > 1 && part[0] in '1'..'7' && part.drop(1).all { it in TAT_PERIOD_CODES }
