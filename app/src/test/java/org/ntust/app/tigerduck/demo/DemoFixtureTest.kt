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
          "password": "pw",
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
        assertEquals(3, course.credits)
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
    fun `a file without credentials never matches`() {
        val fixture = DemoFixture.parse("""{ "studentId": "B1" }""", DemoFixture.LANG_EN)
        assertFalse(fixture.matches("B1", ""))
        assertFalse(DemoFixture.parse("{}", DemoFixture.LANG_EN).matches("", ""))
    }

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
            assertTrue(fixture.courses!!.all { it.courseName.isNotBlank() })
        }
        assertTrue(DemoFixture.parse(json, DemoFixture.LANG_EN).matches("B11308964", "its-my-duty"))
    }
}
