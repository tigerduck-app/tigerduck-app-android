package com.tigerduck.app.network.model

data class MoodleCalendarWrapper(
    val error: Boolean,
    val data: MoodleCalendarData?
)

data class MoodleCalendarData(
    val events: List<MoodleEvent>
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
    val timestart: Int,
    val timesort: Int,
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
        fun upcoming(fromTimestamp: Int) = MoodleCalendarRequest(
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
    val timesortfrom: Int,
    val limittononsuspendedevents: Boolean
)
