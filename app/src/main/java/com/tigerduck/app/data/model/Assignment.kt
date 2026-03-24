package com.tigerduck.app.data.model

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
        get() = "moodlemobile://https://moodle2.ntust.edu.tw?redirect=/mod/assign/view.php?id=$assignmentId"
}
