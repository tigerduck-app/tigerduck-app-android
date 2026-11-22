package org.ntust.app.tigerduck.demo

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.mail.DemoMailFixture
import org.ntust.app.tigerduck.shared.time.TaipeiClock
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/** The demo file's dates moved next to today (DemoDatesTests on iOS, tigerduck-app#1049). */
class DemoDatesTest {

    private fun moved(json: String, today: String) = JsonParser.parseString(DemoDates.shifted(json, LocalDate.parse(today))).asJsonObject

    @Test
    fun `every string that starts with a day moves by today less the anchor, the rest as written`() {
        val json = """
            {"anchor": "2026-10-15", "due": "2026-10-15T23:59", "posted": "2026-10-13T09:00:00+08:00",
             "list": [{"date": "2026-09-14"}, "2026-12-31 note"], "nested": {"deep": ["2026-10-01"]}}
        """
        val root = moved(json, "2026-11-23")
        assertEquals("2026-11-23", root.get("anchor").asString)
        assertEquals("2026-11-23T23:59", root.get("due").asString)
        assertEquals("2026-11-21T09:00:00+08:00", root.get("posted").asString)
        assertEquals("2026-10-23", root.getAsJsonArray("list")[0].asJsonObject.get("date").asString)
        assertEquals("2027-02-08 note", root.getAsJsonArray("list")[1].asString)
        assertEquals("2026-11-09", root.getAsJsonObject("nested").getAsJsonArray("deep")[0].asString)
        assertEquals("backwards too", "2026-10-08T23:59", moved(json, "2026-10-08").get("due").asString)
    }

    @Test
    fun `numbers, keys, weekdays and strings that only look like a day stay`() {
        val json = """
            {"anchor": "2026-10-15", "2026-10-15": 1, "id": 20261015, "weekday": 3, "start": "19:00",
             "bad": "2026-13-45T00:00", "short": "2026-10", "word": "x2026-10-15", "flag": true, "none": null}
        """
        val root = moved(json, "2026-10-20")
        assertTrue(root.has("2026-10-15"))
        assertEquals(20261015, root.get("id").asInt)
        assertEquals(3, root.get("weekday").asInt)
        assertEquals("19:00", root.get("start").asString)
        assertEquals("2026-13-45T00:00", root.get("bad").asString)
        assertEquals("2026-10", root.get("short").asString)
        assertEquals("x2026-10-15", root.get("word").asString)
        assertTrue(root.get("flag").asBoolean)
        assertTrue(root.get("none").isJsonNull)
    }

    @Test
    fun `a file without an anchor, on its anchor day, or not an object stays as it is`() {
        val plain = """{"due": "2026-10-15T23:59"}"""
        assertSame(plain, DemoDates.shifted(plain, LocalDate.parse("2026-11-23")))
        val onAnchor = """{"anchor": "2026-10-15", "due": "2026-10-15T23:59"}"""
        assertSame(onAnchor, DemoDates.shifted(onAnchor, LocalDate.parse("2026-10-15")))
        assertSame("[1]", DemoDates.shifted("[1]", LocalDate.parse("2026-11-23")))
        assertSame("{", DemoDates.shifted("{", LocalDate.parse("2026-11-23")))
    }

    @Test
    fun `with its anchor on today the shipped file has work due today, in five days and five days ago`() {
        val file = File("src/main/assets/demo.json").readText()
        assertEquals("2026-10-15", JsonParser.parseString(file).asJsonObject.get("anchor").asString)
        val today = LocalDate.parse("2026-11-23")
        val fixture = DemoFixture.parse(DemoDates.shifted(file, today), DemoFixture.LANG_EN)
        val due = fixture.assignments!!.map {
            LocalDateTime.ofInstant(it.dueDate!!.toInstant(), TaipeiClock.zone).toLocalDate()
        }
        assertEquals(listOf(today, today.plusDays(5), today.minusDays(5)), due)
        assertTrue("the one five days ago was handed in", fixture.assignments.last().isCompleted)
        val mail = DemoMailFixture.parse(DemoDates.shifted(file, today), "en")!!
        assertTrue("the mail is from the last three days", mail.messages.isNotEmpty() && mail.messages.all {
            val day = it.summary.receivedAt!!.atZone(TaipeiClock.zone).toLocalDate()
            !day.isAfter(today) && !day.isBefore(today.minusDays(3))
        })
    }
}
