package org.ntust.app.tigerduck.emptyclassroom

import org.ntust.app.tigerduck.data.preferences.AppPreferences

/**
 * What the empty-classroom finder holds for an account, dropped when it signs out: the days read
 * in its session, which also ends any read still running for it, and the building it last
 * picked, so the next account opens on its own.
 */
fun forgetEmptyClassroom(classroomUsage: ClassroomUsageService, prefs: AppPreferences) {
    classroomUsage.forget()
    prefs.emptyClassroomBuilding = null
}
