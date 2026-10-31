@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck.liveactivity

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.notification.ClassPreparingNotificationReceiver
import org.ntust.app.tigerduck.notification.ClassReminderAlarms
import org.ntust.app.tigerduck.notification.NotificationChannelRegistrar
import org.ntust.app.tigerduck.shared.Course
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The class-starting banners go once their class has begun, whatever the
 * Live Update shows: a due-soon assignment now shows first during class, so
 * the switch to "in class" that used to clear them may never come.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ClassBannerClearingTest {

    private val context: Application = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val appPrefs = AppPreferences(context)
    private val liveUpdate = LiveActivityPreferences(context)
    private val notifier = LiveActivityNotifier(context, liveUpdate, appPrefs, NotificationChannelRegistrar(context, appPrefs))
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationChannelRegistrar(context, appPrefs).register()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun banner(id: Int, startMs: Long) = ClassPreparingNotificationReceiver.post(
        context, "system", id, "Course $id", "", "", startMs, startMs + 50 * 60_000L, 10 * 60_000L,
    )

    private fun bannerIds(): Set<Int> = manager.activeNotifications
        .filter { it.notification.channelId == ClassPreparingNotificationReceiver.CHANNEL_ID && !it.notification.isSummary }
        .map { it.id }
        .toSet()

    private fun anyClassNotification(): Boolean =
        manager.activeNotifications.any { it.notification.channelId == ClassPreparingNotificationReceiver.CHANNEL_ID }

    private val Notification.isSummary: Boolean get() = flags and Notification.FLAG_GROUP_SUMMARY != 0

    @Test
    fun `a banner whose class has begun goes, and the next class's stays`() {
        banner(1, startMs = now - 60_000L)
        banner(2, startMs = now + 30 * 60_000L)

        notifier.clearStartedClassBanners(now)

        assertEquals(setOf(2), bannerIds())
    }

    @Test
    fun `the stack's summary goes with its last banner`() {
        banner(1, startMs = now - 60_000L)

        notifier.clearStartedClassBanners(now)

        assertTrue("no banner or summary left", !anyClassNotification())
    }

    private val calendarStore = AcademicCalendarStore(context, OkHttpClient(), appPrefs)
    private val dataCache = DataCache(context, calendarStore)

    /**
     * The real manager, over class reminder alarms that report
     * [nextClassStart] as the start of the next class they cover.
     */
    private fun liveActivityManager(nextClassStart: Long? = null) = LiveActivityManager(
        preferences = liveUpdate,
        notifier = notifier,
        dataCache = dataCache,
        isSignedIn = { true },
        appPrefs = appPrefs,
        classPreparingScheduler = object : ClassReminderAlarms {
            override fun scheduleAll(
                coursesByTerm: Map<String, List<Course>>,
                skippedDates: Map<String, List<String>>,
                leadTimeSec: Long,
                calendar: AcademicCalendar,
                optedInHolidayIds: Set<Int>,
                startedFor: String?,
            ): Long? = nextClassStart
            override fun cancelAllTracked() = Unit
        },
        academicCalendar = calendarStore,
        boundaryScheduler = LiveActivityBoundaryScheduler(context),
        appScope = scope,
    )

    /** When the Live Update's wake-up alarm, the one that brings a refresh, is set for. */
    private fun refreshWakes(): List<Long> =
        shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
            .filter {
                // Robolectric 4.17 deprecates the alarm's operation field without a getter to use instead.
                @Suppress("DEPRECATION")
                val operation = it.operation
                it.getType() == AlarmManager.RTC_WAKEUP &&
                    shadowOf(operation).savedIntent.action == LiveActivityBoundaryScheduler.ACTION_BOUNDARY
            }
            .map { it.triggerAtMs }

    @Test
    fun `a refresh clears a started banner while the Live Update shows a deadline`() {
        val manager = liveActivityManager()
        runBlocking {
            dataCache.saveAssignments(
                listOf(
                    org.ntust.app.tigerduck.data.model.Assignment(
                        assignmentId = "900002",
                        courseNo = "CS3204301",
                        courseName = "Operating Systems",
                        title = "Lab 2",
                        dueDate = java.util.Date(now + 60 * 60_000L),
                    )
                )
            )
        }
        banner(1, startMs = now - 60_000L)

        runBlocking { manager.refreshAndWait() }

        assertEquals(
            LiveActivityNotifier.NOTIFICATION_ID,
            this.manager.activeNotifications.single {
                it.notification.channelId != ClassPreparingNotificationReceiver.CHANNEL_ID
            }.id,
        )
        assertTrue(!anyClassNotification())
    }

    @Test
    fun `with the Live Update off, a refresh is still kept for when the next class begins`() {
        liveUpdate.isEnabled = false
        appPrefs.classReminderEnabled = true
        val classStart = now + 5 * 60_000L
        banner(1, startMs = classStart)

        runBlocking { liveActivityManager(nextClassStart = classStart).refreshAndWait() }

        assertEquals("the banner stays until its class begins", setOf(1), bannerIds())
        assertEquals(listOf(classStart + 1_000L), refreshWakes())
    }

    @Test
    fun `with the Live Update off, the refresh at class start clears the banner and waits for the next class`() {
        liveUpdate.isEnabled = false
        appPrefs.classReminderEnabled = true
        banner(1, startMs = now - 1_000L)
        val nextWeek = now + 7 * DAY_MS

        runBlocking { liveActivityManager(nextClassStart = nextWeek).refreshAndWait() }

        assertTrue(!anyClassNotification())
        assertEquals(listOf(nextWeek + 1_000L), refreshWakes())
    }

    @Test
    fun `with the Live Update and class reminders both off, no refresh is kept waiting`() {
        liveUpdate.isEnabled = false
        appPrefs.classReminderEnabled = false

        runBlocking { liveActivityManager(nextClassStart = now + 5 * 60_000L).refreshAndWait() }

        assertEquals(emptyList<Long>(), refreshWakes())
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60_000L
    }
}
