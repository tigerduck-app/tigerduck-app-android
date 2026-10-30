package org.ntust.app.tigerduck.coursechanges

import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.coursechanges.CourseChange.Kind
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.blockClassroom
import org.ntust.app.tigerduck.shared.contiguousPeriodRuns
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale

/**
 * One class of a course on one day as the school changed it: a fragment of a timetable meeting,
 * or a whole meeting merged back for the class table and the course detail. [start] and [end]
 * already hold a changed time. Ported from iOS `CourseTimeSlot`.
 */
data class ClassSlot(
    /** `courseNo_yyyyMMdd_firstPeriod`; the course number may itself hold underscores. */
    val id: String,
    val course: Course,
    val date: LocalDate,
    val periods: List<String>,
    val start: LocalTime,
    val end: LocalTime,
    /** The school's changes to this class. */
    val changes: List<CourseChange> = emptyList(),
    /** The timetable's time, kept only when a change moved it. */
    val originalStart: LocalTime? = null,
    val originalEnd: LocalTime? = null,
) {
    /** 1 = Monday … 7 = Sunday, as course schedules are keyed. */
    val weekday: Int get() = date.dayOfWeek.value

    val startsAt: Instant get() = date.atTime(start).atZone(AppConstants.TAIPEI_ZONE).toInstant()

    val endsAt: Instant get() = date.atTime(end).atZone(AppConstants.TAIPEI_ZONE).toInstant()

    val isCancelled: Boolean get() = changes.any { it.kind == Kind.CANCELLED }

    val notes: List<CourseChange> get() = changes.filter { it.kind == Kind.NOTE }

    val hasChangedRoom: Boolean get() = changes.any { it.kind == Kind.ROOM }

    val hasChangedTime: Boolean get() = originalStart != null || originalEnd != null

    /** The changed room, else the timetable's room for these periods. */
    val room: String
        get() = changes.firstOrNull { it.kind == Kind.ROOM }?.room ?: course.blockClassroom(weekday, periods)
}

/** A day with changes and its changed meetings. Ported from iOS `CourseTimeSlot.upcomingChanges`. */
data class ChangedDay(val day: LocalDate, val meetings: List<ClassSlot>)

/**
 * A course's meetings on a day under the school's changes, and the classes they split into.
 *
 * A meeting is one run of consecutive timetable periods. A new start or end moves the meeting as a
 * whole; a cancellation or a room covers only its own periods, so a meeting splits into fragments
 * where those differ. The time surfaces (Home, widgets, Live Activity) run on fragments, so part of
 * a meeting can be cancelled while the rest still meets; the class table and the course detail show
 * the merged meeting, cancelled only when every period is. Ported from iOS `CourseTimeSlot`.
 */
object CourseMeetings {
    /** No class skipped by the user. */
    val NO_SKIPS: (Course, LocalDate) -> Boolean = { _, _ -> false }

    /** Every class of [courses] on [date], fragments included, by start. */
    fun buildSlots(courses: List<Course>, date: LocalDate, changes: CourseChanges): List<ClassSlot> =
        courses
            .flatMap { course -> meetings(course, date, changes).flatMap { it.slots() } }
            .sortedBy { it.start }

    /** The classes that meet on [date]: none on a day classes do not meet, and none skipped or cancelled. */
    fun meetingSlots(
        courses: List<Course>,
        date: LocalDate,
        changes: CourseChanges,
        isClassDay: (LocalDate) -> Boolean,
        skipped: (Course, LocalDate) -> Boolean = NO_SKIPS,
    ): List<ClassSlot> {
        if (!isClassDay(date)) return emptyList()
        return buildSlots(courses, date, changes).filter { !it.isCancelled && !skipped(it.course, date) }
    }

    /**
     * The meetings overlapping [periods] on [date] as one slot, fragments merged back. A cancellation
     * of some of its periods leaves it standing; a room or note over any applies to all of it.
     */
    fun meeting(course: Course, periods: List<String>, date: LocalDate, changes: CourseChanges): ClassSlot? =
        merged(meetings(course, date, changes).filter { meeting -> meeting.periods.any { it in periods } })

    /** Each meeting on [date] with a change, merged as the class table shows it. */
    fun changedMeetings(course: Course, date: LocalDate, changes: CourseChanges): List<ClassSlot> =
        meetings(course, date, changes)
            .mapNotNull { merged(listOf(it)) }
            .filter { it.changes.isNotEmpty() }

    /** The classes of each meeting moved, cancelled or given a room on [date], as the day's slots show them. */
    fun changedClasses(course: Course, date: LocalDate, changes: CourseChanges): List<ClassSlot> =
        meetings(course, date, changes)
            .filter { meeting -> meeting.changes.any { it.kind != Kind.NOTE } }
            .flatMap { it.slots() }

    /** The feed's days on or after [from] with a changed meeting of [course], earliest first. */
    fun upcomingChanges(course: Course, from: LocalDate, changes: CourseChanges): List<ChangedDay> =
        changes.days(course.courseNo)
            .filter { it >= from }
            .map { ChangedDay(it, changedMeetings(course, it, changes)) }
            .filter { it.meetings.isNotEmpty() }

    /**
     * Whether the class [slotId] names is skipped by the user or cancelled. Only the id's own period
     * is checked, as iOS does.
     */
    fun isSkipped(
        slotId: String,
        courses: List<Course>,
        changes: CourseChanges,
        skipped: (Course, LocalDate) -> Boolean = NO_SKIPS,
    ): Boolean {
        val id = SlotId.parse(slotId) ?: return false
        return courses.any { it.courseNo == id.courseNo && skipped(it, id.day) } ||
            changes.isCancelled(id.courseNo, id.day, id.period)
    }

    /**
     * Whether the class [slotId] names is over at [now] by its changed times: its own piece when one
     * still has that id (#905), or else every piece of its meeting that is not cancelled, as a change
     * can drop the piece an activity began with (#904).
     */
    fun hasEnded(slotId: String, courses: List<Course>, now: Instant, changes: CourseChanges): Boolean {
        val id = SlotId.parse(slotId) ?: return false
        return courses.filter { it.courseNo == id.courseNo }.any { course ->
            val meeting = meetings(course, id.day, changes).firstOrNull { id.period in it.periods } ?: return@any false
            val running = meeting.slots().filter { !it.isCancelled }
            val own = running.firstOrNull { it.id == slotId }
            if (own != null) own.endsAt <= now else running.all { it.endsAt <= now }
        }
    }

    /** The classes running at [now] as the school changed them; a cancelled one is not running. */
    fun ongoingClasses(courses: List<Course>, now: Instant, changes: CourseChanges): List<ClassSlot> =
        buildSlots(courses, now.atZone(AppConstants.TAIPEI_ZONE).toLocalDate(), changes)
            .filter { !it.isCancelled && it.startsAt <= now && now < it.endsAt }

    private class Meeting(
        val course: Course,
        val date: LocalDate,
        val periods: List<String>,
        val start: LocalTime,
        val end: LocalTime,
        val timetableStart: LocalTime,
        val timetableEnd: LocalTime,
        val changes: List<CourseChange>,
    ) {
        fun isCancelled(period: String): Boolean = changes.any { it.kind == Kind.CANCELLED && it.covers(period) }

        fun room(period: String): String? = changes.firstOrNull { it.kind == Kind.ROOM && it.covers(period) }?.room

        /** Its fragments, or the meeting whole when its new times leave none. */
        fun slots(): List<ClassSlot> = fragments().ifEmpty { listOfNotNull(merged(listOf(this))) }

        /**
         * Runs of periods alike in cancellation and room. Inner boundaries keep the timetable times,
         * clamped into the meeting; a run left empty is dropped, and the outer surviving ones meet
         * the meeting's own start and end. Notes never split.
         */
        fun fragments(): List<ClassSlot> {
            val runs = mutableListOf<MutableList<String>>()
            for (period in periods) {
                val last = runs.lastOrNull()?.last()
                if (last != null && isCancelled(last) == isCancelled(period) && room(last) == room(period)) {
                    runs.last() += period
                } else {
                    runs += mutableListOf(period)
                }
            }
            val pieces = runs.mapIndexedNotNull { index, run ->
                val ownStart = periodTime(run.first(), end = false) ?: return@mapIndexedNotNull null
                val ownEnd = periodTime(run.last(), end = true) ?: return@mapIndexedNotNull null
                val pieceStart = if (index == 0) start else minOf(maxOf(ownStart, start), end)
                val pieceEnd = if (index == runs.lastIndex) end else minOf(maxOf(ownEnd, start), end)
                if (pieceStart < pieceEnd) Piece(run, pieceStart, pieceEnd, ownStart, ownEnd) else null
            }
            return pieces.mapIndexed { index, piece ->
                val fragmentStart = if (index == 0) start else piece.start
                val fragmentEnd = if (index == pieces.lastIndex) end else piece.end
                ClassSlot(
                    id = slotId(course, date, piece.periods.first()),
                    course = course,
                    date = date,
                    periods = piece.periods,
                    start = fragmentStart,
                    end = fragmentEnd,
                    changes = changes.filter { change ->
                        change.kind == Kind.START || change.kind == Kind.END || piece.periods.any(change::covers)
                    },
                    originalStart = piece.ownStart.takeIf { it != fragmentStart },
                    originalEnd = piece.ownEnd.takeIf { it != fragmentEnd },
                )
            }
        }
    }

    private class Piece(
        val periods: List<String>,
        val start: LocalTime,
        val end: LocalTime,
        val ownStart: LocalTime,
        val ownEnd: LocalTime,
    )

    private fun meetings(course: Course, date: LocalDate, changes: CourseChanges): List<Meeting> {
        val dayChanges = changes.changes(course.courseNo, date)
        return contiguousPeriodRuns(course.schedule[date.dayOfWeek.value].orEmpty()).mapNotNull { run ->
            val timetableStart = periodTime(run.first(), end = false) ?: return@mapNotNull null
            val timetableEnd = periodTime(run.last(), end = true) ?: return@mapNotNull null
            var applied = dayChanges.filter { change -> run.any(change::covers) }
            var start = changedTime(Kind.START, applied) ?: timetableStart
            var end = changedTime(Kind.END, applied) ?: timetableEnd
            // A new start past the end, or a new end before the start, is a feed error; the meeting
            // keeps its times and its other changes.
            if (end <= start) {
                start = timetableStart
                end = timetableEnd
                applied = applied.filter { it.kind != Kind.START && it.kind != Kind.END }
            }
            Meeting(course, date, run, start, end, timetableStart, timetableEnd, applied)
        }
    }

    /** The meetings as one slot: their own start and end, and cancelled only when every period is. */
    private fun merged(meetings: List<Meeting>): ClassSlot? {
        val first = meetings.firstOrNull() ?: return null
        val last = meetings.last()
        val isCancelled = meetings.all { meeting -> meeting.periods.all(meeting::isCancelled) }
        val kept = mutableListOf<CourseChange>()
        for (change in meetings.flatMap { it.changes }) {
            if ((isCancelled || change.kind != Kind.CANCELLED) && kept.none { it.id == change.id }) kept += change
        }
        return ClassSlot(
            id = slotId(first.course, first.date, first.periods.first()),
            course = first.course,
            date = first.date,
            periods = meetings.flatMap { it.periods },
            start = first.start,
            end = last.end,
            changes = kept,
            originalStart = first.timetableStart.takeIf { it != first.start },
            originalEnd = last.timetableEnd.takeIf { it != last.end },
        )
    }

    private fun periodTime(period: String, end: Boolean): LocalTime? {
        val times = AppConstants.PeriodTimes.mapping[period] ?: return null
        return time(if (end) times.second else times.first)
    }

    /** The first change of [kind]'s time; one this build cannot read leaves the timetable's. */
    private fun changedTime(kind: Kind, changes: List<CourseChange>): LocalTime? {
        val change = changes.firstOrNull { it.kind == kind } ?: return null
        return time((if (kind == Kind.START) change.start else change.end) ?: return null)
    }

    private fun time(text: String): LocalTime? {
        val parts = text.split(":").mapNotNull { it.toIntOrNull() }
        if (parts.size != 2) return null
        return runCatching { LocalTime.of(parts[0], parts[1]) }.getOrNull()
    }

    private val dayKey: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuuMMdd", Locale.US).withResolverStyle(ResolverStyle.STRICT)

    private fun slotId(course: Course, date: LocalDate, firstPeriod: String): String =
        "${course.courseNo}_${date.format(dayKey)}_$firstPeriod"

    /** A slot id read from the right, as its course number may hold underscores. */
    private class SlotId(val courseNo: String, val day: LocalDate, val period: String) {
        companion object {
            fun parse(id: String): SlotId? {
                val parts = id.split("_")
                if (parts.size < 3) return null
                val day = try {
                    LocalDate.parse(parts[parts.size - 2], dayKey)
                } catch (_: DateTimeParseException) {
                    return null
                }
                return SlotId(parts.dropLast(2).joinToString("_"), day, parts.last())
            }
        }
    }
}
