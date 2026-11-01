package org.ntust.app.tigerduck.ui.screen.home

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.auth.SignedInState
import org.ntust.app.tigerduck.data.CourseColorStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.liveactivity.LiveActivityBoundaryScheduler
import org.ntust.app.tigerduck.liveactivity.LiveActivityManager
import org.ntust.app.tigerduck.liveactivity.LiveActivityNotifier
import org.ntust.app.tigerduck.liveactivity.LiveActivityPreferences
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.notification.AssignmentNotificationScheduler
import org.ntust.app.tigerduck.notification.ClassReminderAlarms
import org.ntust.app.tigerduck.notification.NotificationChannelRegistrar
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.model.AssignmentFilter
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import java.time.LocalDateTime
import java.util.Date
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.util.testSignIn
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sun.misc.Unsafe

/**
 * Home as a signed-in student leaves it, with the real cache and preferences and no network.
 * Only what Home reads on the way is real: the other collaborators are bare instances, so a
 * test that makes Home reach one fails loudly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class HomeViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var cache: DataCache
    private lateinit var home: HomeViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication()
        val prefs = AppPreferences(context)
        val calendar = AcademicCalendarStore(context, OkHttpClient(), prefs)
        cache = DataCache(context, calendar)
        val credentials = CredentialManager(context.getSharedPreferences("credentials-test", 0))
        credentials.ntustStudentId = "B00015999"
        val signedIn = SignedInState(true)
        val auth = bare<AuthService>().also {
            it.set("signedInState", signedIn)
            it.set("authState", signedIn.signedIn)
            it.set("credentials", credentials)
        }
        val colors = bare<CourseColorStore>().also { it.set("changeEvent", MutableSharedFlow<Unit>()) }
        val courses = bare<CourseService>().also { it.set("academicCalendar", calendar) }
        home = HomeViewModel(
            bare(), auth, cache, courses, bare(), AssignmentNotificationScheduler(context), prefs, colors, bare(),
            liveActivity(context, prefs, calendar), bare(), bare(),
            bare(), bare(), bare(), bare(), bare(), LocalEdits(), bare(), bare(), bare(), bare(),
        )
    }

    /**
     * The real manager, whose refresh is queued on a scope nothing runs: a refresh is Home's
     * business only as far as it is asked for.
     */
    private fun liveActivity(context: Application, prefs: AppPreferences, calendar: AcademicCalendarStore): LiveActivityManager {
        val liveUpdate = LiveActivityPreferences(context)
        return LiveActivityManager(
            preferences = liveUpdate,
            notifier = LiveActivityNotifier(context, liveUpdate, prefs, NotificationChannelRegistrar(context, prefs)),
            dataCache = cache,
            isSignedIn = { true },
            appPrefs = prefs,
            classPreparingScheduler = object : ClassReminderAlarms {
                override fun scheduleAll(
                    coursesByTerm: Map<String, List<Course>>,
                    skippedDates: Map<String, List<String>>,
                    leadTimeSec: Long,
                    calendar: AcademicCalendar,
                    optedInHolidayIds: Set<Int>,
                    startedFor: String?,
                ): Long? = null
                override fun cancelAllTracked() = Unit
            },
            academicCalendar = calendar,
            boundaryScheduler = LiveActivityBoundaryScheduler(context),
            appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher()),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a course added in the class table reaches Home without a sync`() = runTest {
        try {
            dispatcher.scheduler.runCurrent()

            // What the class table does for an add: its edit is saved, with no sync round after it.
            cache.saveCourses(listOf(Course(courseNo = "CS1001", courseName = "Added in the class table")), testSignIn())

            assertEquals(listOf("CS1001"), homeCourseNos())
        } finally {
            // Home's clock ticker never ends, and runTest waits for every coroutine on its scheduler.
            home.viewModelScope.cancel()
        }
    }

    @Test
    fun `a courses save does not drop a mark Home holds that disk does not yet`() = runTest {
        try {
            dispatcher.scheduler.runCurrent()
            // A swipe still on its way to disk: Home holds the id, the file does not.
            home.markedCompletedIds.let { (it as MutableStateFlow<Set<String>>).value = setOf("7") }

            cache.saveCourses(listOf(Course(courseNo = "CS1001", courseName = "Added in the class table")), testSignIn())
            assertEquals(listOf("CS1001"), homeCourseNos())

            assertEquals(setOf("7"), home.markedCompletedIds.value)
        } finally {
            home.viewModelScope.cancel()
        }
    }

    @Test
    fun `a courses save leaves the assignment reminders armed`() = runTest {
        try {
            dispatcher.scheduler.runCurrent()
            // The scheduler's own record of what it armed: re-arming rewrites it.
            val tracker = RuntimeEnvironment.getApplication().getSharedPreferences("notification_tracker", 0)
            tracker.edit().putStringSet("scheduled_ids", setOf("armed")).commit()

            cache.saveCourses(listOf(Course(courseNo = "CS1001", courseName = "Added in the class table")), testSignIn())
            assertEquals(listOf("CS1001"), homeCourseNos())

            assertEquals(setOf("armed"), tracker.getStringSet("scheduled_ids", null))
        } finally {
            home.viewModelScope.cancel()
        }
    }

    /**
     * A Taipei wall time on a made-up day, as epoch millis. No test reads the real date: the
     * clock is set to these.
     */
    private fun taipei(time: String): Long =
        LocalDateTime.parse("2026-11-05T$time").atZone(AppConstants.TAIPEI_ZONE).toInstant().toEpochMilli()

    private fun assignment(id: String, due: Long) = Assignment(
        assignmentId = id, courseNo = "CS1001", courseName = "Fake course", title = id, dueDate = Date(due),
    )

    @Suppress("UNCHECKED_CAST")
    private fun showAssignments(vararg assignments: Assignment) {
        val all = home.javaClass.getDeclaredField("_allAssignments")
            .apply { isAccessible = true }.get(home) as MutableStateFlow<List<Assignment>>
        all.value = assignments.toList()
    }

    private fun upcomingIds(): List<String> = home.upcomingAssignments.value.map { it.assignmentId }

    /** Time the phone slept: the clock moved on while the scheduler's did not. */
    private var slept = 0L

    /** Home's clock tied to the scheduler's virtual time, so a test waits by advancing it, never by sleeping. */
    private fun runClockFrom(time: String) {
        val base = taipei(time)
        slept = 0L
        home.minuteClock = { base + dispatcher.scheduler.currentTime + slept }
    }

    /**
     * A running clock at 11:19:59. An assignment due at 11:19:59.5 sits first in 全部 while it is
     * still ahead, and moves behind the later one as soon as the minute turns: Home must redraw
     * at the boundary, not up to a minute after it.
     */
    @Test
    fun `an assignment falling due is moved to the past at the minute boundary`() = runTest {
        try {
            runClockFrom("11:19:59")
            home.setAssignmentFilter(AssignmentFilter.ALL)
            showAssignments(assignment("A", taipei("11:19:59.500")), assignment("B", taipei("11:30:00")))
            dispatcher.scheduler.runCurrent()
            assertEquals(listOf("A", "B"), upcomingIds())

            dispatcher.scheduler.advanceTimeBy(999)
            dispatcher.scheduler.runCurrent()
            assertEquals(listOf("A", "B"), upcomingIds())
            dispatcher.scheduler.advanceTimeBy(1)
            dispatcher.scheduler.runCurrent()

            assertEquals(listOf("B", "A"), upcomingIds())
        } finally {
            home.viewModelScope.cancel()
        }
    }

    /**
     * The phone sleeps through a boundary: the wait did not count the sleep, so Home still shows
     * the old minute until the page is back in front. Then it shows the right one at once, and
     * the next boundary still redraws.
     */
    @Test
    fun `coming back to the page after sleep redraws at once and keeps the next boundary`() = runTest {
        try {
            runClockFrom("11:19:30")
            home.setAssignmentFilter(AssignmentFilter.ALL)
            showAssignments(
                assignment("A", taipei("11:19:59.500")),
                assignment("C", taipei("11:20:59.500")),
                assignment("B", taipei("11:30:00")),
            )
            dispatcher.scheduler.runCurrent()
            assertEquals(listOf("A", "C", "B"), upcomingIds())

            slept = 45_000 // 11:20:15, and no virtual time has passed
            home.realignClock()
            dispatcher.scheduler.runCurrent()
            assertEquals(listOf("C", "B", "A"), upcomingIds())

            dispatcher.scheduler.advanceTimeBy(45_000) // 11:21:00
            dispatcher.scheduler.runCurrent()
            assertEquals(listOf("B", "C", "A"), upcomingIds())
        } finally {
            home.viewModelScope.cancel()
        }
    }

    /** A debug clock set to a new time redraws Home at once; a frozen one needs no tick for it. */
    @Test
    fun `moving a frozen debug clock across the boundary redraws Home at once`() = runTest {
        try {
            AppClock.setOverride(ClockOverride(taipei("11:19:59"), frozen = true, savedAtRealMillis = 0L))
            home.setAssignmentFilter(AssignmentFilter.ALL)
            showAssignments(assignment("A", taipei("11:20:00")), assignment("B", taipei("11:30:00")))
            dispatcher.scheduler.runCurrent()
            assertEquals(listOf("A", "B"), upcomingIds())

            AppClock.setOverride(ClockOverride(taipei("11:20:01"), frozen = true, savedAtRealMillis = 0L))
            dispatcher.scheduler.runCurrent()

            assertEquals(listOf("B", "A"), upcomingIds())
        } finally {
            AppClock.setOverride(null)
            home.viewModelScope.cancel()
        }
    }

    /** Home's courses once its collectors have had the time the cache's IO reads take. */
    private suspend fun homeCourseNos(): List<String> {
        repeat(100) {
            dispatcher.scheduler.runCurrent()
            val nos = home.allCourses.value.map { it.courseNo }
            if (nos.isNotEmpty()) return nos
            Thread.sleep(20)
        }
        return home.allCourses.value.map { it.courseNo }
    }
}

private inline fun <reified T : Any> bare(): T {
    val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
    return unsafe.allocateInstance(T::class.java) as T
}

private fun Any.set(field: String, value: Any?) {
    javaClass.getDeclaredField(field).apply { isAccessible = true }.set(this, value)
}
