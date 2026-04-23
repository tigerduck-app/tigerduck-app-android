package org.ntust.app.tigerduck.network.model

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
