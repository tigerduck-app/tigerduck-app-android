@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck.liveactivity

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.changes
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.row
import org.ntust.app.tigerduck.shared.timeline.CourseChanges
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.notification.ClassPreparingNotificationReceiver
import org.ntust.app.tigerduck.notification.ClassPreparingNotificationScheduler
import org.ntust.app.tigerduck.notification.ClassReminderAlarms
import org.ntust.app.tigerduck.notification.NotificationChannelRegistrar
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZonedDateTime

/**
 * What a refresh of the real manager arms for class reminders: their own
 * switch and lead time decide, never the Live Update's. And that a new
 * course changes feed brings a refresh of its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LiveActivityManagerTest {

    /** Records what the manager asks of the class reminder alarms. */
    private class FakeClassReminderAlarms : ClassReminderAlarms {
        var armedLeadSec: Long? = null
        var armedCoursesByTerm: Map<String, List<Course>> = emptyMap()
        val armedCourses: List<Course> get() = armedCoursesByTerm.values.flatten()
        var cancels = 0

        /** What [scheduleAll] reports as the next class's start. */
        var nextClassStart: Long? = null

        override fun scheduleAll(
            coursesByTerm: Map<String, List<Course>>,
            skippedDates: Map<String, List<String>>,
            leadTimeSec: Long,
            calendar: AcademicCalendar,
            optedInHolidayIds: Set<Int>,
            startedFor: String?,
        ): Long? {
            armedLeadSec = leadTimeSec
            armedCoursesByTerm = coursesByTerm
            return nextClassStart
        }

        override fun cancelAllTracked() {
            armedLeadSec = null
            cancels++
        }
    }

    private val context: Application = RuntimeEnvironment.getApplication()
    private val appPrefs = AppPreferences(context)
    private val liveUpdate = LiveActivityPreferences(context)
    private val calendar = AcademicCalendarStore(context, OkHttpClient(), appPrefs)
    private val dataCache = DataCache(context, calendar)
    private val alarms = FakeClassReminderAlarms()
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
    private var signedIn = true
    private var storedId: String? = "B00015999"
    private var demoSession = false
    private var storedIdRead: () -> String? = { storedId }

    private val course = Course(courseNo = "CS3005301", courseName = "Data Structures", scheduleJson = """{"1":["2","3"]}""")

    private fun managerWith(calendar: AcademicCalendarStore) = LiveActivityManager(
        preferences = liveUpdate,
        notifier = LiveActivityNotifier(context, liveUpdate, appPrefs, NotificationChannelRegistrar(context, appPrefs)),
        dataCache = dataCache,
        isSignedIn = { signedIn },
        appPrefs = appPrefs,
        classPreparingScheduler = alarms,
        academicCalendar = calendar,
        boundaryScheduler = LiveActivityBoundaryScheduler(context),
        appScope = scope,
        storedStudentId = { storedIdRead() },
        isDemoSession = { demoSession },
    )

    private val manager = managerWith(calendar)

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        runBlocking { dataCache.saveCourses(listOf(course)) }
    }

    @After
    fun tearDown() {
        scope.cancel()
        liveScope.cancel()
        AppClock.setOverride(null)
    }

    private fun refresh() = runBlocking { manager.refreshAndWait() }

    @Test
    fun `with the Live Update off, class reminders are armed at their own lead time`() {
        liveUpdate.isEnabled = false
        liveUpdate.showClassPreparing = false
        appPrefs.classReminderEnabled = true
        appPrefs.classReminderLeadMinutes = 10

        refresh()

        assertEquals(10 * 60L, alarms.armedLeadSec)
        assertEquals(listOf("CS3005301"), alarms.armedCourses.map { it.courseNo })
    }

    @Test
    fun `with the Live Update on, its class-preparing lead time does not move the reminders`() {
        liveUpdate.isEnabled = true
        liveUpdate.showClassPreparing = true
        liveUpdate.classPreparingLeadTimeSec = 60 * 60
        appPrefs.classReminderEnabled = true
        appPrefs.classReminderLeadMinutes = 5

        refresh()

        assertEquals(5 * 60L, alarms.armedLeadSec)
    }

    @Test
    fun `with class reminders off, none are armed even with the Live Update on`() {
        liveUpdate.isEnabled = true
        liveUpdate.showClassPreparing = true
        appPrefs.classReminderEnabled = false

        refresh()

        assertNull(alarms.armedLeadSec)
        assertTrue(alarms.cancels > 0)
    }

    @Test
    fun `signed in with the stored student ID already cleared arms nothing`() {
        // logout clears the ID a moment before it lowers authState.
        storedId = null
        appPrefs.classReminderEnabled = true

        refresh()

        assertNull(alarms.armedLeadSec)
        assertTrue(alarms.cancels > 0)
    }

    @Test
    fun `the demo account, which has no stored student ID, still arms`() {
        storedId = null
        demoSession = true
        appPrefs.classReminderEnabled = true

        refresh()

        assertNotNull(alarms.armedLeadSec)
    }

    @Test
    fun `signed out, none are armed`() {
        signedIn = false
        appPrefs.classReminderEnabled = true

        refresh()

        assertNull(alarms.armedLeadSec)
        assertTrue(alarms.cancels > 0)
    }

    /**
     * A manager over a calendar in which one term ends on Friday 11
     * September 2026 and the next begins on Monday the 14th, at [hour]:[minute]
     * in Taipei on [day] [month] 2026.
     */
    private fun overTwoTerms(day: Int, hour: Int, minute: Int = 0, month: Int = 9): LiveActivityManager {
        context.getSharedPreferences("academic_calendar", Context.MODE_PRIVATE).edit()
            .putString(
                "payload",
                """{"revision":1,"holidays":[],"semesters":[
                  {"code":"1142","start":"2026-02-23","end":"2026-09-11"},
                  {"code":"1151","start":"2026-09-14","end":"2027-01-15"}]}""",
            )
            .commit()
        // Running, not frozen: a frozen clock maps its alarms to real time
        // from whenever each is set, which no assertion could match.
        val at = ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, AppConstants.TAIPEI_ZONE)
        AppClock.setOverride(
            ClockOverride(at.toInstant().toEpochMilli(), frozen = false, savedAtRealMillis = System.currentTimeMillis())
        )
        return managerWith(AcademicCalendarStore(context, OkHttpClient(), appPrefs))
    }

    /** Midnight, Taipei, as Monday 14 September 2026 begins: the new term's start. */
    private val newTermStarts =
        ZonedDateTime.of(2026, 9, 14, 0, 0, 0, 0, AppConstants.TAIPEI_ZONE).toInstant().toEpochMilli()

    /** When the Live Update's wake-up alarm, the one that brings a refresh, is set for, in AppClock time. */
    private fun refreshWakes(): List<Long> =
        shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
            .filter {
                // Robolectric 4.17 deprecates the alarm's operation field without a getter to use instead.
                @Suppress("DEPRECATION")
                val operation = it.operation
                it.getType() == AlarmManager.RTC_WAKEUP &&
                    shadowOf(operation).savedIntent.action == LiveActivityBoundaryScheduler.ACTION_BOUNDARY
            }
            .map { it.triggerAtMs - AppClock.realTimeFor(0L) }

    @Test
    fun `on the last weekend of a break, the new term's days are armed from its own courses`() {
        appPrefs.classReminderEnabled = true
        val oldCourse = course.copy(courseNo = "CS1001301")
        val newCourse = course.copy(courseNo = "CS2002301")
        runBlocking {
            dataCache.saveCourses(listOf(oldCourse), "1142")
            dataCache.saveCourses(listOf(newCourse), "1151")
        }

        runBlocking { overTwoTerms(day = 12, hour = 7).refreshAndWait() }

        assertEquals(listOf("CS2002301"), alarms.armedCoursesByTerm["1151"]?.map { it.courseNo })
    }

    @Test
    fun `with the Live Update off, class reminders are armed again when the term begins`() {
        liveUpdate.isEnabled = false
        appPrefs.classReminderEnabled = true

        runBlocking { overTwoTerms(day = 12, hour = 7).refreshAndWait() }

        assertEquals(listOf(newTermStarts + 1_000L), refreshWakes())
    }

    @Test
    fun `with the Live Update on, it is refreshed when the term begins`() {
        liveUpdate.isEnabled = true
        appPrefs.classReminderEnabled = true

        runBlocking { overTwoTerms(day = 13, hour = 23, minute = 50).refreshAndWait() }

        assertEquals(listOf(newTermStarts + 1_000L), refreshWakes())
    }

    @Test
    fun `a term change months away leaves the half-hourly refresh in place`() {
        liveUpdate.isEnabled = true
        appPrefs.classReminderEnabled = true

        // A Monday evening in term, its classes over and no deadline ahead:
        // the next term change is the term's end, in January.
        runBlocking { overTwoTerms(day = 12, hour = 20, month = 10).refreshAndWait() }

        val at = ZonedDateTime.of(2026, 10, 12, 20, 0, 0, 0, AppConstants.TAIPEI_ZONE).toInstant().toEpochMilli()
        val wake = refreshWakes().single()
        assertTrue("woke at ${wake - at} ms", wake in at + 30 * 60_000L..at + 31 * 60_000L)
    }

    @Test
    fun `a refresh whose account cannot be read still finishes and leaves the armed reminders alone`() {
        liveUpdate.isEnabled = true
        appPrefs.classReminderEnabled = true
        storedIdRead = { error("keystore unavailable") }

        runBlocking { overTwoTerms(day = 12, hour = 20, month = 10).refreshAndWait() }

        // The Live Update's own wake-up is still set, as in the refresh above.
        val at = ZonedDateTime.of(2026, 10, 12, 20, 0, 0, 0, AppConstants.TAIPEI_ZONE).toInstant().toEpochMilli()
        val wake = refreshWakes().single()
        assertTrue("woke at ${wake - at} ms", wake in at + 30 * 60_000L..at + 31 * 60_000L)
        // Neither armed nor cancelled: what is armed stays.
        assertNull(alarms.armedLeadSec)
        assertEquals(0, alarms.cancels)
    }

    // The school's course changes, over the iPhone suite's course: CC900 meets on Thursday
    // 2026-11-05, periods 2 to 4 (09:10–12:10) and 7 to 8 (14:20–16:20), in T4-501.

    private val feed = MutableStateFlow(CourseChanges.EMPTY)

    // Runs the refreshes a new feed brings on its own, as the app's scope does, but on this
    // thread, when awaitUntil lets it.
    private val liveDispatcher = StandardTestDispatcher()
    private val liveScope = CoroutineScope(SupervisorJob() + liveDispatcher)

    /**
     * The real manager and class reminder alarms over [feed], at 07:00 on Thursday 2026-11-05,
     * with the Live Update and the class reminders on, each 15 minutes ahead of a class.
     */
    private fun overThursdayFeed(): LiveActivityManager {
        AppClock.setOverride(
            ClockOverride(
                CourseChangeFixture.at("07:00").toEpochMilli(),
                frozen = false,
                savedAtRealMillis = System.currentTimeMillis(),
            )
        )
        liveUpdate.isEnabled = true
        liveUpdate.classPreparingLeadTimeSec = 15 * 60
        appPrefs.classReminderEnabled = true
        appPrefs.classReminderLeadMinutes = 15
        runBlocking { dataCache.saveCourses(listOf(CourseChangeFixture.course())) }
        return LiveActivityManager(
            preferences = liveUpdate,
            notifier = LiveActivityNotifier(context, liveUpdate, appPrefs, NotificationChannelRegistrar(context, appPrefs)),
            dataCache = dataCache,
            isSignedIn = { signedIn },
            appPrefs = appPrefs,
            classPreparingScheduler = ClassPreparingNotificationScheduler(context) { feed.value },
            academicCalendar = calendar,
            boundaryScheduler = LiveActivityBoundaryScheduler(context),
            appScope = liveScope,
            courseChanges = { feed.value },
            courseChangesUpdated = feed,
            // No account: the real scheduler here reads none either.
            isDemoSession = { true },
        ).also {
            runBlocking { it.refreshAndWait() }
            // Past the feed it starts with, so the next one is a change.
            awaitUntil { feed.subscriptionCount.value > 0 }
        }
    }

    /** Runs [liveScope] until [condition] holds; a refresh's reads on the IO dispatcher come back to it. */
    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            liveDispatcher.scheduler.advanceUntilIdle()
            if (condition() || System.currentTimeMillis() >= deadline) return
            Thread.sleep(20)
        }
    }

    /** The starts of Thursday's classes a reminder is armed for. */
    private fun remindersOnThursday(): List<Long> =
        shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
            .mapNotNull {
                // Robolectric 4.17 deprecates the alarm's operation field without a getter to use instead.
                @Suppress("DEPRECATION")
                val operation = it.operation
                shadowOf(operation).savedIntent.getLongExtra(ClassPreparingNotificationReceiver.EXTRA_START_MS, 0L)
                    .takeIf { start -> start > 0L }
            }
            .filter { Instant.ofEpochMilli(it).atZone(AppConstants.TAIPEI_ZONE).toLocalDate() == CourseChangeFixture.thursday }
            .sorted()

    private val movedToTen = changes(
        row(1, "start", periods = listOf("2", "3", "4"), values = mapOf("start" to "10:00")),
        row(2, "cancelled", periods = listOf("7", "8")),
    )

    @Test
    fun `a new course changes feed brings the Live Update's next wake to the changed class`() {
        overThursdayFeed()
        assertEquals(listOf(CourseChangeFixture.at("08:55").toEpochMilli() + 1_000L), refreshWakes())

        feed.value = movedToTen
        val wake = CourseChangeFixture.at("09:45").toEpochMilli() + 1_000L
        awaitUntil { refreshWakes() == listOf(wake) }

        assertEquals(listOf(wake), refreshWakes())
    }

    @Test
    fun `a new course changes feed arms the class reminders again`() {
        overThursdayFeed()
        assertEquals(listOf("09:10", "14:20").map { CourseChangeFixture.at(it).toEpochMilli() }, remindersOnThursday())

        feed.value = movedToTen
        val moved = listOf(CourseChangeFixture.at("10:00").toEpochMilli())
        awaitUntil { remindersOnThursday() == moved }

        assertEquals(moved, remindersOnThursday())
    }
}
