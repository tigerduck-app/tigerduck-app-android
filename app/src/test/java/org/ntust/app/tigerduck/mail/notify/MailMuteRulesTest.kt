package org.ntust.app.tigerduck.mail.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.mail.mailSummary
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.notify.MailMuteRule.Field
import org.ntust.app.tigerduck.mail.notify.MailMuteRule.Operator
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * Which new mail School Mail leaves out of its notifications (tigerduck-app-android#354), as the
 * iPhone decides it (tigerduck-app#801). All of it is made-up mail.
 */
class MailMuteRulesTest {

    private val office = MailAddress("Academic Affairs", "office@example.com")
    private val mail = mailSummary(
        uid = 1,
        subject = "Course Selection Results",
        from = office,
        to = listOf(MailAddress("Student", "b00015999@example.com")),
        cc = listOf(MailAddress(null, "Advisor@Example.com")),
    )

    private fun rule(field: Field, op: Operator, value: String) = MailMuteRule("r", field, op, value)

    /** [text], a Taipei wall-clock time such as `2026-10-21T23:30` (a Wednesday). */
    private fun taipei(text: String): Instant = LocalDateTime.parse(text).atZone(ZoneId.of("Asia/Taipei")).toInstant()

    @Test
    fun `From contains checks the display name and the address, ignoring case`() {
        assertTrue(rule(Field.FROM, Operator.CONTAINS, "academic").matches(mail))
        assertTrue(rule(Field.FROM, Operator.CONTAINS, "OFFICE@").matches(mail))
        assertFalse(rule(Field.FROM, Operator.CONTAINS, "registrar").matches(mail))
    }

    @Test
    fun `From is compares the address only`() {
        assertTrue(rule(Field.FROM, Operator.IS, "Office@Example.com").matches(mail))
        assertFalse(rule(Field.FROM, Operator.IS, "Academic Affairs").matches(mail))
        assertFalse(rule(Field.FROM, Operator.IS, "office@example").matches(mail))
    }

    @Test
    fun `To or Cc checks every recipient`() {
        assertTrue(rule(Field.TO_OR_CC, Operator.CONTAINS, "student").matches(mail))
        assertTrue(rule(Field.TO_OR_CC, Operator.CONTAINS, "advisor@").matches(mail))
        assertTrue(rule(Field.TO_OR_CC, Operator.IS, "advisor@example.com").matches(mail))
        assertFalse(rule(Field.TO_OR_CC, Operator.IS, "Student").matches(mail))
    }

    @Test
    fun `Subject is means the whole subject`() {
        assertTrue(rule(Field.SUBJECT, Operator.CONTAINS, "selection").matches(mail))
        assertTrue(rule(Field.SUBJECT, Operator.IS, "course selection results").matches(mail))
        assertFalse(rule(Field.SUBJECT, Operator.IS, "Course Selection").matches(mail))
    }

    @Test
    fun `the value is trimmed, and an empty one matches nothing`() {
        assertTrue(rule(Field.FROM, Operator.IS, "  office@example.com ").matches(mail))
        assertFalse(rule(Field.SUBJECT, Operator.CONTAINS, "").matches(mail))
        assertFalse(rule(Field.SUBJECT, Operator.CONTAINS, "   ").matches(mail))
    }

    @Test
    fun `a mail matching two rules is muted once, and one matching none is not`() {
        val both = MailMuteSettings(
            rules = listOf(rule(Field.FROM, Operator.CONTAINS, "office"), rule(Field.SUBJECT, Operator.CONTAINS, "results")),
        )
        assertTrue(both.mutes(mail, taipei("2026-10-21T12:00")))
        val neither = MailMuteSettings(rules = listOf(rule(Field.FROM, Operator.CONTAINS, "library")))
        assertFalse(neither.mutes(mail, taipei("2026-10-21T12:00")))
    }

    @Test
    fun `the bulletin board preset mutes its sender`() {
        val bulletin = mailSummary(uid = 2, subject = "Lecture notice", from = MailAddress("NTUST", "Bulletin@mail.ntust.edu.tw"))
        assertTrue(MailMutePreset.BULLETIN.matches(bulletin))
        assertFalse(MailMutePreset.BULLETIN.matches(mail))
    }

    @Test
    fun `the Moodle receipt preset needs its sender and one of its subjects`() {
        val sender = MailAddress("Moodle", "noreply@mail.ntust.edu.tw")
        assertTrue(MailMutePreset.MOODLE_SUBMISSION.matches(mailSummary(uid = 3, subject = "Lab 3：您已經繳交", from = sender)))
        assertTrue(
            MailMutePreset.MOODLE_SUBMISSION.matches(
                mailSummary(uid = 4, subject = "You have submitted your assignment submission for Lab 3", from = sender),
            ),
        )
        assertFalse(MailMutePreset.MOODLE_SUBMISSION.matches(mailSummary(uid = 5, subject = "New forum post", from = sender)))
        assertFalse(MailMutePreset.MOODLE_SUBMISSION.matches(mailSummary(uid = 6, subject = "您已經繳交", from = office)))
    }

    @Test
    fun `a preset mutes only while it is switched on`() {
        val bulletin = mailSummary(uid = 2, from = MailAddress(null, "bulletin@mail.ntust.edu.tw"))
        assertFalse(MailMuteSettings().mutes(bulletin, taipei("2026-10-21T12:00")))
        assertTrue(MailMuteSettings(presets = setOf(MailMutePreset.BULLETIN)).mutes(bulletin, taipei("2026-10-21T12:00")))
    }

    @Test
    fun `quiet hours inside one day take the start minute and leave the end minute`() {
        val lunch = MailQuietHours(enabled = true, startMinute = 12 * 60, endMinute = 13 * 60)
        assertFalse(lunch.contains(taipei("2026-10-21T11:59")))
        assertTrue(lunch.contains(taipei("2026-10-21T12:00")))
        assertTrue(lunch.contains(taipei("2026-10-21T12:59")))
        assertFalse(lunch.contains(taipei("2026-10-21T13:00")))
    }

    @Test
    fun `quiet hours past midnight belong to the day they start on`() {
        // Wednesday night only: 23:00 on Wednesday to 07:00 on Thursday.
        val night = MailQuietHours(enabled = true, days = setOf(DayOfWeek.WEDNESDAY))
        assertTrue(night.contains(taipei("2026-10-21T23:30")))
        assertTrue(night.contains(taipei("2026-10-22T06:59")))
        assertFalse(night.contains(taipei("2026-10-22T07:00")))
        // Thursday night is not selected, nor is the morning after Tuesday night.
        assertFalse(night.contains(taipei("2026-10-22T23:30")))
        assertFalse(night.contains(taipei("2026-10-21T06:00")))
    }

    @Test
    fun `a Sunday night runs into Monday`() {
        val sunday = MailQuietHours(enabled = true, days = setOf(DayOfWeek.SUNDAY))
        assertTrue(sunday.contains(taipei("2026-10-26T03:00")))
        assertFalse(sunday.contains(taipei("2026-10-27T03:00")))
    }

    @Test
    fun `quiet hours that are off, or start where they end, quiet nothing`() {
        assertFalse(MailQuietHours().contains(taipei("2026-10-21T23:30")))
        assertFalse(MailQuietHours(enabled = true, startMinute = 600, endMinute = 600).contains(taipei("2026-10-21T10:00")))
    }

    @Test
    fun `quiet hours read Taipei time on a phone set to another time zone`() {
        val zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        try {
            val night = MailQuietHours(enabled = true, days = setOf(DayOfWeek.WEDNESDAY))
            assertTrue(night.contains(taipei("2026-10-21T23:30")))
            assertFalse(night.contains(taipei("2026-10-21T15:00")))
        } finally {
            TimeZone.setDefault(zone)
        }
    }

    @Test
    fun `the last selected day stays selected`() {
        val one = MailQuietHours(days = setOf(DayOfWeek.MONDAY))
        assertEquals(setOf(DayOfWeek.MONDAY), one.toggling(DayOfWeek.MONDAY).days)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), one.toggling(DayOfWeek.FRIDAY).days)
        assertEquals(setOf(DayOfWeek.FRIDAY), one.toggling(DayOfWeek.FRIDAY).toggling(DayOfWeek.MONDAY).days)
    }

    @Test
    fun `quiet hours mute by the time the mail arrived`() {
        val settings = MailMuteSettings(quietHours = MailQuietHours(enabled = true))
        assertTrue(settings.mutes(mail, taipei("2026-10-21T23:30")))
        assertFalse(settings.mutes(mail, taipei("2026-10-21T21:00")))
    }

    @Test
    fun `the settings read back as written`() {
        val settings = MailMuteSettings(
            quietHours = MailQuietHours(enabled = true, startMinute = 22 * 60 + 15, endMinute = 6 * 60 + 45, days = setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY)),
            presets = setOf(MailMutePreset.MOODLE_SUBMISSION),
            rules = listOf(MailMuteRule("a", Field.TO_OR_CC, Operator.IS, "list@example.com"), MailMuteRule("b", Field.SUBJECT, Operator.CONTAINS, "週報")),
        )
        assertEquals(settings, MailMuteSettingsCodec.decode(MailMuteSettingsCodec.encode(settings)))
    }

    @Test
    fun `missing or unreadable settings read as the defaults, field by field`() {
        assertEquals(MailMuteSettings(), MailMuteSettingsCodec.decode(null))
        assertEquals(MailMuteSettings(), MailMuteSettingsCodec.decode("not json"))
        // A field a later build adds, or one this build no longer knows, leaves the rest as stored.
        val partial = MailMuteSettingsCodec.decode(
            """{"quietHours":{"enabled":true},"presets":["bulletin","somethingNew"],"rules":[{"id":"a","field":"from","value":"x@example.com"},{"field":"unknown","op":"is","value":"y"}]}""",
        )
        assertEquals(MailQuietHours(enabled = true), partial.quietHours)
        assertEquals(setOf(MailMutePreset.BULLETIN), partial.presets)
        assertEquals(listOf(MailMuteRule("a", Field.FROM, Operator.CONTAINS, "x@example.com")), partial.rules)
    }

    @Test
    fun `a stored rule without an id reads with the same one every time`() {
        val stored = """{"rules":[{"field":"from","op":"is","value":"a@example.com"},{"id":"b","field":"subject","value":"x"},{"id":"b","value":"y"}]}"""
        val first = MailMuteSettingsCodec.decode(stored).rules.map { it.id }
        assertEquals(first, MailMuteSettingsCodec.decode(stored).rules.map { it.id })
        // And each its own, so an edit or a delete finds the one it opened.
        assertEquals(3, first.toSet().size)
    }

    @Test
    fun `stored days with none selected read as every day`() {
        val none = MailMuteSettingsCodec.decode("""{"quietHours":{"enabled":true,"days":[]}}""")
        assertEquals(DayOfWeek.entries.toSet(), none.quietHours.days)
    }
}
