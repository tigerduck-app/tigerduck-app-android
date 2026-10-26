package org.ntust.app.tigerduck.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.network.model.MoodleSubmission
import org.ntust.app.tigerduck.network.model.MoodleSubmissionLastAttempt
import java.util.Date

class MoodleSubmissionStateTest {

    private fun assignment(id: String, completed: Boolean, submittedAt: Date? = null) = Assignment(
        assignmentId = id,
        courseNo = "CS101",
        courseName = "CS",
        title = "HW $id",
        dueDate = Date(0),
        isCompleted = completed,
        submittedAt = submittedAt,
    )

    private fun own(status: String, timemodified: Long) =
        MoodleSubmissionLastAttempt(submission = MoodleSubmission(status, timemodified))

    /** What Moodle answered in [json], as the fetch reads it. */
    private fun stateOf(json: String) =
        MoodleService.submissionState(
            2, confirmed = emptyMap(), MoodleService.decodeSubmissionStatus(json).lastattempt,
        )

    @Test
    fun `only submitted assignments with a time count as confirmed`() {
        val at = Date(5_000)
        val confirmed = MoodleService.confirmedSubmissions(
            listOf(
                assignment("1", completed = true, submittedAt = at),
                assignment("2", completed = false),
                assignment("3", completed = true),
            )
        )
        assertEquals("3 has no time yet, so it is still asked about", mapOf(1 to at), confirmed)
    }

    @Test
    fun `an id that is not a Moodle number is not confirmed`() {
        assertEquals(
            emptyMap<Int, Date>(),
            MoodleService.confirmedSubmissions(
                listOf(assignment("manual-1", completed = true, submittedAt = Date(1))),
            ),
        )
    }

    @Test
    fun `a confirmed submission keeps the time the cache recorded`() {
        val at = Date(5_000)
        assertEquals(
            true to at,
            MoodleService.submissionState(1, confirmed = mapOf(1 to at), lastAttempt = null),
        )
    }

    @Test
    fun `anything else is as Moodle answered`() {
        assertEquals(
            true to Date(7_000),
            MoodleService.submissionState(2, confirmed = emptyMap(), own("submitted", 7)),
        )
        assertEquals(
            false to null,
            MoodleService.submissionState(2, confirmed = emptyMap(), own("draft", 0)),
        )
    }

    @Test
    fun `a status call that failed reads as not submitted`() {
        assertEquals(
            false to null,
            MoodleService.submissionState(2, confirmed = mapOf(1 to Date(1)), lastAttempt = null),
        )
    }

    // --- real-shaped answers ------------------------------------------------

    @Test
    fun `a team assignment a teammate handed in is submitted, at the team's time`() {
        // The student has no submission of their own: only the one who
        // pressed submit gets one, and the group's status is the team's.
        assertEquals(
            true to Date(1_760_400_000_000),
            stateOf(
                """
                {"lastattempt":{
                  "teamsubmission":{"id":48213,"userid":0,"attemptnumber":0,
                    "timecreated":1759980000,"timemodified":1760400000,"status":"submitted",
                    "groupid":3121,"assignment":20977,"latest":1,
                    "plugins":[{"type":"file","name":"File submissions",
                      "fileareas":[{"area":"submission_files","files":[]}]}]},
                  "submissiongroup":3121,"submissiongroupmemberswhoneedtosubmit":[],
                  "submissionsenabled":true,"locked":false,"graded":false,"canedit":true,
                  "caneditowner":true,"cansubmit":false,"extensionduedate":null,"timelimit":0,
                  "blindmarking":false,"gradingstatus":"notgraded","usergroups":[3121]},
                 "assignmentdata":{},"warnings":[]}
                """.trimIndent()
            ),
        )
    }

    @Test
    fun `a team assignment still in draft is not submitted`() {
        assertEquals(
            false to null,
            stateOf(
                """
                {"lastattempt":{
                  "teamsubmission":{"id":48213,"userid":0,"attemptnumber":0,
                    "timecreated":1759980000,"timemodified":1760300000,"status":"draft",
                    "groupid":3121,"assignment":20977,"latest":1,"plugins":[]},
                  "submissiongroup":3121,"submissiongroupmemberswhoneedtosubmit":[],
                  "submissionsenabled":true,"locked":false,"graded":false,"canedit":true,
                  "caneditowner":true,"cansubmit":true,"extensionduedate":null,"timelimit":0,
                  "blindmarking":false,"gradingstatus":"notgraded","usergroups":[3121]},
                 "assignmentdata":{},"warnings":[]}
                """.trimIndent()
            ),
        )
    }

    @Test
    fun `the student's own submission counts while the team waits for the others`() {
        // A team that needs every member to submit stays in draft until then.
        assertEquals(
            true to Date(1_760_200_000_000),
            stateOf(
                """
                {"lastattempt":{
                  "submission":{"id":48230,"userid":16045,"attemptnumber":0,
                    "timecreated":1760100000,"timemodified":1760200000,"status":"submitted",
                    "groupid":0,"assignment":20977,"latest":1,"plugins":[]},
                  "teamsubmission":{"id":48213,"userid":0,"attemptnumber":0,
                    "timecreated":1759980000,"timemodified":1760200000,"status":"draft",
                    "groupid":3121,"assignment":20977,"latest":1,"plugins":[]},
                  "submissiongroup":3121,"submissiongroupmemberswhoneedtosubmit":[16046],
                  "submissionsenabled":true,"locked":false,"graded":false,"canedit":true,
                  "caneditowner":true,"cansubmit":false,"extensionduedate":null,"timelimit":0,
                  "blindmarking":false,"gradingstatus":"notgraded","usergroups":[3121]},
                 "assignmentdata":{},"warnings":[]}
                """.trimIndent()
            ),
        )
    }

    @Test
    fun `a new individual submission is not submitted`() {
        assertEquals(
            false,
            stateOf(
                """
                {"lastattempt":{
                  "submission":{"id":48230,"userid":16045,"attemptnumber":0,
                    "timecreated":1760000000,"timemodified":1760000000,"status":"new",
                    "groupid":0,"assignment":20977,"latest":1,"plugins":[]},
                  "submissiongroupmemberswhoneedtosubmit":[],
                  "submissionsenabled":true,"locked":false,"graded":false,"canedit":true,
                  "caneditowner":true,"cansubmit":true,"extensionduedate":null,"timelimit":0,
                  "blindmarking":false,"gradingstatus":"notgraded","usergroups":[]},
                 "assignmentdata":{},"warnings":[]}
                """.trimIndent()
            ).first,
        )
    }

    @Test
    fun `a rejected token throws`() {
        assertThrows(MoodleWebserviceError.InvalidToken::class.java) {
            MoodleService.decodeSubmissionStatus(
                """{"exception":"moodle_exception","errorcode":"invalidtoken",""" +
                    """"message":"Invalid token - token not found"}"""
            )
        }
    }

    @Test
    fun `the extension comes from the status this round asked, and from the cache when it did not ask`() {
        val cached = mapOf("1" to Date(1_000_000L))
        fun attempt(extensionSeconds: Long?) = MoodleSubmissionLastAttempt(extensionduedate = extensionSeconds)

        assertEquals(Date(2_000_000L), MoodleService.extensionDueDate(1, asked = true, attempt(2_000), cached))
        assertEquals("asked, and none", null, MoodleService.extensionDueDate(1, asked = true, attempt(0), cached))
        assertEquals("asked, and none", null, MoodleService.extensionDueDate(1, asked = true, attempt(null), cached))
        assertEquals("not asked this round", Date(1_000_000L), MoodleService.extensionDueDate(1, asked = false, null, cached))
        assertEquals(null, MoodleService.extensionDueDate(2, asked = false, null, cached))
    }
}
