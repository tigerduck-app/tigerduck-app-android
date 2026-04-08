package org.ntust.app.tigerduck.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.net.URLEncoder
import java.util.Date

@Entity(
    tableName = "assignments",
    indices = [Index("dueDate"), Index("courseNo")]
)
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
        get() = moodleUrl?.let {
            val path = it.substringAfter("moodle2.ntust.edu.tw")
            "moodlemobile://https://moodle2.ntust.edu.tw?redirect=${URLEncoder.encode(path, "UTF-8")}"
        }
}
