package org.ntust.app.tigerduck.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "assignments")
data class Assignment(
    @PrimaryKey val assignmentId: String,
    val courseNo: String,
    val courseName: String,
    val title: String,
    val dueDate: Date,
    val isCompleted: Boolean = false,
    val moodleUrl: String? = null
) {
    val isOverdue: Boolean
        get() = !isCompleted && dueDate.before(Date())

    val moodleDeepLink: String?
        get() = moodleUrl?.let { "moodlemobile://https://moodle2.ntust.edu.tw?redirect=${it.substringAfter("moodle2.ntust.edu.tw")}" }
            ?: "moodlemobile://https://moodle2.ntust.edu.tw?redirect=/mod/assign/view.php?id=$assignmentId"
}
