package org.ntust.app.tigerduck.data.model

import java.util.Date

data class Assignment(
    val assignmentId: String,
    val courseNo: String,
    val courseName: String,
    val title: String,
    val dueDate: Date,
    val isCompleted: Boolean = false,
    val moodleUrl: String? = null,
    /** Final cutoff after which Moodle rejects further submissions. Null when
     *  Moodle reports cutoffdate=0 (late submissions accepted indefinitely). */
    val cutoffDate: Date? = null,
    /** Moodle `submission.timemodified` — used to distinguish on-time vs late
     *  submissions once [isCompleted] is true. */
    val submittedAt: Date? = null,
) {
    val isOverdue: Boolean
        get() = !isCompleted && dueDate.before(Date())

    val moodleDeepLink: String
        get() = moodleUrl?.let {
            val path = it.substringAfter("moodle2.ntust.edu.tw")
            "moodlemobile://https://moodle2.ntust.edu.tw?redirect=$path"
        }
            ?: "moodlemobile://https://moodle2.ntust.edu.tw?redirect=/mod/assign/view.php?id=$assignmentId"
}
