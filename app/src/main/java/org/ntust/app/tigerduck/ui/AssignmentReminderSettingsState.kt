package org.ntust.app.tigerduck.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.data.preferences.AssignmentReminderPrefs
import org.ntust.app.tigerduck.notification.AssignmentReminderOffset

/**
 * The assignment-reminder settings as the UI shows them: a Compose-observable
 * copy of what [prefs] holds.
 *
 * [prefs] is the record, and it has more than one writer. This device's own
 * edits come through here, but a pull of the shared `notification` document
 * (`NotificationSettingsSync.pullNow`) writes [prefs] directly, whichever
 * screen or sync started it. So the copy re-reads [prefs] every time [prefs]
 * reports a change, and an edit is computed from [prefs], never from the
 * copy: a copy even a moment behind would write the values from before
 * another device's change back over it.
 */
internal class AssignmentReminderSettingsState(
    private val prefs: AssignmentReminderPrefs,
    scope: CoroutineScope,
) {
    private var enabledState by mutableStateOf(prefs.notifyAssignments)
    private var offsetsState by mutableStateOf(prefs.notifyAssignmentOffsets)

    init {
        // The extra re-read once the collector is listening covers a change
        // stored between construction and subscription, which the signal
        // alone would miss.
        scope.launch {
            prefs.assignmentReminderSettingsChanged
                .onSubscription { emit(Unit) }
                .collect { reload() }
        }
    }

    var enabled: Boolean
        get() = enabledState
        set(value) {
            enabledState = value
            prefs.notifyAssignments = value
        }

    val offsets: Set<AssignmentReminderOffset> get() = offsetsState

    /** Turns [offset] on or off in the stored set of reminder offsets. */
    fun setOffsetEnabled(offset: AssignmentReminderOffset, enabled: Boolean) {
        val stored = prefs.notifyAssignmentOffsets
        val next = if (enabled) stored + offset else stored - offset
        offsetsState = next
        prefs.notifyAssignmentOffsets = next
    }

    /** Re-reads both settings from [prefs]. */
    fun reload() {
        enabledState = prefs.notifyAssignments
        offsetsState = prefs.notifyAssignmentOffsets
    }
}
