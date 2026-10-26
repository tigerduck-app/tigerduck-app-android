package org.ntust.app.tigerduck.auth

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.notification.AssignmentNotificationScheduler
import org.ntust.app.tigerduck.notification.AssignmentReminderOffset
import org.ntust.app.tigerduck.notification.BackgroundSyncWorker
import org.ntust.app.tigerduck.notification.NotificationChannelRegistrar
import org.ntust.app.tigerduck.notification.NotificationChannels
import org.ntust.app.tigerduck.notification.NotificationGroup
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Date

/**
 * What follows the school account's sign-in and sign-out, whichever page they came from: the
 * onboarding wizard and Settings both sign in through AuthService, and the effects follow its
 * signed-in state rather than either page.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class AccountSessionEffectsTest {

    private lateinit var context: Application
    private lateinit var workManager: WorkManager
    private lateinit var assignmentReminders: AssignmentNotificationScheduler
    private val scope = CoroutineScope(UnconfinedTestDispatcher())
    /** The account's state as AuthService keeps it: each change counted. */
    private class SignedIn {
        val flow = MutableStateFlow(AccountState(false, changes = 0))
        var value: Boolean
            get() = flow.value.signedIn
            set(signedIn) {
                if (signedIn != flow.value.signedIn) flow.value = AccountState(signedIn, flow.value.changes + 1)
            }
    }

    private val signedIn = SignedIn()
    private val liveUpdates = mutableListOf<String>()
    private val browserClears = mutableListOf<Boolean>()
    private var clearingOwed = false

    /** Holds each browser clearing until it is completed: a slow one, as a real deletion can be. */
    private var clearingGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    private val signIns = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
        assignmentReminders = AssignmentNotificationScheduler(context)
    }

    @After
    fun tearDown() = scope.cancel()

    private fun effects(startSignedIn: Boolean): AccountSessionEffects {
        signedIn.flow.value = AccountState(startSignedIn, changes = 0)
        return AccountSessionEffects(
            context = context,
            accountState = signedIn.flow,
            signIns = signIns,
            assignmentReminders = assignmentReminders,
            refreshLiveUpdates = { liveUpdates += "refresh" },
            stopLiveUpdates = { liveUpdates += "stop" },
            clearBrowser = {
                browserClears += signedIn.value
                clearingGate?.await()
            },
            browserClearingOwed = { clearingOwed },
        ).also { it.start(scope) }
    }

    private fun backgroundSync(): List<WorkInfo.State> =
        workManager.getWorkInfosForUniqueWork(BackgroundSyncWorker.UNIQUE_NAME).get().map { it.state }

    @Test
    fun `a sign-in schedules the background sync, as the onboarding wizard's does`() {
        // A fresh install: nobody is signed in when the app starts.
        effects(startSignedIn = false)
        assertEquals(emptyList<WorkInfo.State>(), backgroundSync())

        signedIn.value = true

        assertEquals(listOf(WorkInfo.State.ENQUEUED), backgroundSync())
    }

    @Test
    fun `a sign-out cancels the background sync`() {
        effects(startSignedIn = true)
        BackgroundSyncWorker.schedule(context)

        signedIn.value = false

        assertEquals(listOf(WorkInfo.State.CANCELLED), backgroundSync())
    }

    @Test
    fun `the state the app starts in is not a sign-in`() {
        // MainActivity schedules for an account signed in at launch; a launch is not a change.
        effects(startSignedIn = true)

        assertEquals(emptyList<WorkInfo.State>(), backgroundSync())
    }

    @Test
    fun `a sign-out and a sign-in each clear the in-app browser`() {
        effects(startSignedIn = true)
        assertEquals(emptyList<Boolean>(), browserClears)

        signedIn.value = false
        signedIn.value = true

        // Cleared once signed out, and again at the next student's sign-in.
        assertEquals(listOf(false, true), browserClears)
    }

    @Test
    fun `a student signing in over another clears the in-app browser though it never signs out`() {
        effects(startSignedIn = true)

        signIns.tryEmit(Unit)

        assertEquals(listOf(true), browserClears)
    }

    @Test
    fun `a clearing a process death cut short is run again at the start`() {
        clearingOwed = true
        effects(startSignedIn = true)

        assertEquals(listOf(true), browserClears)
    }

    @Test
    fun `a sign-out and sign-in while the browser is still being cleared each do what they do`() {
        // A fresh install; the browser's clearing for the first sign-in takes its time.
        effects(startSignedIn = false)
        clearingGate = kotlinx.coroutines.CompletableDeferred()
        signedIn.value = true

        // The student signs out, and another signs in, before that clearing ends.
        signedIn.value = false
        signedIn.value = true
        clearingGate?.complete(Unit)

        assertEquals("the sign-out stopped the Live Update and the sign-in brought it back", listOf("refresh", "stop", "refresh"), liveUpdates)
        assertEquals("cleared at the sign-in, the sign-out and the next sign-in", listOf(true, false, true), browserClears)
        assertEquals(listOf(WorkInfo.State.ENQUEUED), backgroundSync().filter { it != WorkInfo.State.CANCELLED })
    }

    @Test
    fun `a sign-out and sign-in the watcher sees only once, as the same value, still count as both`() {
        val scheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
        val late = CoroutineScope(kotlinx.coroutines.test.StandardTestDispatcher(scheduler))
        signedIn.flow.value = AccountState(true, changes = 0)
        AccountSessionEffects(
            context = context,
            accountState = signedIn.flow,
            signIns = signIns,
            assignmentReminders = assignmentReminders,
            refreshLiveUpdates = { liveUpdates += "refresh" },
            stopLiveUpdates = { liveUpdates += "stop" },
            clearBrowser = { browserClears += signedIn.value },
            browserClearingOwed = { false },
        ).start(late)
        scheduler.runCurrent()

        // Out and back in before the watcher next runs.
        signedIn.value = false
        signedIn.value = true
        scheduler.runCurrent()

        assertEquals(listOf("stop", "refresh"), liveUpdates)

        // And the other way: in and back out, which leaves the account that came and went to clear.
        liveUpdates.clear()
        signedIn.value = false
        scheduler.runCurrent()
        liveUpdates.clear()
        signedIn.value = true
        signedIn.value = false
        scheduler.runCurrent()

        assertEquals(listOf("stop"), liveUpdates)
        late.cancel()
    }

    @Test
    fun `a sign-out before the watcher first runs is not taken for where it started`() {
        val scheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
        val late = CoroutineScope(kotlinx.coroutines.test.StandardTestDispatcher(scheduler))
        signedIn.flow.value = AccountState(true, changes = 0)
        AccountSessionEffects(
            context = context,
            accountState = signedIn.flow,
            signIns = signIns,
            assignmentReminders = assignmentReminders,
            refreshLiveUpdates = { liveUpdates += "refresh" },
            stopLiveUpdates = { liveUpdates += "stop" },
            clearBrowser = { browserClears += signedIn.value },
            browserClearingOwed = { false },
        ).start(late)

        // Signed out as the app starts, before the watcher has run once.
        signedIn.value = false
        scheduler.runCurrent()

        assertEquals(listOf("stop"), liveUpdates)
        assertEquals(listOf(false), browserClears)
        late.cancel()
    }

    @Test
    fun `a sign-out stops the Live Update and a sign-in brings it back`() {
        effects(startSignedIn = true)

        signedIn.value = false
        signedIn.value = true

        assertEquals(listOf("stop", "refresh"), liveUpdates)
    }

    private fun post(id: Int, channel: String, group: String?) {
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("n$id")
        if (group != null) builder.setGroup(group)
        context.getSystemService(NotificationManager::class.java).notify(id, builder.build())
    }

    /** What the shade shows, as "tag:id" for the tagged stack summaries and the id for the rest. */
    private fun shown(): List<String> =
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .map { if (it.tag != null) "${it.tag}:${it.id}" else "${it.id}" }
            .sorted()

    @Test
    fun `a sign-out takes down the student's notifications and leaves school mail's`() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationChannelRegistrar(context, AppPreferences(context)).register()
        effects(startSignedIn = true)
        post(101, NotificationChannels.CLASS_PREPARING, NotificationGroup.CLASS.key)
        post(102, NotificationChannels.ASSIGNMENT_DUE, NotificationGroup.ASSIGNMENT.key)
        post(103, NotificationChannels.BULLETINS_SOUND, NotificationGroup.OTHER.key)
        // The sign-in notice.
        post(104, NotificationChannels.SYSTEM, NotificationGroup.OTHER.key)
        // From before the stacks: an assignment reminder posted with no group.
        post(105, NotificationChannels.ASSIGNMENT_DUE, null)
        post(106, NotificationChannels.SCHOOL_MAIL, NotificationGroup.MAIL.key)
        // The mail sign-in failure, which is on System and may have no group.
        post(107, NotificationChannels.SYSTEM, null)
        NotificationGroup.CLASS.postSummary(context)
        NotificationGroup.MAIL.postSummary(context)

        signedIn.value = false

        // School mail goes with its own account's sign-out, not this one's.
        assertEquals(listOf("106", "107", "group_summary:3"), shown())
    }

    @Test
    fun `a sign-out disarms the assignment reminders`() {
        effects(startSignedIn = true)
        val due = Assignment(
            assignmentId = "a1",
            courseNo = "CS1001301",
            courseName = "Course",
            title = "Task",
            dueDate = Date(AppClock.nowMillis() + 3 * 86_400_000L),
        )
        assignmentReminders.scheduleAll(listOf(due), setOf(AssignmentReminderOffset.HR24))
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        assertEquals(1, alarms.scheduledAlarms.size)

        signedIn.value = false

        assertEquals(0, alarms.scheduledAlarms.size)
    }
}
