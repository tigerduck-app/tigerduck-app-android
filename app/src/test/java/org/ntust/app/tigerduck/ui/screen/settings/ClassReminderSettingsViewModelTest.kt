package org.ntust.app.tigerduck.ui.screen.settings

import android.app.Application
import android.os.Looper
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.liveactivity.LiveActivityPreferences
import org.ntust.app.tigerduck.notification.AssignmentReminderOffset
import org.ntust.app.tigerduck.notification.SystemPermissions
import org.ntust.app.tigerduck.push.AssignmentReminderAlarms
import org.ntust.app.tigerduck.push.AssignmentReminderSyncStore
import org.ntust.app.tigerduck.push.ClassReminderSyncStore
import org.ntust.app.tigerduck.push.NotificationSettingsSync
import org.ntust.app.tigerduck.push.SettingsDocumentEnvelope
import org.ntust.app.tigerduck.push.SettingsDocumentTransport
import org.ntust.app.tigerduck.push.SettingsWriteResult
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Class reminders screen's state holder, over the real preferences and
 * the real settings sync, with the account's document held in memory.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ClassReminderSettingsViewModelTest {

    private val context: Application = RuntimeEnvironment.getApplication()
    private val prefs = AppPreferences(context)
    // The sync's push queue, on virtual time.
    private val scheduler = TestCoroutineScheduler()
    private val queue = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))

    /** The account's `notification` document. */
    private var document: JsonObject? = null
    private val writes = mutableListOf<JsonObject>()

    private val transport = object : SettingsDocumentTransport {
        override suspend fun read() = document?.let { SettingsDocumentEnvelope(document = it, revision = 1L) }

        override suspend fun write(
            document: JsonObject,
            baseRevision: Long?,
            isCurrent: () -> Boolean,
        ): SettingsWriteResult<JsonObject> {
            writes += document
            this@ClassReminderSettingsViewModelTest.document = document
            return SettingsWriteResult.Written(2L)
        }
    }

    private val sync = NotificationSettingsSync(
        transport = transport,
        liveActivityPreferences = LiveActivityPreferences(context),
        assignmentStore = object : AssignmentReminderSyncStore {
            override var enabled = true
            override var offsets = AssignmentReminderOffset.DEFAULTS
            override var hasUnconfirmedEdit = false
        },
        assignmentAlarms = object : AssignmentReminderAlarms {
            override fun cancelAll() = Unit
            override suspend fun rescheduleFromCache() = Unit
        },
        classReminderStore = object : ClassReminderSyncStore {
            override var enabled: Boolean
                get() = prefs.classReminderEnabled
                set(value) { prefs.classReminderEnabled = value }
            override var leadMinutes: Int
                get() = prefs.classReminderLeadMinutes
                set(value) { prefs.classReminderLeadMinutes = value }
            override var hasPickedLead: Boolean
                get() = prefs.hasPickedClassReminderLead
                set(value) { prefs.hasPickedClassReminderLead = value }
            override var hasUnconfirmedEdit: Boolean
                get() = prefs.hasUnconfirmedClassReminderSyncEdit
                set(value) { prefs.hasUnconfirmedClassReminderSyncEdit = value }
        },
        cloudSyncEnabled = { true },
        syncLiveActivity = { false },
        syncAssignmentReminders = { false },
        isLoggedIn = { true },
        syncLiveActivityChanged = MutableSharedFlow(),
        syncAssignmentRemindersChanged = MutableSharedFlow(),
        scope = queue,
    )

    private fun openScreen(): ClassReminderSettingsViewModel {
        val viewModel = ClassReminderSettingsViewModel(prefs, sync, SystemPermissions(context))
        shadowOf(Looper.getMainLooper()).idle()
        return viewModel
    }

    private fun runQueue() {
        scheduler.advanceTimeBy(60_000)
        scheduler.runCurrent()
    }

    private fun lastCourses(): JsonObject = writes.last().getAsJsonObject("courses")

    @After
    fun tearDown() {
        queue.cancel()
    }

    @Test
    fun `opening the screen shows what an iPhone last saved`() {
        document = JsonParser.parseString(
            """{"courses": {"enabled": false, "reminder_offsets_minutes": [30]}}"""
        ).asJsonObject

        val state = openScreen().state.value

        assertFalse(state.enabled)
        assertEquals(30, state.leadMinutes)
    }

    @Test
    fun `picking a lead time stores it as picked and sends it to the other devices`() {
        val viewModel = openScreen()

        viewModel.setLeadMinutes(15)
        runQueue()

        assertEquals(15, viewModel.state.value.leadMinutes)
        assertEquals(15, prefs.classReminderLeadMinutes)
        assertTrue(prefs.hasPickedClassReminderLead)
        assertEquals(listOf(15), lastCourses().getAsJsonArray("reminder_offsets_minutes").map { it.asInt })
        assertFalse(prefs.hasUnconfirmedClassReminderSyncEdit)
    }

    @Test
    fun `turning class reminders off stores it and sends it`() {
        val viewModel = openScreen()

        viewModel.setEnabled(false)
        runQueue()

        assertFalse(viewModel.state.value.enabled)
        assertFalse(prefs.classReminderEnabled)
        assertEquals(false, lastCourses().get("enabled").asBoolean)
    }

    @Test
    fun `picking the lead time already chosen changes nothing`() {
        prefs.classReminderLeadMinutes = 10
        val viewModel = openScreen()

        viewModel.setLeadMinutes(10)
        runQueue()

        assertFalse(prefs.hasPickedClassReminderLead)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `a lead time carried over from the Live Update is listed until another is picked`() {
        prefs.classReminderLeadMinutes = 20
        val viewModel = openScreen()
        assertEquals(listOf(5, 10, 15, 20, 30, 60), viewModel.state.value.leadOptions)

        viewModel.setLeadMinutes(30)

        assertEquals(listOf(5, 10, 15, 30, 60), viewModel.state.value.leadOptions)
    }
}
