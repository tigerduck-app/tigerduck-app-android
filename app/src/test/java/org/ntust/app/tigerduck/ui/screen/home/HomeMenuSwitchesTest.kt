package org.ntust.app.tigerduck.ui.screen.home

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.liveactivity.LiveActivityBoundaryScheduler
import org.ntust.app.tigerduck.liveactivity.LiveActivityManager
import org.ntust.app.tigerduck.liveactivity.LiveActivityNotifier
import org.ntust.app.tigerduck.liveactivity.LiveActivityPreferences
import org.ntust.app.tigerduck.notification.AssignmentNotificationScheduler
import org.ntust.app.tigerduck.notification.AssignmentReminderOffset
import org.ntust.app.tigerduck.notification.ClassReminderAlarms
import org.ntust.app.tigerduck.notification.NotificationChannelRegistrar
import org.ntust.app.tigerduck.notification.SystemPermissions
import org.ntust.app.tigerduck.push.AssignmentReminderAlarms
import org.ntust.app.tigerduck.push.AssignmentReminderSyncStore
import org.ntust.app.tigerduck.push.ClassReminderSyncStore
import org.ntust.app.tigerduck.push.LastSyncedSections
import org.ntust.app.tigerduck.push.LastSyncedSeed
import org.ntust.app.tigerduck.push.NotificationSettingsSync
import org.ntust.app.tigerduck.push.SettingsDocumentEnvelope
import org.ntust.app.tigerduck.push.SettingsDocumentTransport
import org.ntust.app.tigerduck.push.SettingsWriteResult
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.AssignmentReminderSettingsState
import org.ntust.app.tigerduck.ui.screen.settings.AssignmentReminderSettingsViewModel
import org.ntust.app.tigerduck.ui.screen.settings.AssignmentReminderSwitch
import org.ntust.app.tigerduck.ui.screen.settings.LiveActivitySettingsViewModel
import org.ntust.app.tigerduck.ui.screen.settings.LiveUpdatesSwitch
import org.ntust.app.tigerduck.util.bare
import org.ntust.app.tigerduck.util.set
import org.ntust.app.tigerduck.util.testSignIn
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Home's ⋮ turns its four switches with the writes their settings rows make, so each reads the
 * same in both places: over the real preferences, with the settings document held in memory.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class HomeMenuSwitchesTest {

    private val context: Application = RuntimeEnvironment.getApplication()
    private val prefs = AppPreferences(context)
    private val queue = CoroutineScope(SupervisorJob() + StandardTestDispatcher())

    /** What the sync's assignments section is told: an edit waiting to go up. */
    private val assignmentStore = object : AssignmentReminderSyncStore {
        override var enabled = true
        override var offsets = AssignmentReminderOffset.DEFAULTS
        override var hasUnconfirmedEdit = false
    }

    private val sync = NotificationSettingsSync(
        documents = { nothingStored },
        currentSignIn = { testSignIn() },
        liveActivityPreferences = LiveActivityPreferences(context),
        assignmentStore = assignmentStore,
        assignmentAlarms = object : AssignmentReminderAlarms {
            override fun cancelAll() = Unit
            override suspend fun rescheduleFromCache() = Unit
        },
        classReminderStore = object : ClassReminderSyncStore {
            override var enabled = true
            override var leadMinutes = 10
            override var hasPickedLead = false
            override var hasUnconfirmedEdit = false
        },
        lastSynced = object : LastSyncedSections {
            override var liveActivity: String? = null
            override var assignments: String? = null
            override var courses: String? = null
        },
        lastSyncedSeed = object : LastSyncedSeed {
            override var done = false
        },
        cloudSyncEnabled = { false },
        syncLiveActivity = { false },
        syncAssignmentReminders = { false },
        isLoggedIn = { false },
        schoolAccountStored = { false },
        syncLiveActivityChanged = MutableSharedFlow(),
        syncAssignmentRemindersChanged = MutableSharedFlow(),
        scope = queue,
    )

    /** Only the parts of AppState these switches reach: the preferences and their Compose copies. */
    private val appState = bare<AppState>().also {
        it.set("prefs", prefs)
        it.set("assignmentReminderSettings", AssignmentReminderSettingsState(prefs, queue))
        it.set("showAbsoluteAssignmentTimeState\$delegate", mutableStateOf(prefs.showAbsoluteAssignmentTime))
        it.set("invertSliderDirectionState\$delegate", mutableStateOf(prefs.invertSliderDirection))
    }

    private val calendar = AcademicCalendarStore(context, OkHttpClient(), prefs)
    private val cache = DataCache(context, calendar)
    private val reminders = AssignmentReminderSwitch(appState, AssignmentNotificationScheduler(context), cache, sync)
    private val liveUpdatePrefs = LiveActivityPreferences(context)
    private val manager = liveActivityManager()
    private val liveUpdates = LiveUpdatesSwitch(liveUpdatePrefs, manager)
    private val menu = HomeMenuViewModel(appState, reminders, liveUpdates)

    @After
    fun tearDown() = queue.cancel()

    @Test
    fun `the full due date and the slider's direction are the switches Settings turns`() {
        menu.setFullDueDate(true)
        menu.setInvertSlider(true)

        assertTrue(prefs.showAbsoluteAssignmentTime)
        assertTrue(prefs.invertSliderDirection)
        assertTrue(menu.switches().fullDueDate)
        assertTrue(menu.switches().invertSlider)
    }

    @Test
    fun `reminders turned off in the menu are off on their page and wait to go to the other devices`() {
        val page = AssignmentReminderSettingsViewModel(appState, reminders, sync)
        shadowOf(Looper.getMainLooper()).idle()

        menu.setAssignmentReminders(false)

        assertFalse(prefs.notifyAssignments)
        assertFalse(page.appState.notifyAssignments)
        assertTrue("the edit is queued, as the page's own is", assignmentStore.hasUnconfirmedEdit)

        page.setEnabled(true)
        assertTrue(menu.switches().assignmentReminders)
    }

    @Test
    fun `Live Updates turned off in the menu read off on their page, and on again from it`() {
        menu.setLiveUpdates(false)
        val page = LiveActivitySettingsViewModel(liveUpdatePrefs, manager, sync, SystemPermissions(context), liveUpdates)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(LiveActivityPreferences(context).isEnabled)
        assertFalse(page.state.value.enabled)

        page.setEnabled(true)
        assertEquals(true, menu.switches().liveUpdates)
    }

    @Test
    fun `none of the four waits for TigerSync, which is off here`() {
        menu.setAssignmentReminders(false)
        menu.setLiveUpdates(false)
        menu.setAssignmentReminders(true)
        menu.setLiveUpdates(true)

        assertTrue(prefs.notifyAssignments)
        assertTrue(liveUpdatePrefs.isEnabled)
    }

    private val nothingStored = object : SettingsDocumentTransport {
        override suspend fun read(): SettingsDocumentEnvelope<JsonObject>? = null

        override suspend fun write(
            document: JsonObject,
            baseRevision: Long?,
            isCurrent: () -> Boolean,
        ): SettingsWriteResult<JsonObject> = SettingsWriteResult.Written(1L)
    }

    /** The real manager, whose refreshes queue on a scope nothing runs. */
    private fun liveActivityManager() = LiveActivityManager(
        preferences = liveUpdatePrefs,
        notifier = LiveActivityNotifier(context, liveUpdatePrefs, prefs, NotificationChannelRegistrar(context, prefs)),
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
        appScope = queue,
    )
}
