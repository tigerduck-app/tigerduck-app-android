package org.ntust.app.tigerduck.notification

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.academic.Holiday
import org.ntust.app.tigerduck.academic.SemesterTerm
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.changes
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.row
import org.ntust.app.tigerduck.shared.timeline.CourseChanges
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Which classes the reminders are armed for over the ten days ahead, at what time, and under which
 * alarm ids. The ids are persisted (the slot id, and the request code it maps to), so a row here
 * that changes leaves an old alarm firing or arms a new one twice. CC900 meets on Thursday
 * 2026-11-05, periods 2 to 4 (09:10-12:10) and 7 to 8 (14:20-16:20), in T4-501.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ClassReminderTimelineTest {

    private val context: Application = RuntimeEnvironment.getApplication()

    @After
    fun releaseClock() {
        AppClock.setOverride(null)
    }

    private fun local(ms: Long): LocalDateTime =
        Instant.ofEpochMilli(ms).atZone(AppConstants.TAIPEI_ZONE).toLocalDateTime()

    private fun at(day: String, time: String): Long =
        LocalDateTime.parse("${day}T$time").atZone(AppConstants.TAIPEI_ZONE).toInstant().toEpochMilli()

    private class Armed(val nextStart: Long?, val rows: List<String>, val codeMap: String, val scheduledIds: List<String>)

    /** Arms from [day] at [time] and reads back every alarm, in the order the classes start. */
    @Suppress("DEPRECATION")
    private fun arm(
        day: String,
        time: String,
        coursesByTerm: Map<String, List<Course>>,
        calendar: AcademicCalendar = fall,
        skippedDates: Map<String, List<String>> = emptyMap(),
        optedInHolidayIds: Set<Int> = emptySet(),
        changes: CourseChanges = CourseChanges.EMPTY,
        leadMinutes: Long = 15,
    ): Armed {
        // Running, not frozen, so an alarm's real time maps back to the app's.
        AppClock.setOverride(
            ClockOverride(at(day, time), frozen = false, savedAtRealMillis = System.currentTimeMillis())
        )
        val next = ClassPreparingNotificationScheduler(context) { changes }.scheduleAll(
            coursesByTerm = coursesByTerm,
            skippedDates = skippedDates,
            leadTimeSec = leadMinutes * 60,
            calendar = calendar,
            optedInHolidayIds = optedInHolidayIds,
        )
        val rows = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
            .map { alarm ->
                val intent: Intent = shadowOf(alarm.operation).savedIntent
                val start = intent.getLongExtra(ClassPreparingNotificationReceiver.EXTRA_START_MS, 0L)
                listOf(
                    "fires ${local(alarm.triggerAtMs - AppClock.realTimeFor(0L))}",
                    intent.getStringExtra(ClassPreparingNotificationReceiver.EXTRA_COURSE_NAME),
                    "${local(start)}..${local(intent.getLongExtra(ClassPreparingNotificationReceiver.EXTRA_END_MS, 0L)).toLocalTime()}",
                    intent.getStringExtra(ClassPreparingNotificationReceiver.EXTRA_CLASSROOM),
                    intent.getStringExtra(ClassPreparingNotificationReceiver.EXTRA_CLASS_ID),
                    "term ${intent.getStringExtra(ClassPreparingNotificationReceiver.EXTRA_TERM)}",
                    "code ${intent.getIntExtra(ClassPreparingNotificationReceiver.EXTRA_NOTIFICATION_ID, -1)}",
                ).joinToString(" | ") to start
            }
            .sortedBy { it.second }
            .map { it.first }
        val tracker = context.getSharedPreferences("class_preparing_tracker", Context.MODE_PRIVATE)
        return Armed(
            nextStart = next,
            rows = rows,
            codeMap = tracker.getString("slot_request_codes", "").orEmpty(),
            scheduledIds = tracker.getStringSet("scheduled_ids", emptySet()).orEmpty().sorted(),
        )
    }

    private val cc900 = CourseChangeFixture.course()
    /**
     * One term for every case that isn't about the missing calendar: with no calendar the term is
     * the month guess, which reads the real wall clock and moves in February.
     */
    private val fall = AcademicCalendar(
        revision = 1L,
        terms = listOf(SemesterTerm("1151", LocalDate.of(2026, 9, 7), LocalDate.of(2027, 1, 15))),
        holidays = emptyList(),
    )

    /** The term the no-calendar case arms under, from the same guess the scheduler makes. */
    private val noCalendarTerm get() = org.ntust.app.tigerduck.network.SemesterCodes.heuristic()

    private val endedEarly = changes(row(1, "end", periods = listOf("2", "3", "4"), values = mapOf("end" to "11:45")))

    private fun Armed.show() = (listOf("next=${nextStart?.let(::local)}") + rows + codeMap + scheduledIds.joinToString(",")).joinToString("\n")

    private val moments = listOf("11:19", "11:20", "12:09", "12:10")

    // Nothing in the reminders reads a class's end, so the four moments and the changed end give
    // one answer: the afternoon class today, and next Thursday's two.
    private val thursdayAlarms = """next=2026-11-05T14:20
fires 2026-11-05T14:05 | Compilers | 2026-11-05T14:20..16:20 | T4-501 | CC900_20261105_7 | term 1151 | code 1
fires 2026-11-12T08:55 | Compilers | 2026-11-12T09:10..12:10 | T4-501 | CC900_20261112_2 | term 1151 | code 2
fires 2026-11-12T14:05 | Compilers | 2026-11-12T14:20..16:20 | T4-501 | CC900_20261112_7 | term 1151 | code 3
CC900@2026-11-05#7-8=1;CC900@2026-11-12#2-4=2;CC900@2026-11-12#7-8=3
CC900@2026-11-05#7-8,CC900@2026-11-12#2-4,CC900@2026-11-12#7-8"""

    @Test
    fun `the four moments on the timetable's end`() {
        val out = moments.map { arm("2026-11-05", it, mapOf("1151" to listOf(cc900))).show() }
        assertEquals(List(4) { thursdayAlarms }, out)
    }

    @Test
    fun `the four moments on the changed end at 11_45`() {
        val out = moments.map { arm("2026-11-05", it, mapOf("1151" to listOf(cc900)), changes = endedEarly).show() }
        assertEquals(List(4) { thursdayAlarms }, out)
    }

    // Term 1151 ends on Thursday 2026-10-15 and 1152 begins on Monday 2026-10-19, so from Monday
    // 2026-10-12 the ten days (the 12th to the 21st) reach into both, over a Wednesday holiday
    // and a gap between terms. Both courses meet Monday to Friday at period 3 (10:20-11:10).
    private val twoTerms = AcademicCalendar(
        revision = 1L,
        terms = listOf(
            SemesterTerm("1151", LocalDate.of(2026, 9, 7), LocalDate.of(2026, 10, 15)),
            SemesterTerm("1152", LocalDate.of(2026, 10, 19), LocalDate.of(2027, 1, 15)),
        ),
        holidays = listOf(
            Holiday(5, "測試假", "Test Day", LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 14)),
        ),
    )
    private val weekdays = (1..5).associateWith { listOf("3") }
    private val oldTermCourse = Course.fromSchedule("CC100", "Old Term", schedule = weekdays)
    private val newTermCourse = Course.fromSchedule("CC200", "New Term", schedule = weekdays)
    private val both = mapOf("1151" to listOf(oldTermCourse), "1152" to listOf(newTermCourse))

    @Test
    fun `ten days across a term boundary, a holiday, a gap between terms and a skipped date`() {
        val armed = arm("2026-10-12", "07:00", both, calendar = twoTerms, skippedDates = mapOf("CC100" to listOf("2026-10-13")))
        assertEquals("""next=2026-10-12T10:20
fires 2026-10-12T10:05 | Old Term | 2026-10-12T10:20..11:10 |  | CC100_20261012_3 | term 1151 | code 1
fires 2026-10-15T10:05 | Old Term | 2026-10-15T10:20..11:10 |  | CC100_20261015_3 | term 1151 | code 2
fires 2026-10-19T10:05 | New Term | 2026-10-19T10:20..11:10 |  | CC200_20261019_3 | term 1152 | code 3
fires 2026-10-20T10:05 | New Term | 2026-10-20T10:20..11:10 |  | CC200_20261020_3 | term 1152 | code 4
fires 2026-10-21T10:05 | New Term | 2026-10-21T10:20..11:10 |  | CC200_20261021_3 | term 1152 | code 5
CC100@2026-10-12#3-3=1;CC100@2026-10-15#3-3=2;CC200@2026-10-19#3-3=3;CC200@2026-10-20#3-3=4;CC200@2026-10-21#3-3=5
CC100@2026-10-12#3-3,CC100@2026-10-15#3-3,CC200@2026-10-19#3-3,CC200@2026-10-20#3-3,CC200@2026-10-21#3-3""", armed.show())
    }

    @Test
    fun `a holiday the user opted into is armed again`() {
        val armed = arm("2026-10-12", "07:00", both, calendar = twoTerms, optedInHolidayIds = setOf(5))
        assertEquals("""next=2026-10-12T10:20
fires 2026-10-12T10:05 | Old Term | 2026-10-12T10:20..11:10 |  | CC100_20261012_3 | term 1151 | code 1
fires 2026-10-13T10:05 | Old Term | 2026-10-13T10:20..11:10 |  | CC100_20261013_3 | term 1151 | code 2
fires 2026-10-14T10:05 | Old Term | 2026-10-14T10:20..11:10 |  | CC100_20261014_3 | term 1151 | code 3
fires 2026-10-15T10:05 | Old Term | 2026-10-15T10:20..11:10 |  | CC100_20261015_3 | term 1151 | code 4
fires 2026-10-19T10:05 | New Term | 2026-10-19T10:20..11:10 |  | CC200_20261019_3 | term 1152 | code 5
fires 2026-10-20T10:05 | New Term | 2026-10-20T10:20..11:10 |  | CC200_20261020_3 | term 1152 | code 6
fires 2026-10-21T10:05 | New Term | 2026-10-21T10:20..11:10 |  | CC200_20261021_3 | term 1152 | code 7
CC100@2026-10-12#3-3=1;CC100@2026-10-13#3-3=2;CC100@2026-10-14#3-3=3;CC100@2026-10-15#3-3=4;CC200@2026-10-19#3-3=5;CC200@2026-10-20#3-3=6;CC200@2026-10-21#3-3=7
CC100@2026-10-12#3-3,CC100@2026-10-13#3-3,CC100@2026-10-14#3-3,CC100@2026-10-15#3-3,CC200@2026-10-19#3-3,CC200@2026-10-20#3-3,CC200@2026-10-21#3-3""", armed.show())
    }

    @Test
    fun `a term with no courses cached arms nothing for its days`() {
        val armed = arm("2026-10-12", "07:00", mapOf("1151" to listOf(oldTermCourse), "1152" to emptyList()), calendar = twoTerms)
        assertEquals("""next=2026-10-12T10:20
fires 2026-10-12T10:05 | Old Term | 2026-10-12T10:20..11:10 |  | CC100_20261012_3 | term 1151 | code 1
fires 2026-10-13T10:05 | Old Term | 2026-10-13T10:20..11:10 |  | CC100_20261013_3 | term 1151 | code 2
fires 2026-10-15T10:05 | Old Term | 2026-10-15T10:20..11:10 |  | CC100_20261015_3 | term 1151 | code 3
CC100@2026-10-12#3-3=1;CC100@2026-10-13#3-3=2;CC100@2026-10-15#3-3=3
CC100@2026-10-12#3-3,CC100@2026-10-13#3-3,CC100@2026-10-15#3-3""", armed.show())
    }

    @Test
    fun `no calendar arms every class day of the ten`() {
        val armed = arm("2026-10-12", "07:00", mapOf(noCalendarTerm to listOf(oldTermCourse)), calendar = AcademicCalendar.EMPTY)
        assertEquals("""next=2026-10-12T10:20
fires 2026-10-12T10:05 | Old Term | 2026-10-12T10:20..11:10 |  | CC100_20261012_3 | term TERM | code 1
fires 2026-10-13T10:05 | Old Term | 2026-10-13T10:20..11:10 |  | CC100_20261013_3 | term TERM | code 2
fires 2026-10-14T10:05 | Old Term | 2026-10-14T10:20..11:10 |  | CC100_20261014_3 | term TERM | code 3
fires 2026-10-15T10:05 | Old Term | 2026-10-15T10:20..11:10 |  | CC100_20261015_3 | term TERM | code 4
fires 2026-10-16T10:05 | Old Term | 2026-10-16T10:20..11:10 |  | CC100_20261016_3 | term TERM | code 5
fires 2026-10-19T10:05 | Old Term | 2026-10-19T10:20..11:10 |  | CC100_20261019_3 | term TERM | code 6
fires 2026-10-20T10:05 | Old Term | 2026-10-20T10:20..11:10 |  | CC100_20261020_3 | term TERM | code 7
fires 2026-10-21T10:05 | Old Term | 2026-10-21T10:20..11:10 |  | CC100_20261021_3 | term TERM | code 8
CC100@2026-10-12#3-3=1;CC100@2026-10-13#3-3=2;CC100@2026-10-14#3-3=3;CC100@2026-10-15#3-3=4;CC100@2026-10-16#3-3=5;CC100@2026-10-19#3-3=6;CC100@2026-10-20#3-3=7;CC100@2026-10-21#3-3=8
CC100@2026-10-12#3-3,CC100@2026-10-13#3-3,CC100@2026-10-14#3-3,CC100@2026-10-15#3-3,CC100@2026-10-16#3-3,CC100@2026-10-19#3-3,CC100@2026-10-20#3-3,CC100@2026-10-21#3-3""".replace("TERM", noCalendarTerm), armed.show())
    }

    @Test
    fun `a cancelled class, a moved one and a split meeting`() {
        val feed = changes(
            row(1, "cancelled", periods = listOf("7", "8")),
            row(2, "start", periods = listOf("2", "3", "4"), values = mapOf("start" to "10:00")),
            row(3, "room", periods = listOf("4"), values = mapOf("room" to "TR-313")),
        )
        val armed = arm("2026-11-04", "07:00", mapOf("1151" to listOf(cc900)), changes = feed)
        assertEquals("""next=2026-11-05T10:00
fires 2026-11-05T09:45 | Compilers | 2026-11-05T10:00..11:10 | T4-501 | CC900_20261105_2 | term 1151 | code 1
fires 2026-11-05T11:05 | Compilers | 2026-11-05T11:20..12:10 | TR-313 | CC900_20261105_4 | term 1151 | code 2
fires 2026-11-12T08:55 | Compilers | 2026-11-12T09:10..12:10 | T4-501 | CC900_20261112_2 | term 1151 | code 3
fires 2026-11-12T14:05 | Compilers | 2026-11-12T14:20..16:20 | T4-501 | CC900_20261112_7 | term 1151 | code 4
CC900@2026-11-05#2-3=1;CC900@2026-11-05#4-4=2;CC900@2026-11-12#2-4=3;CC900@2026-11-12#7-8=4
CC900@2026-11-05#2-3,CC900@2026-11-05#4-4,CC900@2026-11-12#2-4,CC900@2026-11-12#7-8""", armed.show())
    }

    @Test
    fun `contiguous periods are one id`() {
        val armed = arm("2026-11-04", "07:00", mapOf("1151" to listOf(CourseChangeFixture.course(listOf("3", "4")))))
        assertEquals("""next=2026-11-05T10:20
fires 2026-11-05T10:05 | Compilers | 2026-11-05T10:20..12:10 | T4-501 | CC900_20261105_3 | term 1151 | code 1
fires 2026-11-12T10:05 | Compilers | 2026-11-12T10:20..12:10 | T4-501 | CC900_20261112_3 | term 1151 | code 2
CC900@2026-11-05#3-4=1;CC900@2026-11-12#3-4=2
CC900@2026-11-05#3-4,CC900@2026-11-12#3-4""", armed.show())
    }

    @Test
    fun `a gap between periods makes two ids`() {
        val armed = arm("2026-11-04", "07:00", mapOf("1151" to listOf(CourseChangeFixture.course(listOf("3", "5")))))
        assertEquals("""next=2026-11-05T10:20
fires 2026-11-05T10:05 | Compilers | 2026-11-05T10:20..11:10 | T4-501 | CC900_20261105_3 | term 1151 | code 1
fires 2026-11-05T12:05 | Compilers | 2026-11-05T12:20..13:10 | T4-501 | CC900_20261105_5 | term 1151 | code 2
fires 2026-11-12T10:05 | Compilers | 2026-11-12T10:20..11:10 | T4-501 | CC900_20261112_3 | term 1151 | code 3
fires 2026-11-12T12:05 | Compilers | 2026-11-12T12:20..13:10 | T4-501 | CC900_20261112_5 | term 1151 | code 4
CC900@2026-11-05#3-3=1;CC900@2026-11-05#5-5=2;CC900@2026-11-12#3-3=3;CC900@2026-11-12#5-5=4
CC900@2026-11-05#3-3,CC900@2026-11-05#5-5,CC900@2026-11-12#3-3,CC900@2026-11-12#5-5""", armed.show())
    }

    @Test
    fun `a course with only a period of no known time arms nothing`() {
        val armed = arm("2026-11-04", "07:00", mapOf("1151" to listOf(CourseChangeFixture.course(listOf("E")))))
        assertEquals("""next=null

""", armed.show())
    }

    @Test
    fun `a period with no known time is left out and its course's other period is armed`() {
        val armed = arm("2026-11-04", "07:00", mapOf("1151" to listOf(CourseChangeFixture.course(listOf("4", "E")))))
        assertEquals("""next=2026-11-05T11:20
fires 2026-11-05T11:05 | Compilers | 2026-11-05T11:20..12:10 | T4-501 | CC900_20261105_4 | term 1151 | code 1
fires 2026-11-12T11:05 | Compilers | 2026-11-12T11:20..12:10 | T4-501 | CC900_20261112_4 | term 1151 | code 2
CC900@2026-11-05#4-4=1;CC900@2026-11-12#4-4=2
CC900@2026-11-05#4-4,CC900@2026-11-12#4-4""", armed.show())
    }
}
