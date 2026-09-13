package org.ntust.app.tigerduck.data.preferences

import kotlinx.coroutines.flow.SharedFlow
import org.ntust.app.tigerduck.notification.AssignmentReminderOffset

/**
 * The assignment-reminder settings as [AppPreferences] stores them, and the
 * signal it sends when either one changes. An interface so the UI's copy of
 * them ([org.ntust.app.tigerduck.ui.AssignmentReminderSettingsState]) can be
 * tested on the plain JVM, the way [FirstTriggerSeenStore] lets the
 * first-trigger prompt be.
 */
interface AssignmentReminderPrefs {
    var notifyAssignments: Boolean
    var notifyAssignmentOffsets: Set<AssignmentReminderOffset>

    /** Emits after either setting changes, whoever changed it. */
    val assignmentReminderSettingsChanged: SharedFlow<Unit>
}
