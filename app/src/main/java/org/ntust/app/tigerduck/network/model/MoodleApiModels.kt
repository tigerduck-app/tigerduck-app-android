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
    // Null rather than empty when the key is missing: only a body that is
    // not this function's answer lacks it, and an empty array is an answer.
    val courses: List<MoodleAssignmentsCourse>? = null,
    val warnings: List<MoodleWarning>? = null,
)

/** One of the warnings a Moodle webservice answers with beside its data. */
data class MoodleWarning(
    /** What it is about: "course" or "module" for mod_assign_get_assignments. */
    val item: String? = null,
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
    val nosubmissions: Int = 0,
    val introattachments: List<MoodleAssignmentFileNode>? = null,
    /** 1 for a team assignment, where a group hands in one submission. */
    val teamsubmission: Int = 0,
)

/** A file in a `mod_assign_get_assignments` answer, as Moodle's `external_files` lists it. */
data class MoodleAssignmentFileNode(
    val filename: String? = null,
    val filepath: String? = null,
    val filesize: Long? = null,
    val fileurl: String? = null,
    val mimetype: String? = null,
    val timemodified: Long? = null,
)

// mod_assign_get_submission_status

data class MoodleSubmissionStatusEnvelope(
    val lastattempt: MoodleSubmissionLastAttempt? = null
)

data class MoodleSubmissionLastAttempt(
    val submission: MoodleSubmission? = null,
    /** A team assignment's own submission, the one a teammate hands in. */
    val teamsubmission: MoodleSubmission? = null,
    /** False when the assignment takes no submissions. */
    val submissionsenabled: Boolean? = null,
    /** True when the student's submissions are locked. */
    val locked: Boolean? = null,
    /** The due date of the student's extension, epoch seconds; null or 0 without one. */
    val extensionduedate: Long? = null,
    /**
     * Whether the student can still add to or change their submission, as
     * Moodle works it out for them: their own cutoff, override and extension
     * included. Null from a Moodle that does not say.
     */
    val canedit: Boolean? = null,
    /** Whether the student can still submit a draft for grading. Null when not said. */
    val cansubmit: Boolean? = null,
    val gradingstatus: String? = null
) {
    // Computed only, like MoodleEnrolledCourse's: Gson leaves a backing field null.

    /**
     * The submission Moodle records as handed in, the student's own or their
     * team's, or null when neither is. A teammate's submission shows only in
     * [teamsubmission]; the student's own [submission] may not exist at all.
     */
    val handedIn: MoodleSubmission?
        get() = listOfNotNull(submission, teamsubmission).firstOrNull { it.status == "submitted" }
}

data class MoodleSubmission(
    val status: String? = null,
    val timemodified: Long? = null
)
