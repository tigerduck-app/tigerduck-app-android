package org.ntust.app.tigerduck.network.moodle

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.network.model.moodle.MoodleAssignmentGrade

/**
 * What an assignment's page reads from Moodle: the submission, its comments and the group's
 * name. The answers follow the shapes Moodle 4.5 documents for these functions, and every name in
 * them is made up.
 */
class MoodleSubmissionJsonTest {

    private fun json(text: String) = JsonParser.parseString(text)

    private fun file(name: String, area: String, item: Int, size: Int, type: String, time: Long) =
        """{"filename": "$name", "filepath": "/", "filesize": $size,
            "fileurl": "https://moodle2.ntust.edu.tw/webservice/pluginfile.php/123/$area/$item/$name",
            "timemodified": $time, "mimetype": "$type", "isexternalfile": false}"""

    @Test
    fun `an individual submission brings its files, its online text, its time and that it takes comments`() {
        val submission = MoodleJson.submission(
            json(
                """
                {"lastattempt": {
                  "submission": {"id": 5501, "userid": 42, "attemptnumber": 0, "timecreated": 1790400000,
                    "timemodified": 1790500000, "timestarted": null, "status": "submitted", "groupid": 0,
                    "assignment": 701, "latest": 1, "plugins": [
                      {"type": "file", "name": "File submissions", "fileareas": [{"area": "submission_files", "files": [
                        ${file("hw2.pdf", "assignsubmission_file/submission_files", 5501, 870400, "application/pdf", 1790500000)}]}]},
                      {"type": "comments", "name": "Submission comments"},
                      {"type": "onlinetext", "name": "Online text",
                       "fileareas": [{"area": "submissions_onlinetext", "files": [
                         ${file("chart.png", "assignsubmission_onlinetext/submissions_onlinetext", 5501, 4096, "image/png", 1790500000)}]}],
                       "editorfields": [{"name": "onlinetext", "description": "Online text submissions",
                         "text": "<p>See the attached report.</p>", "format": 1}]}
                    ]},
                  "submissiongroupmemberswhoneedtosubmit": [], "submissionsenabled": true, "locked": false, "graded": false,
                  "canedit": true, "caneditowner": true, "cansubmit": false, "extensionduedate": null, "timelimit": 0,
                  "blindmarking": false, "gradingstatus": "notgraded", "usergroups": []},
                 "warnings": []}
                """,
            ),
        )

        assertTrue(submission.isSubmitted)
        assertEquals(1_790_500_000L, submission.submittedAt)
        assertEquals(listOf("hw2.pdf"), submission.files.map { it.name })
        assertEquals(
            "https://moodle2.ntust.edu.tw/webservice/pluginfile.php/123/assignsubmission_file/submission_files/5501/hw2.pdf",
            submission.files.single().url,
        )
        assertEquals("<p>See the attached report.</p>", submission.onlineText)
        assertEquals(5501, submission.id)
        assertTrue(submission.takesComments)
        assertNull(submission.groupId)
        assertNull("the group's name and the comments come from other calls", submission.comments)
        assertNull("not graded yet", submission.grade)
    }

    @Test
    fun `a graded submission brings the grade as Moodle writes it for the student, when it was graded and the feedback`() {
        val submission = MoodleJson.submission(
            json(
                """
                {"lastattempt": {"submission": {"id": 5501, "timemodified": 1790500000, "status": "submitted", "plugins": []},
                  "graded": true, "gradingstatus": "graded"},
                 "feedback": {
                   "grade": {"id": 801, "assignment": 701, "userid": 42, "attemptnumber": 0, "timecreated": 1790600000,
                     "timemodified": 1790700000, "grader": 7, "grade": "85.00000"},
                   "gradefordisplay": "85.00&nbsp;/&nbsp;100.00", "gradeddate": 1790700000,
                   "plugins": [
                     {"type": "comments", "name": "Feedback comments",
                      "fileareas": [{"area": "feedback", "files": [
                        ${file("case7.png", "assignfeedback_comments/feedback", 801, 2048, "image/png", 1790700000)}]}],
                      "editorfields": [{"name": "comments", "description": "Feedback comments", "format": 1,
                        "text": "<p>Case 7 misses the empty tree.</p><p><img src=\"@@PLUGINFILE@@/case7.png\" alt=\"case 7\"> <img src=\"@@PLUGINFILE@@/gone.png\" alt=\"gone\"></p>"}]},
                     {"type": "file", "name": "File feedback", "fileareas": [{"area": "feedback_files", "files": []}]}
                   ]},
                 "warnings": []}
                """,
            ),
        )

        val grade = submission.grade!!
        assertEquals("85.00 / 100.00", grade.grade)
        assertEquals(1_790_700_000L, grade.gradedAt)
        val feedback = grade.feedback!!
        assertTrue(feedback.contains("Case 7 misses the empty tree."))
        assertTrue(feedback.contains("assignfeedback_comments/feedback/801/case7.png"))
        assertFalse(feedback.contains("@@PLUGINFILE@@"))
        assertFalse(feedback.contains("gone"))
    }

    @Test
    fun `feedback without a grade keeps the feedback, and a grade of - with empty feedback is no grade`() {
        val feedbackOnly = MoodleJson.submission(
            json(
                """
                {"lastattempt": {"submission": {"id": 5501, "timemodified": 1790500000, "status": "submitted", "plugins": []}},
                 "feedback": {"gradefordisplay": null, "gradeddate": null, "plugins": [
                   {"type": "comments", "editorfields": [{"name": "comments", "text": "<p>See me after class.</p>", "format": 1}]}]}}
                """,
            ),
        )
        assertEquals(MoodleAssignmentGrade(grade = null, gradedAt = null, feedback = "<p>See me after class.</p>"), feedbackOnly.grade)

        val nothing = MoodleJson.submission(
            json(
                """
                {"lastattempt": {"submission": {"id": 5501, "timemodified": 1790500000, "status": "submitted", "plugins": []}},
                 "feedback": {"gradefordisplay": "-", "gradeddate": 0, "plugins": [
                   {"type": "comments", "editorfields": [{"name": "comments", "text": "<p></p>", "format": 1}]}]}}
                """,
            ),
        )
        assertNull(nothing.grade)
    }

    @Test
    fun `a team assignment shows what the group handed in, with the group it is for`() {
        val submission = MoodleJson.submission(
            json(
                """
                {"lastattempt": {
                  "submission": {"id": 5601, "userid": 42, "timemodified": 1790300000, "status": "new", "groupid": 0,
                    "assignment": 702, "latest": 1, "plugins": [
                      {"type": "file", "fileareas": [{"area": "submission_files", "files": []}]},
                      {"type": "comments", "name": "Submission comments"}]},
                  "teamsubmission": {"id": 5600, "userid": 0, "timemodified": 1790900000, "status": "submitted",
                    "groupid": 31, "assignment": 702, "latest": 1, "plugins": [
                      {"type": "file", "fileareas": [{"area": "submission_files", "files": [
                        ${file("project.zip", "assignsubmission_file/submission_files", 5600, 2048, "application/zip", 1790900000)}]}]},
                      {"type": "comments", "name": "Submission comments"}]},
                  "submissiongroup": 31, "submissionsenabled": true, "gradingstatus": "notgraded", "usergroups": [31]},
                 "warnings": []}
                """,
            ),
        )

        assertEquals(5600, submission.id)
        assertEquals(listOf("project.zip"), submission.files.map { it.name })
        assertEquals(1_790_900_000L, submission.submittedAt)
        assertEquals(31, submission.groupId)
        assertTrue(submission.takesComments)
    }

    @Test
    fun `where every member must submit, the page follows the student's own record, as the round does`() {
        val submission = MoodleJson.submission(
            json(
                """
                {"lastattempt": {
                  "submission": {"id": 5601, "timemodified": 1790800000, "status": "submitted", "plugins": [
                    {"type": "comments", "name": "Submission comments"}]},
                  "teamsubmission": {"id": 5600, "timemodified": 1790700000, "status": "draft", "plugins": [
                    {"type": "file", "fileareas": [{"area": "submission_files", "files": [
                      ${file("project.zip", "assignsubmission_file/submission_files", 5600, 2048, "application/zip", 1790700000)}]}]}]},
                  "submissiongroup": 31, "submissiongroupmemberswhoneedtosubmit": [43]},
                 "warnings": []}
                """,
            ),
        )

        assertEquals(5601, submission.id)
        assertEquals(1_790_800_000L, submission.submittedAt)
        assertTrue(submission.takesComments)
        // What the group handed in is on its record still, while it waits for the others.
        assertEquals(listOf("project.zip"), submission.files.map { it.name })
    }

    @Test
    fun `online text points its images and links at its own files, and drops an image with no file`() {
        val submission = MoodleJson.submission(
            json(
                """
                {"lastattempt": {"submission": {"id": 5501, "timemodified": 1790500000, "status": "submitted", "plugins": [
                  {"type": "onlinetext", "fileareas": [{"area": "submissions_onlinetext", "files": [
                    ${file("chart.png", "assignsubmission_onlinetext/submissions_onlinetext", 5501, 4096, "image/png", 1790500000)},
                    {"filename": "notes v2.pdf", "filepath": "/", "filesize": 100, "mimetype": "application/pdf",
                     "fileurl": "https://moodle2.ntust.edu.tw/webservice/pluginfile.php/123/assignsubmission_onlinetext/submissions_onlinetext/5501/notes%20v2.pdf",
                     "timemodified": 1790500000}]}],
                   "editorfields": [{"name": "onlinetext", "format": 1, "text":
                     "<p>Chart: <img src=\"@@PLUGINFILE@@/chart.png\" alt=\"chart\"> <img src=\"@@PLUGINFILE@@/gone.png\" alt=\"gone\"></p><p><a href=\"@@PLUGINFILE@@/notes%20v2.pdf\">Notes</a> <a href=\"@@PLUGINFILE@@/old.pdf\">Old notes</a></p>"}]}
                ]}}}
                """,
            ),
        )

        val text = submission.onlineText
        assertNotNull(text)
        assertFalse(text!!.contains("@@PLUGINFILE@@"))
        assertTrue(text.contains("submissions_onlinetext/5501/chart.png"))
        assertTrue(text.contains("submissions_onlinetext/5501/notes%20v2.pdf"))
        assertFalse(text.contains("gone"))
        assertTrue(text.contains("Old notes"))
    }

    @Test
    fun `with no record on Moodle yet, nothing is handed in and there is nothing to comment on`() {
        val submission = MoodleJson.submission(
            json(
                """
                {"lastattempt": {"submissionsenabled": true, "locked": false, "graded": false, "canedit": true,
                  "cansubmit": false, "gradingstatus": "notgraded", "usergroups": []}, "warnings": []}
                """,
            ),
        )

        assertNull(submission.status)
        assertFalse(submission.isSubmitted)
        assertTrue(submission.files.isEmpty())
        assertNull(submission.onlineText)
        assertNull(submission.id)
        assertFalse(submission.takesComments)
    }

    @Test
    fun `a draft keeps its online text but has no submission time, and empty online text counts as none`() {
        val draft = MoodleJson.submission(
            json(
                """
                {"lastattempt": {"submission": {"id": 5701, "timemodified": 1790400000, "status": "draft", "plugins": [
                  {"type": "onlinetext", "editorfields": [{"name": "onlinetext", "text": "<p>Half done</p>", "format": 1}]}
                ]}}}
                """,
            ),
        )
        assertEquals("<p>Half done</p>", draft.onlineText)
        assertEquals(1_790_400_000L, draft.modified)
        assertNull(draft.submittedAt)

        val empty = MoodleJson.submission(
            json(
                """
                {"lastattempt": {"submission": {"id": 5702, "timemodified": 1790400000, "status": "submitted", "plugins": [
                  {"type": "onlinetext", "editorfields": [{"name": "onlinetext", "text": "<p></p>", "format": 1}]}
                ]}}}
                """,
            ),
        )
        assertNull(empty.onlineText)

        val imageOnly = MoodleJson.submission(
            json(
                """
                {"lastattempt": {"submission": {"id": 5703, "timemodified": 1790400000, "status": "submitted", "plugins": [
                  {"type": "onlinetext", "fileareas": [{"area": "submissions_onlinetext", "files": [
                    ${file("chart.png", "assignsubmission_onlinetext/submissions_onlinetext", 5703, 4096, "image/png", 1790400000)}]}],
                   "editorfields": [{"name": "onlinetext", "text": "<p><img src=\"@@PLUGINFILE@@/chart.png\"></p>", "format": 1}]}
                ]}}}
                """,
            ),
        )
        assertNotNull(imageOnly.onlineText)
        assertFalse("a text that is one image still points it at its file (iOS #728)", imageOnly.onlineText!!.contains("@@PLUGINFILE@@"))
        assertTrue(imageOnly.onlineText.contains("submissions_onlinetext/5703/chart.png"))
    }

    @Test
    fun `a page of comments keeps each one's author, time and text, with the submission's total`() {
        val page = MoodleJson.comments(
            json(
                """
                {"comments": [
                  {"id": 902, "content": "<div class=\"text_to_html\">Thanks, page 2 is the corrected one.</div>", "format": 1,
                   "timecreated": 1790510000, "strftimeformat": "%A, %d %B %Y, %I:%M %p",
                   "profileurl": "https://moodle2.ntust.edu.tw/user/view.php?id=42&course=20119",
                   "fullname": "B11399001 @ Tiger Duck", "time": "Friday, 18 October 2026, 9:05 PM",
                   "avatar": "https://moodle2.ntust.edu.tw/theme/image.php/moove/core/1/u/f2", "userid": 42, "delete": true},
                  {"id": 901, "content": "<div class=\"text_to_html\">Please check page 2.</div>", "format": 1,
                   "timecreated": 1790505000, "fullname": "T00001 @ Duck Teacher", "userid": 7, "delete": false},
                  {"content": "no id"}
                ], "count": 2, "perpage": 15, "canpost": true, "warnings": []}
                """,
            ),
        )

        assertEquals(2, page.count)
        assertEquals(listOf(902, 901), page.comments.map { it.id })
        assertEquals("T00001 @ Duck Teacher", page.comments[1].authorName)
        assertEquals(1_790_505_000L, page.comments[1].created)
        assertEquals("<div class=\"text_to_html\">Please check page 2.</div>", page.comments[1].content)
    }

    @Test
    fun `a page without a total reads as none`() {
        val page = MoodleJson.comments(json("""{"comments": [], "warnings": []}"""))
        assertNull(page.count)
        assertTrue(page.comments.isEmpty())
    }

    @Test
    fun `the account's groups on a course come by id, with their names as text`() {
        val groups = MoodleJson.groups(
            json(
                """
                {"groups": [
                  {"id": 31, "name": "Group 3 &amp; friends", "description": "", "descriptionformat": 1, "idnumber": "",
                   "courseid": 20119},
                  {"id": 32, "name": "Lab B", "description": "<p>Thursday</p>", "descriptionformat": 1, "idnumber": "LB",
                   "courseid": 20119},
                  {"name": "no id"}
                ], "warnings": []}
                """,
            ),
        )

        assertEquals(mapOf(31 to "Group 3 & friends", 32 to "Lab B"), groups)
    }

    @Test
    fun `an extension granted to the student comes with the submission, and none reads as none`() {
        val extended = MoodleJson.submission(json("""{"lastattempt":{"extensionduedate":1790900000,"submission":{"status":"new"}}}"""))
        assertEquals(1_790_900_000L, extended.extensionDue)
        assertNull(MoodleJson.submission(json("""{"lastattempt":{"extensionduedate":0,"submission":{"status":"new"}}}""")).extensionDue)
    }
}
