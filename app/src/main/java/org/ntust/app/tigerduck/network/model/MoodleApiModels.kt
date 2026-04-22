package org.ntust.app.tigerduck.network.model

data class MoodleCalendarWrapper(
    val error: Boolean,
    val data: MoodleCalendarData?
)

data class MoodleCalendarData(
    val events: List<MoodleEvent> = emptyList()
)

data class MoodleEvent(
    val id: Int,
    val name: String,
    val description: String?,
    val component: String?,
    val modulename: String?,
    val activityname: String?,
    val instance: Int?,
    val eventtype: String?,
    val timestart: Long,
    val timesort: Long,
    val course: MoodleCourseInfo?,
    val action: MoodleAction?,
    val url: String?
)

data class MoodleCourseInfo(
    val id: Int,
    val fullname: String?,
    val shortname: String?,
    val idnumber: String?
)

data class MoodleAction(
    val name: String?,
    val url: String?,
    val itemcount: Int?,
    val actionable: Boolean?
)

data class MoodleCalendarRequest(
    val index: Int,
    val methodname: String,
    val args: MoodleCalendarArgs
) {
    companion object {
        fun upcoming(fromTimestamp: Long) = MoodleCalendarRequest(
            index = 0,
            methodname = "core_calendar_get_action_events_by_timesort",
            args = MoodleCalendarArgs(
                limitnum = 50,
                timesortfrom = fromTimestamp,
                limittononsuspendedevents = true
            )
        )
    }
}

data class MoodleCalendarArgs(
    val limitnum: Int,
    val timesortfrom: Long,
    val limittononsuspendedevents: Boolean
)

data class MoodleEnrolledCourse(
    val id: Int,
    val fullname: String?,
    val shortname: String?,
    val idnumber: String?,
    val startdate: Long?,
    val enddate: Long?
) {
    /** NTUST course number with 4-digit semester prefix stripped. */
    val courseNo: String
        get() = if (hasSemesterPrefix()) idnumber!!.substring(4) else ""

    /** 4-digit semester code prefix of idnumber, e.g. "1142". */
    val semesterCode: String
        get() = if (hasSemesterPrefix()) idnumber!!.substring(0, 4) else ""

    private fun hasSemesterPrefix(): Boolean {
        val id = idnumber ?: return false
        return id.length > 4 && id.substring(0, 4).all { it.isDigit() }
    }
}

data class MoodleEnrolRequest(
    val index: Int,
    val methodname: String,
    val args: MoodleEnrolArgs
) {
    companion object {
        fun forUser(userId: Int) = MoodleEnrolRequest(
            index = 0,
            methodname = "core_enrol_get_users_courses",
            args = MoodleEnrolArgs(userid = userId)
        )
    }
}

data class MoodleEnrolArgs(val userid: Int)

data class MoodleEnrolWrapper(
    val error: Boolean,
    val data: List<MoodleEnrolledCourse>?
)
