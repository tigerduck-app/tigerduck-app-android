package org.ntust.app.tigerduck.ui.screen.coursemoodle

/**
 * The course detail, the course sheet's one page (see CourseDetailSheet), and the Moodle screens it
 * opens, full-screen pages of the app's navigation over the page the sheet is on (coursePages).
 */
object MoodleRoutes {
    /** The course detail of the class table's selected course, the sheet's page. */
    const val COURSE_DETAIL = "classTable/course"

    const val ANNOUNCEMENTS = "courseMoodle/{courseId}/announcements"
    const val ANNOUNCEMENT = "courseMoodle/{courseId}/announcements/{discussionId}"
    const val ASSIGNMENTS = "courseMoodle/{courseId}/assignments"

    /** An assignment's page opened from the sheet; `courseId` is 0 for one whose course the cache does not name. */
    const val ASSIGNMENT = "courseMoodle/{courseId}/assignments/{assignmentId}"
    const val MATERIALS = "courseMoodle/{courseId}/materials"
    const val FORUMS = "courseMoodle/{courseId}/forums"
    const val FORUM = "courseMoodle/{courseId}/forums/{forumId}"
    const val DISCUSSION = "courseMoodle/{courseId}/forums/{forumId}/discussions/{discussionId}"
    const val GRADES = "courseMoodle/{courseId}/grades"
    const val MEMBERS = "courseMoodle/{courseId}/members"
    const val MEMBER = "courseMoodle/{courseId}/members/{memberId}"

    const val ARG_DISCUSSION_ID = "discussionId"
    const val ARG_ASSIGNMENT_ID = "assignmentId"
    const val ARG_FORUM_ID = "forumId"
    const val ARG_MEMBER_ID = "memberId"

    fun announcements(courseId: Int) = "courseMoodle/$courseId/announcements"
    fun announcement(courseId: Int, discussionId: Int) = "courseMoodle/$courseId/announcements/$discussionId"
    fun assignments(courseId: Int) = "courseMoodle/$courseId/assignments"
    fun assignment(courseId: Int, assignmentId: String) = "courseMoodle/$courseId/assignments/$assignmentId"
    fun materials(courseId: Int) = "courseMoodle/$courseId/materials"
    fun forums(courseId: Int) = "courseMoodle/$courseId/forums"
    fun forum(courseId: Int, forumId: Int) = "courseMoodle/$courseId/forums/$forumId"
    fun discussion(courseId: Int, forumId: Int, discussionId: Int) =
        "courseMoodle/$courseId/forums/$forumId/discussions/$discussionId"
    fun grades(courseId: Int) = "courseMoodle/$courseId/grades"
    fun members(courseId: Int) = "courseMoodle/$courseId/members"
    fun member(courseId: Int, memberId: Int) = "courseMoodle/$courseId/members/$memberId"
}
