package org.ntust.app.tigerduck.network.model

import org.ntust.app.tigerduck.network.MoodleCourseIds

data class MoodleEnrolledCourse(
    val id: Int,
    val fullname: String?,
    val shortname: String?,
    val idnumber: String?,
    val startdate: Long?,
    val enddate: Long?
) {
    // Computed properties only: this class is Gson-decoded, and a backing
    // field added here would come back null from Gson's Unsafe path.

    /**
     * NTUST course number with the semester prefix stripped, e.g.
     * "1142PE139B022" → "PE139B022". Empty when idnumber carries no
     * recognisable prefix — see [MoodleCourseIds.semesterPrefix].
     */
    val courseNo: String
        get() = if (semesterCode.isNotEmpty()) idnumber!!.substring(4) else ""

    /**
     * Semester code from the idnumber prefix, spelled the way NTUST's own
     * catalogue spells it: "1142PE139B022" → "1142", and a summer term's
     * "114hGD3115301" → "114H". Empty when there is no prefix.
     */
    val semesterCode: String
        get() = idnumber?.let(MoodleCourseIds::semesterPrefix) ?: ""

    /**
     * Every NTUST course number this Moodle course answers to: [courseNo]
     * first, then any co-listed (合開) code found in [fullname]. Empty when
     * [courseNo] is.
     */
    val courseNos: List<String>
        get() = MoodleCourseIds.courseNos(this)

    /**
     * [courseNos] with the term prefix put back, spelled exactly as Moodle
     * wrote it, so each can be looked up the way [idnumber] itself is. Index 0
     * is always [idnumber].
     */
    val idnumbers: List<String>
        get() {
            val prefix = if (semesterCode.isEmpty()) "" else idnumber!!.take(4)
            return courseNos.map { prefix + it }
        }
}

// mod_assign_get_assignments

data class MoodleAssignmentsEnvelope(
    val courses: List<MoodleAssignmentsCourse> = emptyList()
)

data class MoodleAssignmentsCourse(
    val id: Int,
    val assignments: List<MoodleAssignmentNode> = emptyList()
)

data class MoodleAssignmentNode(
    val id: Int,
    val cmid: Int,
    val name: String,
    val duedate: Long = 0,
    val cutoffdate: Long? = null,
    val allowsubmissionsfromdate: Long? = null,
    val intro: String? = null,
    val nosubmissions: Int = 0
)

// mod_assign_get_submission_status

data class MoodleSubmissionStatusEnvelope(
    val lastattempt: MoodleSubmissionLastAttempt? = null
)

data class MoodleSubmissionLastAttempt(
    val submission: MoodleSubmission? = null,
    val gradingstatus: String? = null
)

data class MoodleSubmission(
    val status: String? = null,
    val timemodified: Long? = null
)
