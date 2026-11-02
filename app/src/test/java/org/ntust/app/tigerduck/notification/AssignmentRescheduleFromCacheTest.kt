package org.ntust.app.tigerduck.notification

import android.app.AlarmManager
import android.app.Application
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.CourseIgnores
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.util.testSignIn
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Date

/**
 * The reminders the class table and the calendar rebuild after they write
 * the assignment cache, an empty write included.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class AssignmentRescheduleFromCacheTest {

    private lateinit var context: Application
    private lateinit var prefs: AppPreferences
    private lateinit var dataCache: DataCache
    private lateinit var scheduler: AssignmentNotificationScheduler

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs = AppPreferences(context)
        dataCache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), prefs))
        scheduler = AssignmentNotificationScheduler(context)
    }

    private fun reminders() =
        shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms

    /** The assignment id of every armed reminder, one per alarm. */
    // Robolectric 4.17 deprecates the operation field but has no getter for it.
    @Suppress("DEPRECATION")
    private fun armedIds(): List<String?> = reminders().map {
        shadowOf(it.operation).savedIntent
            .getStringExtra(AssignmentNotificationReceiver.EXTRA_ASSIGNMENT_ID)
    }

    private fun dueInThreeDays(id: String) = Assignment(
        assignmentId = id,
        courseNo = "CS101",
        courseName = "CS",
        title = "HW $id",
        dueDate = Date(AppClock.nowMillis() + 3 * DAY_MS),
    )

    @Test
    fun `the cached assignments get their reminders`() = runBlocking {
        dataCache.saveAssignments(listOf(dueInThreeDays("1")), testSignIn())

        scheduler.rescheduleFromCache(dataCache, prefs)

        assertTrue(reminders().isNotEmpty())
    }

    @Test
    fun `an emptied cache takes every reminder away`() = runBlocking {
        dataCache.saveAssignments(listOf(dueInThreeDays("1")), testSignIn())
        scheduler.rescheduleFromCache(dataCache, prefs)

        // What a TA account's cache holds once Moodle leaves out the courses it TAs.
        dataCache.saveAssignments(emptyList(), testSignIn())
        scheduler.rescheduleFromCache(dataCache, prefs)

        assertTrue(reminders().isEmpty())
    }

    @Test
    fun `nothing is armed while reminders are off`() = runBlocking {
        prefs.notifyAssignments = false
        dataCache.saveAssignments(listOf(dueInThreeDays("1")), testSignIn())

        scheduler.rescheduleFromCache(dataCache, prefs)

        assertTrue(reminders().isEmpty())
    }

    @Test
    fun `homework ignored, marked done or of an ignored course gets no reminder`() = runBlocking {
        dataCache.saveAssignments(
            listOf(
                dueInThreeDays("open"),
                dueInThreeDays("ignored"),
                dueInThreeDays("marked"),
                dueInThreeDays("course-ignored").copy(courseNo = "IGNORED"),
            ), testSignIn()
        )
        dataCache.saveIgnoredAssignments(setOf("ignored"), testSignIn())
        dataCache.saveMarkedCompletedAssignments(setOf("marked"), testSignIn())
        dataCache.saveIgnoredCourses(
            setOf(CourseIgnores.key(dataCache.currentSemesterCode(), "IGNORED")),
            testSignIn(),
        )

        scheduler.rescheduleFromCache(dataCache, prefs)

        assertEquals(setOf("open"), armedIds().toSet())
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }

    @Test
    fun `one assignment armed again for an account that signed out leaves the next account's reminders alone`() = runBlocking {
        val series = SignInSeries()
        val departing = series.current
        series.end()
        dataCache.clearAllUserData()
        series.open()
        dataCache.saveAssignments(listOf(dueInThreeDays("2")), series.current)
        scheduler.scheduleAll(dataCache.loadOutstandingAssignments(), setOf(AssignmentReminderOffset.HR2))

        scheduler.rescheduleOne("2", dataCache, prefs, departing)

        assertEquals(listOf("2"), armedIds())
    }

    @Test
    fun `reminders armed again for an account that signed out leave the next account's alone`() = runBlocking {
        val series = SignInSeries()
        val departing = series.current
        dataCache.saveAssignments(listOf(dueInThreeDays("1")), departing)
        series.end()
        dataCache.clearAllUserData()
        series.open()
        dataCache.saveAssignments(listOf(dueInThreeDays("2")), series.current)
        // The next account's, for the one offset it chose, before the departing account's work
        // arms what it reads, for every default offset.
        scheduler.scheduleAll(dataCache.loadOutstandingAssignments(), setOf(AssignmentReminderOffset.HR2))

        scheduler.rescheduleFromCache(dataCache, prefs, departing)

        assertEquals(listOf("2"), armedIds())
    }
}
