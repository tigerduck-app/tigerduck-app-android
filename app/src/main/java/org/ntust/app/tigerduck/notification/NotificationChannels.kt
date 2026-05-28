package org.ntust.app.tigerduck.notification

object NotificationChannels {
    const val ASSIGNMENT_DUE = "assignment_due"
    const val BULLETINS = "bulletins"
    /** High-importance bulletin channel: heads-up banner + default sound. */
    const val BULLETINS_SOUND = "bulletins_sound"
    /** Default-importance bulletin channel: shows banner but silent. */
    const val BULLETINS_SILENT = "bulletins_silent"
}
