package org.ntust.app.tigerduck.shared.timeline

import org.json.JSONArray
import org.json.JSONObject
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.PeriodTimes
import org.ntust.app.tigerduck.shared.time.TaipeiClock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * The iPhone suite's fixture (`CourseChangesTests`): CC900 meets on Thursday 2026-11-05 in two
 * meetings, periods 2 to 4 and 7 to 8, in T4-501. Course numbers and rooms are made up.
 */
object CourseChangeFixture {
    val thursday: LocalDate = LocalDate.of(2026, 11, 5)

    fun time(text: String): LocalTime = LocalTime.parse(text)

    fun at(text: String, day: LocalDate = thursday): Instant =
        day.atTime(time(text)).atZone(TaipeiClock.zone).toInstant()

    fun course(periods: List<String> = listOf("2", "3", "4", "7", "8")): Course =
        Course.fromSchedule(courseNo = "CC900", courseName = "Compilers", classroom = "T4-501", schedule = mapOf(4 to periods))

    /** A row as the server writes it, every field present; [values] fills room, start, end and the notes. */
    fun row(
        id: Int,
        kind: String,
        periods: List<String>? = null,
        days: List<String> = listOf("2026-11-05"),
        values: Map<String, String> = emptyMap(),
        courseNo: String = "CC900",
    ): JSONObject = JSONObject().apply {
        put("id", id)
        put("course_no", courseNo)
        put("kind", kind)
        put("mode", "day")
        put("day", days.first())
        put("from", JSONObject.NULL)
        put("to", JSONObject.NULL)
        put("excluded_weekdays", JSONArray())
        put("periods", periods?.let(::JSONArray) ?: JSONObject.NULL)
        put("class_days", JSONArray(days))
        put("room", JSONObject.NULL)
        put("start", JSONObject.NULL)
        put("end", JSONObject.NULL)
        put("note_zh", JSONObject.NULL)
        put("note_en", JSONObject.NULL)
        put("updated_at", "2026-10-29T14:03:00+08:00")
        for ((key, value) in values) put(key, value)
    }

    fun feed(rows: List<Any>, term: String = "1151"): String =
        JSONObject().put("term", term).put("changes", JSONArray(rows)).toString()

    fun changes(vararg rows: JSONObject): CourseChanges = CourseChanges.decode(feed(rows.toList()))

    fun slots(changes: CourseChanges, day: LocalDate = thursday): List<ClassSlot> =
        CourseMeetings.buildSlots(listOf(course()), day, changes)

    /** The timetable time of [period]'s start or end. */
    fun timetable(period: String, end: Boolean): LocalTime {
        val times = PeriodTimes.mapping.getValue(period)
        return time(if (end) times.second else times.first)
    }
}
