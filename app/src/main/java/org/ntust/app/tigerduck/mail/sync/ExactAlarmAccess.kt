package org.ntust.app.tigerduck.mail.sync

/** Whether the app may set exact alarms (Android 14+ defaults this to no for new installs). */
fun interface ExactAlarmAccess {
    fun canScheduleExactAlarms(): Boolean
}
