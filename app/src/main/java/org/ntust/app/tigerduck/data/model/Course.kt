package org.ntust.app.tigerduck.data.model

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

@Entity(tableName = "courses")
data class Course(
    @PrimaryKey val courseNo: String,
    val courseName: String,
    val instructor: String = "",
    val credits: Int = 0,
    val classroom: String = "",
    val enrolledCount: Int = 0,
    val maxCount: Int = 0,
    /** JSON: {"1":["3","4"],"3":["6","7"]} — keys = weekday (1=Mon..7=Sun) */
    val scheduleJson: String = "{}",
    val moodleIdNumber: String? = null
) {
    @Ignore
    @Transient
    private var _cachedSchedule: Map<Int, List<String>>? = null

    val schedule: Map<Int, List<String>>
        get() {
            _cachedSchedule?.let { return it }
            val type = object : TypeToken<Map<String, List<String>>>() {}.type
            val raw: Map<String, List<String>> = try {
                scheduleGson.fromJson(scheduleJson, type) ?: emptyMap()
            } catch (e: Exception) {
                emptyMap()
            }
            val parsed = raw.mapKeys { it.key.toIntOrNull() ?: 0 }
                .filterKeys { it != 0 }
            _cachedSchedule = parsed
            return parsed
        }

    companion object {
        private val scheduleGson = Gson()

        fun fromSchedule(
            courseNo: String,
            courseName: String,
            instructor: String = "",
            credits: Int = 0,
            classroom: String = "",
            enrolledCount: Int = 0,
            maxCount: Int = 0,
            schedule: Map<Int, List<String>> = emptyMap(),
            moodleIdNumber: String? = null
        ): Course {
            val stringKeyMap = schedule.mapKeys { it.key.toString() }
            val json = scheduleGson.toJson(stringKeyMap)
            return Course(
                courseNo = courseNo,
                courseName = courseName,
                instructor = instructor,
                credits = credits,
                classroom = classroom,
                enrolledCount = enrolledCount,
                maxCount = maxCount,
                scheduleJson = json,
                moodleIdNumber = moodleIdNumber
            )
        }

        fun courseNoFromMoodleId(moodleId: String): String =
            if (moodleId.length > 4) moodleId.drop(4) else moodleId
    }
}
