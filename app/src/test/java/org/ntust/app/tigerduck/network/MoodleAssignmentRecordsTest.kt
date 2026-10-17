package org.ntust.app.tigerduck.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.network.model.moodle.MoodleFile

/**
 * What a round keeps of `mod_assign_get_assignments` for the assignments' pages: every assignment
 * of the answer, with its description, attachments and whether a group submits together. The
 * answer follows the shape Moodle 4.5 documents; every name in it is made up.
 */
class MoodleAssignmentRecordsTest {

    private val body = """
        {"courses": [{"id": 20119, "fullname": "Data Structures", "shortname": "DS", "timemodified": 1790000000,
          "assignments": [
            {"id": 701, "cmid": 9101, "course": 20119, "name": "HW 2: Linked lists &amp; more", "nosubmissions": 0,
             "submissiondrafts": 0, "sendnotifications": 0, "sendlatenotifications": 0, "duedate": 1790600000,
             "allowsubmissionsfromdate": 1790000000, "grade": 100, "timemodified": 1790000000, "cutoffdate": 0,
             "teamsubmission": 0, "requireallteammemberssubmit": 0, "maxattempts": -1, "configs": [],
             "intro": "<p>Implement a <strong>singly linked list</strong>.</p>", "introformat": 1, "introfiles": [],
             "introattachments": [{"filename": "spec.pdf", "filepath": "/", "filesize": 1258291,
               "fileurl": "https://moodle2.ntust.edu.tw/webservice/pluginfile.php/123/mod_assign/introattachment/0/spec.pdf",
               "timemodified": 1790000000, "mimetype": "application/pdf", "isexternalfile": false}]},
            {"id": 702, "cmid": 9102, "course": 20119, "name": "Final project", "nosubmissions": 0,
             "duedate": 1791000000, "allowsubmissionsfromdate": 0, "cutoffdate": 1791100000, "teamsubmission": 1,
             "requireallteammemberssubmit": 0, "configs": [], "intro": "", "introformat": 1, "introattachments": []},
            {"id": 703, "cmid": 9103, "course": 20119, "name": "Reading", "nosubmissions": 1, "duedate": 0,
             "allowsubmissionsfromdate": 0, "cutoffdate": 0, "intro": "<p>Chapter 3</p>", "introformat": 1}
          ]}], "warnings": []}
    """

    @Test
    fun `each record keeps its description, attachments and whether a group submits together`() {
        val records = MoodleService.assignmentRecords(MoodleService.decodeAssignments(body))

        assertEquals(listOf(701, 702, 703), records.map { it.assignId })
        val first = records[0]
        assertEquals(9101, first.cmid)
        assertEquals(20119, first.courseId)
        assertEquals("HW 2: Linked lists & more", first.name)
        assertEquals(1_790_600_000L, first.dueDate)
        assertEquals(0L, first.cutoffDate)
        assertEquals(1_790_000_000L, first.allowSubmissionsFrom)
        assertEquals("<p>Implement a <strong>singly linked list</strong>.</p>", first.intro)
        assertEquals(
            listOf(
                MoodleFile(
                    name = "spec.pdf", size = 1_258_291,
                    url = "https://moodle2.ntust.edu.tw/webservice/pluginfile.php/123/mod_assign/introattachment/0/spec.pdf",
                    mimeType = "application/pdf", modified = 1_790_000_000L, path = "/",
                ),
            ),
            first.introAttachments,
        )
        assertFalse(first.teamSubmission)
        assertFalse(first.noSubmissions)

        assertTrue(records[1].teamSubmission)
        assertEquals(1_791_100_000L, records[1].cutoffDate)
        assertTrue(records[1].introAttachments.isEmpty())

        assertTrue("one with no due date or submission is kept for the course's list", records[2].noSubmissions)
        assertEquals(0L, records[2].dueDate)
        assertEquals("<p>Chapter 3</p>", records[2].intro)
    }
}
