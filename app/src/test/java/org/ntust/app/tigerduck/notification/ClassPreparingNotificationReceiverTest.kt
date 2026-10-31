package org.ntust.app.tigerduck.notification

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import dagger.hilt.internal.GeneratedComponent
import dagger.hilt.internal.GeneratedComponentManager
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.network.SemesterCodes
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.coursechanges.CourseChangeStore
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZonedDateTime

/**
 * A stand-in for the Hilt application, which hands the receiver its
 * dependencies the way EntryPointAccessors asks for them.
 */
class EntryPointApplication : Application(), GeneratedComponentManager<Any> {
    lateinit var component: Any
    override fun generatedComponent(): Any = component
}

/** What the class reminder does when its alarm goes off after the student it was armed for has signed out. */
@RunWith(RobolectricTestRunner::class)
@Config(application = EntryPointApplication::class)
class ClassPreparingNotificationReceiverTest {

    private lateinit var context: EntryPointApplication
    private lateinit var credentials: CredentialManager

    // A course meeting on Mondays.
    private val monday = Course.fromSchedule(
        courseNo = "CS2001301",
        courseName = "Data Structures",
        schedule = mapOf(1 to listOf("3", "4")),
    )

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication() as EntryPointApplication
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        credentials = CredentialManager(context.getSharedPreferences("credentials-test", 0))
        val prefs = AppPreferences(context)
        prefs.classReminderEnabled = true
        val deps = object : ClassPreparingNotificationReceiver.Deps, GeneratedComponent {
            override fun academicCalendar() = AcademicCalendarStore(context, OkHttpClient(), prefs)
            override fun appPreferences() = prefs
            override fun courseChanges() = CourseChangeStore(
                context.getSharedPreferences("course_changes_receiver_test", 0), OkHttpClient(), { "" }, { true },
            )
            override fun credentials() = credentials
            override fun notificationChannels() = NotificationChannelRegistrar(context, prefs)
        }
        context.component = deps
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

    // Robolectric 4.17 deprecates the operation field but has no getter for it.
    @Suppress("DEPRECATION")
    private fun firstArmed(): Intent =
        shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
            .map { shadowOf(it.operation).savedIntent }
            .minBy { it.getLongExtra(ClassPreparingNotificationReceiver.EXTRA_START_MS, 0L) }

    private fun posted() = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

    @Test
    fun `an alarm armed for a student who signed out since posts nothing`() {
        credentials.ntustStudentId = "B00015999"
        armReminders()
        val alarm = firstArmed()

        // The sign-out clears the stored student ID.
        credentials.ntustStudentId = null
        ClassPreparingNotificationReceiver().onReceive(context, alarm)

        assertEquals(0, posted().size)
    }

    private fun armReminders(startedFor: String? = credentials.ntustStudentId) {
        ClassPreparingNotificationScheduler(context, storedStudentId = { credentials.ntustStudentId }).scheduleAll(
            coursesByTerm = mapOf(
                SemesterCodes.termOn(AcademicCalendar.EMPTY, java.time.LocalDate.now())
                    to listOf(monday),
            ),
            skippedDates = emptyMap(),
            leadTimeSec = 600,
            calendar = AcademicCalendar.EMPTY,
            optedInHolidayIds = emptySet(),
            startedFor = startedFor,
        )
    }

    @Test
    fun `an alarm armed under no student still posts`() {
        armReminders()
        val alarm = firstArmed()
        credentials.ntustStudentId = "B00015999"

        ClassPreparingNotificationReceiver().onReceive(context, alarm)

        assertEquals(2, posted().size)
    }

    @Test
    fun `an alarm armed for the student still signed in posts`() {
        credentials.ntustStudentId = "B00015999"
        armReminders()

        ClassPreparingNotificationReceiver().onReceive(context, firstArmed())

        assertEquals(2, posted().size)
    }

    @Test
    fun `an alarm armed for one student posts nothing once another is signed in`() {
        credentials.ntustStudentId = "B00015999"
        armReminders()
        val alarm = firstArmed()

        credentials.ntustStudentId = "B00016000"
        ClassPreparingNotificationReceiver().onReceive(context, alarm)

        assertEquals(0, posted().size)
    }

    @Test
    fun `a stored student ID that cannot be read does not lose the reminder`() {
        credentials.ntustStudentId = "B00015999"
        armReminders()
        val alarm = firstArmed()
        val readable = context.component as ClassPreparingNotificationReceiver.Deps
        context.component = object : ClassPreparingNotificationReceiver.Deps by readable, GeneratedComponent {
            override fun credentials(): CredentialManager = throw IllegalStateException("keystore unavailable")
        }

        ClassPreparingNotificationReceiver().onReceive(context, alarm)

        assertEquals(2, posted().size)
    }

    @Test
    fun `a refresh begun for one student arms nothing once the store reads another or nobody`() {
        credentials.ntustStudentId = "B00015999"
        armReminders(startedFor = "B00015999")
        assertEquals(true, alarmsArmed() > 0)

        credentials.ntustStudentId = null
        armReminders(startedFor = "B00015999")
        assertEquals(0, alarmsArmed())

        credentials.ntustStudentId = "B00016000"
        armReminders(startedFor = "B00015999")
        assertEquals(0, alarmsArmed())
    }

    @Test
    fun `an account that cannot be read keeps the reminders already armed`() {
        credentials.ntustStudentId = "B00015999"
        armReminders()
        val armedBefore = alarmsArmed()

        ClassPreparingNotificationScheduler(context, storedStudentId = { error("keystore unavailable") }).scheduleAll(
            coursesByTerm = emptyMap(),
            skippedDates = emptyMap(),
            leadTimeSec = 600,
            calendar = AcademicCalendar.EMPTY,
            optedInHolidayIds = emptySet(),
            startedFor = "B00015999",
        )

        assertEquals(true, armedBefore > 0)
        assertEquals(armedBefore, alarmsArmed())
    }

    private fun alarmsArmed() = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.size
}
