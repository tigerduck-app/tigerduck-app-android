package org.ntust.app.tigerduck.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoMailFixtureTest {
    private val json = """
        {
          "studentId": "B10000099",
          "mail": {
            "password": "demo-mail",
            "displayName": {"zh": "示範同學", "en": "Demo Student"},
            "messages": [
              {"from": {"name": "教務處", "address": "office@mail.ntust.edu.tw"},
               "subject": {"zh": "期中考時間公告", "en": "Midterm schedule"},
               "date": "2026-09-14T09:00:00+08:00", "seen": false,
               "html": "<p>Midterm</p>", "plain": "Midterm"},
              {"from": {"address": "prize@evil.example"},
               "subject": "Verify your account password",
               "date": "2026-09-15T09:00:00+08:00", "seen": true,
               "plain": "click", "attachments": [{"name": "invoice.pdf.exe", "type": "application/octet-stream", "size": 1024}]}
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `parses the demo mailbox newest first in the requested language`() {
        val box = DemoMailFixture.parse(json, "en")!!
        assertEquals("Demo Student", box.displayName)
        assertEquals(listOf("Verify your account password", "Midterm schedule"), box.messages.map { it.summary.subject })
        assertTrue(box.messages[0].summary.flags.seen)
        assertEquals("invoice.pdf.exe", box.messages[0].body.attachments.single().fileName)
        assertTrue(box.messages[0].summary.hasAttachments)
        assertEquals("期中考時間公告", DemoMailFixture.parse(json, "zh")!!.messages[1].summary.subject)
    }

    @Test
    fun `credentials match the demo account and nothing else`() {
        val box = DemoMailFixture.parse(json, "en")!!
        assertTrue(box.matches(" b10000099 ", "demo-mail"))
        assertFalse(box.matches("B10000099", "wrong"))
        assertFalse(DemoMailbox.EMPTY.matches("", ""))
        assertNull(DemoMailFixture.parse("{\"studentId\": \"x\"}", "en"))
    }
}
