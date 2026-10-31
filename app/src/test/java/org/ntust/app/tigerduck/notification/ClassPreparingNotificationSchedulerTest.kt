package org.ntust.app.tigerduck.notification

import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.network.SemesterCodes
import org.ntust.app.tigerduck.academic.SemesterTerm
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.changes
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.row
import org.ntust.app.tigerduck.coursechanges.CourseChanges
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime

/** What each "class starting soon" reminder is armed to say, and when. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ClassPreparingNotificationSchedulerTest {

    private lateinit var context: Application
    private lateinit var scheduler: ClassPreparingNotificationScheduler

    // A course meeting twice on Mondays, in a different room each time.
    private val twice = Course.fromSchedule(
        courseNo = "CS2001301",
        courseName = "Data Structures",
        classroom = "TR-313, TR-412",
        schedule = mapOf(1 to listOf("3", "4", "7", "8")),
        classroomMap = mapOf(
            "1-3" to "TR-313", "1-4" to "TR-313",
            "1-7" to "TR-412", "1-8" to "TR-412",
        ),
    )

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        scheduler = ClassPreparingNotificationScheduler(context)
        // 2026-10-12 is a Monday.
        val mondayMorning = ZonedDateTime.of(2026, 10, 12, 7, 0, 0, 0, AppConstants.TAIPEI_ZONE)
        AppClock.setOverride(
            ClockOverride(mondayMorning.toInstant().toEpochMilli(), frozen = true, savedAtRealMillis = 0L)
        )
    }

    @After
    fun releaseClock() {
        AppClock.setOverride(null)
    }

    /** The intent of every armed reminder, in the order the classes start. */
    // Robolectric 4.17 deprecates the operation field but has no getter for it.
    @Suppress("DEPRECATION")
    private fun armed(): List<Intent> =
        shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
            .map { shadowOf(it.operation).savedIntent }
            .sortedBy { it.getLongExtra(ClassPreparingNotificationReceiver.EXTRA_START_MS, 0L) }

    @Test
    fun `each block of a course meeting twice is announced with its own room`() {
        // Every argument named, the calendar ones too: they lose their defaults in #342.
        scheduler.scheduleAll(
            coursesByTerm = mapOf(noCalendarTerm to listOf(twice)),
            skippedDates = emptyMap(),
            leadTimeSec = 600,
            calendar = AcademicCalendar.EMPTY,
            optedInHolidayIds = emptySet(),
        )

        assertEquals(
            listOf("TR-313", "TR-412"),
            armed().take(2).map { it.getStringExtra(ClassPreparingNotificationReceiver.EXTRA_CLASSROOM) },
        )
    }

    /** The term every day falls in with no calendar. */
    private val noCalendarTerm = SemesterCodes.termOn(AcademicCalendar.EMPTY, LocalDate.now())

    // One term ends on Friday 11 September 2026 and the next begins on the
    // Monday after, so the ten days from Wednesday the 9th reach into both.
    private val twoTerms = AcademicCalendar(
        revision = 1L,
        terms = listOf(
            SemesterTerm("1142", LocalDate.of(2026, 2, 23), LocalDate.of(2026, 9, 11)),
            SemesterTerm("1151", LocalDate.of(2026, 9, 14), LocalDate.of(2027, 1, 15)),
        ),
        holidays = emptyList(),
    )

    // The old term's course meets on Mondays and Thursdays, the new term's on Mondays.
    private val oldTerm = Course.fromSchedule(
        courseNo = "CS1001301",
        courseName = "Old Term Course",
        schedule = mapOf(1 to listOf("3", "4"), 4 to listOf("3", "4")),
    )
    private val newTerm = Course.fromSchedule(
        courseNo = "CS2002301",
        courseName = "New Term Course",
        schedule = mapOf(1 to listOf("3", "4")),
    )

    /** Arms from Wednesday 9 September 2026, 07:00, with [coursesByTerm] cached. */
    private fun armAcrossTermStart(coursesByTerm: Map<String, List<Course>>) {
        val wednesday = ZonedDateTime.of(2026, 9, 9, 7, 0, 0, 0, AppConstants.TAIPEI_ZONE)
        AppClock.setOverride(
            ClockOverride(wednesday.toInstant().toEpochMilli(), frozen = true, savedAtRealMillis = 0L)
        )
        scheduler.scheduleAll(
            coursesByTerm = coursesByTerm,
            skippedDates = emptyMap(),
            leadTimeSec = 600,
            calendar = twoTerms,
            optedInHolidayIds = emptySet(),
        )
    }

    /** Each armed reminder as its course's name and its class's day. */
    private fun armedByDay(): List<String> = armed().map {
        val start = it.getLongExtra(ClassPreparingNotificationReceiver.EXTRA_START_MS, 0L)
        val day = Instant.ofEpochMilli(start).atZone(AppConstants.TAIPEI_ZONE).toLocalDate()
        "${it.getStringExtra(ClassPreparingNotificationReceiver.EXTRA_COURSE_NAME)} $day"
    }

    @Test
    fun `across a term start, each day is armed from its own term's courses`() {
        armAcrossTermStart(mapOf("1142" to listOf(oldTerm), "1151" to listOf(newTerm)))

        assertEquals(
            listOf("Old Term Course 2026-09-10", "New Term Course 2026-09-14"),
            armedByDay(),
        )
    }

    @Test
    fun `with only the old term cached, the new term's days get nothing`() {
        // What LiveActivityManager hands over when the new term has never
        // been fetched: its term, with no courses.
        armAcrossTermStart(mapOf("1142" to listOf(oldTerm), "1151" to emptyList()))

        assertEquals(listOf("Old Term Course 2026-09-10"), armedByDay())
    }

    @Test
    fun `each reminder carries the term it was armed from`() {
        armAcrossTermStart(mapOf("1142" to listOf(oldTerm), "1151" to listOf(newTerm)))

        assertEquals(
            listOf("1142", "1151"),
            armed().map { it.getStringExtra(ClassPreparingNotificationReceiver.EXTRA_TERM) },
        )
    }

    @Test
    fun `the days ahead need the courses of each term they fall in`() {
        val wednesday = LocalDate.of(2026, 9, 9)

        assertEquals(
            setOf("1142", "1151"),
            ClassPreparingNotificationScheduler.termsAhead(twoTerms, wednesday),
        )
    }

    // The school's course changes, over the iPhone suite's course: CC900 meets on Thursday
    // 2026-11-05, periods 2 to 4 (09:10–12:10) and 7 to 8 (14:20–16:20), in T4-501.

    private val morning = listOf("2", "3", "4")

    /** Arms from Thursday 2026-11-05, 07:00, under [changes], [leadMinutes] ahead of each class. */
    private fun armThursday(changes: CourseChanges, leadMinutes: Long = 15) {
        // Running, not frozen, so an alarm's real time maps back to the app's.
        AppClock.setOverride(
            ClockOverride(
                CourseChangeFixture.at("07:00").toEpochMilli(),
                frozen = false,
                savedAtRealMillis = System.currentTimeMillis(),
            )
        )
        ClassPreparingNotificationScheduler(context) { changes }.scheduleAll(
            coursesByTerm = mapOf(noCalendarTerm to listOf(CourseChangeFixture.course())),
            skippedDates = emptyMap(),
            leadTimeSec = leadMinutes * 60,
            calendar = AcademicCalendar.EMPTY,
            optedInHolidayIds = emptySet(),
        )
    }

    /** The reminders armed for Thursday's classes, as when each fires and the class it announces. */
    // Robolectric 4.17 deprecates the operation field but has no getter for it.
    @Suppress("DEPRECATION")
    private fun armedOnThursday(): List<Pair<Long, Intent>> =
        shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
            .map { (it.triggerAtMs - AppClock.realTimeFor(0L)) to shadowOf(it.operation).savedIntent }
            .filter { (_, intent) ->
                val start = intent.getLongExtra(ClassPreparingNotificationReceiver.EXTRA_START_MS, 0L)
                Instant.ofEpochMilli(start).atZone(AppConstants.TAIPEI_ZONE).toLocalDate() == CourseChangeFixture.thursday
            }
            .sortedBy { it.first }

    private fun Intent.startMs() = getLongExtra(ClassPreparingNotificationReceiver.EXTRA_START_MS, 0L)

    private fun Intent.room() = getStringExtra(ClassPreparingNotificationReceiver.EXTRA_CLASSROOM)

    @Test
    fun `the reminders skip a cancelled class and fire ahead of the changed start`() {
        armThursday(
            changes(
                row(1, "cancelled", periods = listOf("7", "8")),
                row(2, "start", periods = morning, values = mapOf("start" to "10:00")),
                row(3, "room", periods = morning, values = mapOf("room" to "TR-313")),
            ),
        )

        val armed = armedOnThursday()
        assertEquals(listOf(CourseChangeFixture.at("09:45").toEpochMilli()), armed.map { it.first })
        assertEquals(listOf(CourseChangeFixture.at("10:00").toEpochMilli()), armed.map { it.second.startMs() })
        assertEquals(listOf("TR-313"), armed.map { it.second.room() })
    }

    @Test
    fun `with no course changes, each meeting is announced as the timetable has it`() {
        armThursday(CourseChanges.EMPTY)

        val armed = armedOnThursday()
        assertEquals(
            listOf("08:55", "14:05").map { CourseChangeFixture.at(it).toEpochMilli() },
            armed.map { it.first },
        )
        assertEquals(listOf("T4-501", "T4-501"), armed.map { it.second.room() })
    }

    @Test
    fun `a reminder carries the changed room`() {
        armThursday(changes(row(1, "room", periods = listOf("7", "8"), values = mapOf("room" to "TR-313"))))

        assertEquals(listOf("T4-501", "TR-313"), armedOnThursday().map { it.second.room() })
    }

    @Test
    fun `a reminder for a class cancelled after it was armed is not posted`() {
        armThursday(CourseChanges.EMPTY)
        val later = changes(row(1, "cancelled", periods = listOf("7", "8")))

        assertEquals(
            listOf(false, true),
            armedOnThursday().map { (_, intent) ->
                ClassPreparingNotificationReceiver.cancelledSince(
                    later,
                    intent.getStringExtra(ClassPreparingNotificationReceiver.EXTRA_CLASS_ID),
                )
            },
        )
        assertEquals(false, ClassPreparingNotificationReceiver.cancelledSince(later, classId = null))
    }

    // A reminder already up when the school changes its class: the one for the 09:10 class goes
    // off at 08:55, and at 09:00 a new feed comes.

    private val notifications get() = context.getSystemService(NotificationManager::class.java)

    private fun clockAt(time: String) = AppClock.setOverride(
        ClockOverride(CourseChangeFixture.at(time).toEpochMilli(), frozen = true, savedAtRealMillis = 0L)
    )

    /** Posts the reminder [alarm] carries, as the receiver does when it goes off. */
    private fun goOff(alarm: Intent) = ClassPreparingNotificationReceiver.post(
        context,
        appLanguage = "system",
        notificationId = alarm.getIntExtra(ClassPreparingNotificationReceiver.EXTRA_NOTIFICATION_ID, -1),
        courseName = alarm.getStringExtra(ClassPreparingNotificationReceiver.EXTRA_COURSE_NAME)!!,
        classroom = alarm.room().orEmpty(),
        instructor = alarm.getStringExtra(ClassPreparingNotificationReceiver.EXTRA_INSTRUCTOR).orEmpty(),
        startMs = alarm.startMs(),
        endMs = alarm.getLongExtra(ClassPreparingNotificationReceiver.EXTRA_END_MS, 0L),
        leadTimeMs = alarm.getLongExtra(ClassPreparingNotificationReceiver.EXTRA_LEAD_TIME_MS, 0L),
        classId = alarm.getStringExtra(ClassPreparingNotificationReceiver.EXTRA_CLASS_ID),
    )

    /** Arms Thursday at 07:00 as the timetable has it, and has the 09:10 class's reminder go off at 08:55. */
    private fun remindedOfTheMorningClass() {
        NotificationChannelRegistrar(context, AppPreferences(context)).register()
        armThursday(CourseChanges.EMPTY)
        val alarm = armedOnThursday().first().second
        clockAt("08:55")
        goOff(alarm)
    }

    /** Arms the reminders again at 09:00, under the school's [changes]. */
    private fun newFeedAtNine(changes: CourseChanges) {
        clockAt("09:00")
        ClassPreparingNotificationScheduler(context) { changes }.scheduleAll(
            coursesByTerm = mapOf(noCalendarTerm to listOf(CourseChangeFixture.course())),
            skippedDates = emptyMap(),
            leadTimeSec = 15 * 60,
            calendar = AcademicCalendar.EMPTY,
            optedInHolidayIds = emptySet(),
        )
    }

    /** The class reminders up, their stack's summary aside. */
    private fun shown(): List<Notification> = notifications.activeNotifications
        .map { it.notification }
        .filter {
            it.channelId == ClassPreparingNotificationReceiver.CHANNEL_ID &&
                it.flags and Notification.FLAG_GROUP_SUMMARY == 0
        }

    private val Notification.text: String? get() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    @Test
    fun `a reminder already up goes when the school cancels its class`() {
        remindedOfTheMorningClass()
        assertEquals(1, shown().size)

        newFeedAtNine(changes(row(1, "cancelled", periods = morning)))

        assertEquals(emptyList<Notification>(), shown())
    }

    @Test
    fun `a reminder already up goes when its course was the last one and is removed`() {
        remindedOfTheMorningClass()
        assertEquals(1, shown().size)

        clockAt("09:00")
        ClassPreparingNotificationScheduler(context) { CourseChanges.EMPTY }.scheduleAll(
            coursesByTerm = mapOf(noCalendarTerm to emptyList()),
            skippedDates = emptyMap(),
            leadTimeSec = 15 * 60,
            calendar = AcademicCalendar.EMPTY,
            optedInHolidayIds = emptySet(),
        )

        assertEquals(emptyList<Notification>(), shown())
    }

    @Test
    fun `a reminder already up shows its class's new room, without sounding again`() {
        remindedOfTheMorningClass()
        assertEquals(listOf("09:10–12:10 · T4-501"), shown().map { it.text })

        newFeedAtNine(changes(row(1, "room", periods = morning, values = mapOf("room" to "TR-313"))))

        val reminder = shown().single()
        assertEquals("09:10–12:10 · TR-313", reminder.text)
        assertTrue("it sounds again", reminder.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }

    @Test
    fun `a reminder already up moves to its class's new start, and goes when the class begins`() {
        remindedOfTheMorningClass()

        newFeedAtNine(changes(row(1, "start", periods = morning, values = mapOf("start" to "09:05"))))

        val reminder = shown().single()
        assertEquals("09:05–12:10 · T4-501", reminder.text)
        assertEquals(
            CourseChangeFixture.at("09:05").toEpochMilli(),
            reminder.extras.getLong(ClassPreparingNotificationReceiver.EXTRA_CLASS_START_MS),
        )
        assertEquals(5 * 60_000L, reminder.timeoutAfter)
    }

    @Test
    fun `a reminder already up goes when its class moves past the reminder's lead, to come again then`() {
        remindedOfTheMorningClass()

        newFeedAtNine(changes(row(1, "start", periods = morning, values = mapOf("start" to "10:00"))))

        assertEquals(emptyList<Notification>(), shown())
        assertEquals(
            CourseChangeFixture.at("10:00").toEpochMilli(),
            armedOnThursday().first().second.startMs(),
        )
    }

    @Test
    fun `a reminder already up is left as it is when the school changes another class`() {
        remindedOfTheMorningClass()
        val before = shown().single()

        newFeedAtNine(changes(row(1, "cancelled", periods = listOf("7", "8"))))

        assertSame(before, shown().single())
    }
}
