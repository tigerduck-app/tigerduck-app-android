package org.ntust.app.tigerduck.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.ntust.app.tigerduck.data.preferences.AssignmentReminderPrefs
import org.ntust.app.tigerduck.notification.AssignmentReminderOffset
import org.ntust.app.tigerduck.notification.AssignmentReminderOffset.HR24
import org.ntust.app.tigerduck.notification.AssignmentReminderOffset.MIN10

/**
 * Covers [AssignmentReminderSettingsState], AppState's copy of the
 * assignment-reminder settings. [AppState] itself needs a real
 * `Context`-backed Hilt graph, so its copy lives in this class, which takes
 * the store as an interface.
 *
 * The store has a second writer: `NotificationSettingsSync.pullNow()` writes
 * `AppPreferences` directly when it adopts what another device wrote. Each
 * test below plays that pull by writing [FakePrefs] behind the copy's back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssignmentReminderSettingsStateTest {

    /** [AppPreferences]' behavior: stores the value, and signals only on an actual change. */
    private class FakePrefs : AssignmentReminderPrefs {
        private val changes = MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        override val assignmentReminderSettingsChanged: SharedFlow<Unit> = changes

        override var notifyAssignments: Boolean = true
            set(value) {
                val changed = field != value
                field = value
                if (changed) changes.tryEmit(Unit)
            }

        override var notifyAssignmentOffsets: Set<AssignmentReminderOffset> = AssignmentReminderOffset.DEFAULTS
            set(value) {
                val changed = field != value
                field = value
                if (changed) changes.tryEmit(Unit)
            }
    }

    @Test
    fun `a change a pull stores behind the copy shows up in it`() = runTest {
        val prefs = FakePrefs()
        val state = AssignmentReminderSettingsState(prefs, backgroundScope)
        runCurrent()

        prefs.notifyAssignments = false
        prefs.notifyAssignmentOffsets = setOf(HR24)
        runCurrent()

        assertFalse("the switch must show what the store now holds", state.enabled)
        assertEquals(setOf(HR24), state.offsets)
    }

    @Test
    fun `an offset toggle is computed from the stored set, not from the copy`() = runTest {
        val prefs = FakePrefs()
        val state = AssignmentReminderSettingsState(prefs, backgroundScope)
        // A pull has just stored the iPhone's 24 小時-only set, and the copy
        // has not caught up yet.
        prefs.notifyAssignmentOffsets = setOf(HR24)

        state.setOffsetEnabled(MIN10, true)

        assertEquals(
            "turning 10 分鐘 on must add it to what the other device chose, not revert that choice",
            setOf(HR24, MIN10),
            prefs.notifyAssignmentOffsets,
        )
        assertEquals(setOf(HR24, MIN10), state.offsets)
    }
}
