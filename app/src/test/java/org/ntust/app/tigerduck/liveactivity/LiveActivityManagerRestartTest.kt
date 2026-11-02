package org.ntust.app.tigerduck.liveactivity

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.auth.session.AccountSession
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.notification.ClassPreparingNotificationScheduler
import org.ntust.app.tigerduck.notification.NotificationChannelRegistrar
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.timeline.CourseChanges
import org.ntust.app.tigerduck.util.testSignIn
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Date

/**
 * A sign-out stops the Live Update; it must not stop the manager. Signed in again in the same
 * process, the next refresh shows the Live Update again rather than waiting for a restart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LiveActivityManagerRestartTest {

    private lateinit var context: Application
    private lateinit var notifications: NotificationManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var signedIn = true

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifications = context.getSystemService(NotificationManager::class.java)
    }

    @After
    fun tearDown() = scope.cancel()

    private fun manager(
        session: AccountSession = AccountSession(SignInSeries(demo = true), scope),
        // Read last before the post, so a test can land a sign-out there.
        courseChanges: () -> CourseChanges = { CourseChanges.EMPTY },
    ): LiveActivityManager {
        val prefs = AppPreferences(context)
        val calendar = AcademicCalendarStore(context, OkHttpClient(), prefs)
        val dataCache = DataCache(context, calendar)
        val livePrefs = LiveActivityPreferences(context)
        val channels = NotificationChannelRegistrar(context, prefs).also { it.register() }
        runBlocking {
            // Due within the assignment lead time, so the Live Update counts down to it.
            dataCache.saveAssignments(
                listOf(
                    Assignment(
                        assignmentId = "due-soon",
                        courseNo = "CS1001301",
                        courseName = "Course",
                        title = "Task",
                        dueDate = Date(AppClock.nowMillis() + 20 * 60_000),
                    ),
                ),
                testSignIn(),
            )
        }
        return LiveActivityManager(
            preferences = livePrefs,
            notifier = LiveActivityNotifier(context, livePrefs, prefs, channels),
            dataCache = dataCache,
            isSignedIn = { signedIn },
            appPrefs = prefs,
            classPreparingScheduler = ClassPreparingNotificationScheduler(context),
            academicCalendar = calendar,
            boundaryScheduler = LiveActivityBoundaryScheduler(context),
            appScope = scope,
            courseChanges = courseChanges,
            session = session,
        )
    }

    private fun showing(): Boolean =
        notifications.activeNotifications.any { it.id == LiveActivityNotifier.NOTIFICATION_ID }

    private fun awaitShowing(expected: Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (showing() != expected && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    @Test
    fun `a sign-out while a refresh reads its data puts nothing up`() {
        val series = SignInSeries(demo = true)
        val manager = manager(AccountSession(series, scope)) {
            // The state still reads signed in: the sign-out lowers it after the sign-in ends.
            series.end()
            CourseChanges.EMPTY
        }

        runBlocking { manager.refreshAndWait() }

        assertFalse("the student who left is on the phone's status bar", showing())
    }

    @Test
    fun `a same-student sign-in while a refresh reads its data leaves the post to the next refresh`() {
        val series = SignInSeries(demo = true)
        val manager = manager(AccountSession(series, scope)) {
            series.end()
            series.open()
            CourseChanges.EMPTY
        }

        runBlocking { manager.refreshAndWait() }

        assertFalse(showing())
    }

    @Test
    fun `a refresh under a sign-in that stands shows the Live Update`() {
        val manager = manager(AccountSession(SignInSeries(demo = true), scope))

        runBlocking { manager.refreshAndWait() }

        assertTrue(showing())
    }

    @Test
    fun `a refresh after signing in again shows the Live Update`() {
        val manager = manager()
        manager.refresh()
        awaitShowing(true)
        assertTrue("the fixture must show a Live Update", showing())

        signedIn = false
        manager.stop()
        assertFalse(showing())

        signedIn = true
        manager.refresh()
        awaitShowing(true)

        assertTrue(showing())
    }
}
