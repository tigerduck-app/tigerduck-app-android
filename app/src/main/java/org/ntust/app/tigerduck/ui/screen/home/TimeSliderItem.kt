package org.ntust.app.tigerduck.ui.screen.home

import androidx.compose.ui.graphics.Color
import org.ntust.app.tigerduck.clubs.ClubOccurrence
import org.ntust.app.tigerduck.shared.time.TaipeiClock
import org.ntust.app.tigerduck.ui.screen.classtable.ClassTableClubLayout
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import java.time.Duration
import java.time.LocalDate
import java.util.Date

/**
 * One block on Home's time machine: a class, or what is left of a subscribed club's activity once
 * the day's classes and every higher club are taken out. As iOS `TimeSliderItem`.
 */
sealed interface TimeSliderItem {
    val id: String
    val start: Date
    val end: Date

    /** The start of its Taipei day. */
    val date: Date

    val isCancelled: Boolean

    /** The color its card and its block on the track are tinted with. */
    val tint: Color

    /** Whether [at] is within it: from its start, over from its end. */
    fun isOn(at: Date): Boolean

    data class Course(val slot: CourseTimeSlot) : TimeSliderItem {
        override val id get() = slot.id
        override val start get() = slot.start
        override val end get() = slot.end
        override val date get() = slot.date
        override val isCancelled get() = slot.isCancelled
        override val tint get() = TigerDuckTheme.courseColorVibrant(slot.course.courseNo)
        override fun isOn(at: Date) = slot.isGoingOn(at)
    }

    /** [piece] is on [day], the start of the activity's Taipei day. */
    data class Club(val piece: ClassTableClubLayout.Segment, val day: Date) : TimeSliderItem {
        val occurrence: ClubOccurrence get() = piece.occurrence
        override val id get() = "club-${occurrence.id}@${piece.start}"
        override val start get() = at(piece.start)
        override val end get() = at(piece.end)
        override val date get() = day
        override val isCancelled get() = false
        override val tint get() = Color(0xFF000000.toInt() or occurrence.color)
        override fun isOn(at: Date) = start <= at && at < end

        private fun at(minute: Int) = Date(day.time + minute * 60_000L)
    }

    companion object {
        /**
         * [slots], the classes the time machine shows, with the clubs' [activities] between them,
         * by start. A club is cut by the classes of its own day, cancelled ones too: a skipped or a
         * quiet day's are not in [slots], so they do not block. Classes come before clubs that
         * start with them.
         */
        fun timeline(slots: List<CourseTimeSlot>, activities: List<ClubOccurrence>): List<TimeSliderItem> {
            val courses = slots.map(::Course)
            if (activities.isEmpty()) return courses
            val classes = slots.groupBy { it.localDate.toString() }
            val clubs = activities.groupBy { it.day }.flatMap { (key, day) ->
                val midnight = LocalDate.parse(key).atStartOfDay(TaipeiClock.zone).toInstant()
                fun minute(time: Date) = Duration.between(midnight, time.toInstant()).toMinutes().toInt()
                val blockers = classes[key].orEmpty().map { ClassTableClubLayout.Interval(minute(it.start), minute(it.end)) }
                ClassTableClubLayout.segments(day, blockers).map { Club(it, Date.from(midnight)) }
            }
            return (courses + clubs).sortedBy { it.start }
        }
    }
}
