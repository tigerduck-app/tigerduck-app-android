package org.ntust.app.tigerduck.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

class DemoFixtureTest {

    private val sample = """
        {
          "studentId": " b11308964 ",
          "school-password": "pw",
          "library-password": "lib",
          "libraryQr": { "content": "https://example.com", "fakeLoggedIn": true },
          "courses": [
            {
              "courseNo": "CS3005301",
              "name": { "zh": "資料結構", "en": "Data Structures" },
              "instructor": "Wang",
              "credits": 3,
              "classroom": "TR-513",
              "colorHex": "#E57373",
              "schedule": { "1": ["2", "3"], "x": ["1"] },
              "classroomMap": { "1-2": "TR-215" }
            },
            { "name": "no course number" },
            "not an object"
          ],
          "assignments": [
            { "id": "900001", "courseNo": "CS3005301", "courseName": "DS",
              "title": { "en": "Homework 3" }, "due": "2026-09-15T23:59", "completed": true },
            { "id": "900002", "due": "not a date" }
          ],
          "bulletins": [
            { "title": "Library maintenance", "org": "圖書館", "tags": ["圖書館", "維護"],
              "postedAt": "2026-09-07T14:30:00Z" }
          ],
          "calendar": [
            { "title": "開學日", "date": "2026-09-14" },
            { "title": "no date", "date": "" }
          ],
          "classmates": [
            { "studentId": " b11308965 ", "nickname": { "zh": "小鴨", "en": "Duckling" },
              "courses": [ { "courseNo": "MA2012301", "name": "Probability", "schedule": { "2": ["3"] } } ] },
            { "nickname": "no student ID", "courses": [] }
          ]
        }
    """.trimIndent()

    @Test
    fun `reads each section into the app's models, skipping malformed entries`() {
        val fixture = DemoFixture.parse(sample, DemoFixture.LANG_EN)

        val course = fixture.courses!!.single()
        assertEquals("CS3005301", course.courseNo)
        assertEquals("Data Structures", course.courseName)
        assertEquals("Wang", course.instructor)
        assertEquals(3f, course.credits, 0f)
        assertEquals("#E57373", course.customColorHex)
        assertTrue(course.scheduleJson.contains("\"1\""))
        assertFalse("a non-numeric weekday is dropped", course.scheduleJson.contains("\"x\""))

        val assignment = fixture.assignments!!.single()
        assertEquals("900001", assignment.assignmentId)
        assertTrue(assignment.isCompleted)
        assertEquals(Instant.parse("2026-09-15T15:59:00Z").toEpochMilli(), assignment.dueDate.time)

        val bulletin = fixture.bulletins!!.single()
        assertEquals(1, bulletin.id)
        assertEquals("demo-1", bulletin.externalId)
        assertEquals("圖書館", bulletin.canonicalOrg)
        assertEquals(listOf("圖書館", "維護"), bulletin.contentTags)

        val event = fixture.calendar!!.single()
        assertEquals("demo-cal-1", event.eventId)
        assertEquals("school", event.sourceRaw)
        assertEquals(Instant.parse("2026-09-13T16:00:00Z").toEpochMilli(), event.date.time)

        assertEquals("https://example.com", fixture.libraryQrContent)
        assertTrue(fixture.libraryFakeSignedIn)

        val classmate = fixture.classmates!!.single()
        assertEquals("B11308965", classmate.studentId)
        assertEquals("Duckling", classmate.nickname)
        assertEquals(listOf("MA2012301"), classmate.courses.map { it.courseNo })
        assertEquals("小鴨", DemoFixture.parse(sample, DemoFixture.LANG_ZH).classmates!!.single().nickname)
    }

    @Test
    fun `picks the requested language and falls back to the other`() {
        val zh = DemoFixture.parse(sample, DemoFixture.LANG_ZH)
        assertEquals("資料結構", zh.courses!!.single().courseName)
        assertEquals("Homework 3", zh.assignments!!.single().title)
    }

    @Test
    fun `an absent section is null, not empty`() {
        val fixture = DemoFixture.parse("{}", DemoFixture.LANG_EN)
        assertNull(fixture.courses)
        assertNull(fixture.assignments)
        assertNull(fixture.bulletins)
        assertNull(fixture.calendar)
        assertNull(fixture.classmates)
        assertFalse(fixture.libraryFakeSignedIn)
    }

    @Test
    fun `matches the student id the way login normalizes it, and the password exactly`() {
        val fixture = DemoFixture.parse(sample, DemoFixture.LANG_EN)
        assertTrue(fixture.matches("B11308964", "pw"))
        assertTrue(fixture.matches("  b11308964", "pw"))
        assertFalse(fixture.matches("B11308964", "PW"))
        assertFalse(fixture.matches("B11308964", "pw "))
        assertFalse(fixture.matches("B11308965", "pw"))
    }

    @Test
    fun `the library sign-in takes the same id with the library password`() {
        val fixture = DemoFixture.parse(sample, DemoFixture.LANG_EN)
        assertTrue(fixture.matchesLibrary("  b11308964", "lib"))
        assertFalse(fixture.matchesLibrary("B11308964", "pw"))
        assertFalse(fixture.matches("B11308964", "lib"))
        assertFalse(fixture.matchesLibrary("B11308965", "lib"))
    }

    @Test
    fun `the Moodle section is read into the app's models`() {
        val json = """
            {"moodle": {"ownMemberId": 1, "courses": [
              {"courseNo": "CS3005301", "moodleId": 5,
               "summary": {"zh": "<p>摘要</p>", "en": "<p>Summary</p>"},
               "announcements": [
                 {"id": 7, "created": "2026-09-14T09:00", "pinned": true, "unread": true,
                  "title": {"en": "Syllabus"}, "message": {"en": "<p>Hi <b>all</b></p>"},
                  "attachments": [{"name": "a.pdf", "size": 10, "mime": "application/pdf"}]},
                 {"title": "no date"}],
               "grades": [{"name": "HW1", "module": "assign", "grade": "90", "max": 100},
                          {"type": "course", "grade": "90", "max": 100}],
               "members": [{"id": 1, "name": "B1 @ Me", "role": "student"}, {"name": "no id"}]},
              {"moodleId": 6}
            ]}}
        """.trimIndent()
        val moodle = DemoFixture.parse(json, DemoFixture.LANG_ZH).moodle!!

        val course = moodle.courses.single()
        assertEquals("<p>摘要</p>", course.summaryHtml)
        val announcement = course.announcements.single()
        assertEquals("Syllabus", announcement.title)
        assertEquals("Hi all", announcement.preview)
        assertTrue(announcement.isPinned)
        assertEquals(1, announcement.unreadCount)
        assertEquals("a.pdf", announcement.attachments.single().name)
        assertEquals(listOf("mod", "course"), course.grades.map { it.type })
        assertEquals(listOf(1), course.members.map { it.id })
    }

    @Test
    fun `a Moodle course's assignments are read with their submissions, comments and group`() {
        val json = """
            {"assignments": [
               {"id": "900001", "courseNo": "CS3005301", "courseName": "DS", "title": {"en": "HW 3"}, "due": "2026-09-15T23:59"},
               {"id": "900002", "courseNo": "CS3005301", "courseName": "DS", "title": "Lab 2", "due": "2026-09-18T18:00", "completed": true}],
             "moodle": {"ownMemberId": 1, "courses": [
              {"courseNo": "CS3005301", "moodleId": 5, "assignments": [
                 {"id": 900001, "cmid": 950001, "intro": {"en": "<p>Balance <b>trees</b></p>"},
                  "attachments": [{"name": "hw3.pdf", "size": 12, "mime": "application/pdf"}]},
                 {"id": 900002, "cmid": 950002, "teamSubmission": true,
                  "submission": {"status": "submitted", "time": "2026-09-17T20:10",
                    "files": [{"name": "lab2.zip", "size": 2048, "mime": "application/zip"}],
                    "text": {"en": "<p>See the zip.</p>"}, "group": {"id": 31, "name": {"en": "Group 3"}},
                    "comments": [{"author": "T0001 @ Teacher", "time": "2026-09-18T09:00", "content": {"en": "<p>Thanks</p>"}}],
                    "grade": {"grade": "92.00 / 100.00", "gradedAt": "2026-09-19T10:30", "feedback": {"en": "<p>Neat.</p>"}}}},
                 {"id": 900009, "cmid": 950009, "noSubmissions": true},
                 {"cmid": 1}]}
            ]}}
        """.trimIndent()
        val fixture = DemoFixture.parse(json, DemoFixture.LANG_EN)

        val assignments = fixture.moodle!!.courses.single().assignments
        assertEquals(listOf(900001, 900002, 900009), assignments.map { it.id })
        val hw3 = assignments[0]
        assertEquals(950001, hw3.cmid)
        assertEquals("<p>Balance <b>trees</b></p>", hw3.intro)
        assertEquals("hw3.pdf", hw3.attachments.single().name)
        assertTrue(hw3.attachments.single().url.startsWith("https://moodle2.ntust.edu.tw/webservice/pluginfile.php/"))
        assertNull("no record on Moodle yet", hw3.submission)
        assertFalse(hw3.teamSubmission)

        val lab2 = assignments[1]
        assertTrue(lab2.teamSubmission)
        val submission = lab2.submission!!
        assertTrue(submission.isSubmitted)
        assertEquals(Instant.parse("2026-09-17T12:10:00Z").epochSecond, submission.submittedAt)
        assertEquals("lab2.zip", submission.files.single().name)
        assertEquals("<p>See the zip.</p>", submission.onlineText)
        assertEquals(31, submission.groupId)
        assertEquals("Group 3", submission.groupName)
        assertTrue(submission.takesComments)
        val comment = submission.comments!!.single()
        assertEquals("T0001 @ Teacher", comment.authorName)
        assertEquals("<p>Thanks</p>", comment.content)
        assertEquals(Instant.parse("2026-09-18T01:00:00Z").epochSecond, comment.created)
        val grade = submission.grade!!
        assertEquals("92.00 / 100.00", grade.grade)
        assertEquals(Instant.parse("2026-09-19T02:30:00Z").epochSecond, grade.gradedAt)
        assertEquals("<p>Neat.</p>", grade.feedback)
        assertNull("a submission without a grade has none", hw3.submission?.grade)
        assertTrue(assignments[2].noSubmissions)

        val records = DemoMoodleFixture.records(fixture.moodle, fixture.assignments!!)
        assertEquals("one with no Home row is left out", listOf(900001, 900002), records.map { it.assignId })
        assertEquals("HW 3", records[0].name)
        assertEquals(Instant.parse("2026-09-15T15:59:00Z").epochSecond, records[0].dueDate)
        assertEquals(5, records[0].courseId)
        assertEquals(950001, records[0].cmid)
        assertEquals("<p>Balance <b>trees</b></p>", records[0].intro)
        assertTrue(records[1].teamSubmission)
    }

    @Test
    fun `a file without credentials never matches`() {
        val fixture = DemoFixture.parse("""{ "studentId": "B1" }""", DemoFixture.LANG_EN)
        assertFalse(fixture.matches("B1", ""))
        assertFalse(fixture.matchesLibrary("B1", ""))
        assertFalse(DemoFixture.parse("{}", DemoFixture.LANG_EN).matches("", ""))
    }

    private fun root() = com.google.gson.JsonParser.parseString(File("src/main/assets/demo.json").readText()).asJsonObject

    @Test
    fun `the shipped demo account parses in full`() {
        val json = File("src/main/assets/demo.json").readText()
        for (lang in listOf(DemoFixture.LANG_EN, DemoFixture.LANG_ZH)) {
            val fixture = DemoFixture.parse(json, lang)
            assertEquals(5, fixture.courses!!.size)
            assertEquals(3, fixture.assignments!!.size)
            assertEquals(2, fixture.bulletins!!.size)
            assertEquals(3, fixture.calendar!!.size)
            assertTrue(fixture.libraryFakeSignedIn)
            assertTrue(fixture.courses.all { it.courseName.isNotBlank() })
            val moodle = fixture.moodle!!
            assertEquals(3, moodle.courses.size)
            val courseNos = fixture.courses.map { it.courseNo }
            assertTrue("every Moodle course is a course on the timetable", moodle.courses.all { it.courseNo in courseNos })
            val pages = moodle.courses.flatMap { it.assignments }
            assertEquals(
                "every assignment on Home has a page",
                fixture.assignments.map { it.assignmentId }.toSet(),
                pages.map { it.id.toString() }.toSet(),
            )
            assertTrue("each page has a description", pages.all { it.intro.isNotBlank() })
            assertTrue("one of them was handed in, with a comment", pages.any { it.submission?.isSubmitted == true && !it.submission.comments.isNullOrEmpty() })
            assertTrue("and graded, with feedback", pages.any { it.submission?.grade?.grade != null && it.submission.grade?.feedback != null })
            assertTrue("one of them was not", pages.any { it.submission?.isSubmitted != true })
            assertTrue(moodle.courses.all { c -> c.announcements.all { it.title.isNotBlank() && it.created > 0 } })
            assertTrue("the student is among each course's members",
                moodle.courses.all { c -> c.members.any { it.id == moodle.ownMemberId } })
            val classmate = fixture.classmates!!.single()
            assertEquals(5, classmate.courses.size)
            // A share code names the classmate and carries each course, so the import is real.
            assertTrue(org.ntust.app.tigerduck.data.sharing.ClassTableShare.canName(classmate.studentId))
            assertTrue(classmate.courses.all { it.courseNo.matches(Regex("3?[A-Z]{2}[A-Z0-9]{6,7}")) })
            assertTrue(classmate.studentId != root().get("studentId").asString.uppercase())
        }
        // Read back from the file rather than repeated here, so a new
        // password there needs no edit to this test.
        val root = com.google.gson.JsonParser.parseString(json).asJsonObject
        val fixture = DemoFixture.parse(json, DemoFixture.LANG_EN)
        val id = root.get("studentId").asString
        assertTrue(fixture.matches(id, root.get("school-password").asString))
        assertTrue(fixture.matchesLibrary(id, root.get("library-password").asString))
    }
}
