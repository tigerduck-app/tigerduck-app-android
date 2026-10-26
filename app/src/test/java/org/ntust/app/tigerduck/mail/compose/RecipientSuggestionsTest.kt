package org.ntust.app.tigerduck.mail.compose

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.model.MailFlags
import org.ntust.app.tigerduck.mail.model.MailSummary
import java.time.Duration
import java.time.Instant

class RecipientSuggestionsTest {

    private val self = "b00015999@example.com"
    private val now = Instant.parse("2026-10-24T08:00:00Z")
    private var nextUid = 1L

    private fun mail(
        from: MailAddress?,
        to: List<MailAddress> = emptyList(),
        cc: List<MailAddress> = emptyList(),
        daysAgo: Long = 1,
        draft: Boolean = false,
    ) = MailSummary(
        nextUid++, from, emptyList(), to, cc, "Hello", now.minus(Duration.ofDays(daysAgo)), null,
        MailFlags.NONE.copy(draft = draft), 100, false, null, null, null,
    )

    private val me = MailAddress("Student", self)
    private val teacher = MailAddress("王大明", "wang@example.com")
    private val ta = MailAddress("Teaching Assistant", "ta@example.com")
    private val board = MailAddress("公告欄", "board@example.com")

    private fun ranked(vararg mail: MailSummary) =
        RecipientSuggestions.contacts(mail.toList(), self, now).map { it.address.address }

    @Test
    fun `the people written to and heard from count, and the student does not`() {
        assertEquals(
            listOf("wang@example.com", "ta@example.com"),
            ranked(
                mail(from = me, to = listOf(teacher, me), cc = listOf(ta)),
                mail(from = teacher, to = listOf(me), cc = listOf(board)),
            ),
        )
    }

    @Test
    fun `more mail and more recent mail rank higher, and a mail written counts more than one read`() {
        // One mail written to the teacher outranks one heard from the board on the same day.
        assertEquals(listOf("wang@example.com", "board@example.com"), ranked(mail(from = board), mail(from = me, to = listOf(teacher))))
        // Three old mail from the board still trail one written to the teacher this week.
        assertEquals(
            listOf("wang@example.com", "board@example.com"),
            ranked(mail(from = board, daysAgo = 200), mail(from = board, daysAgo = 210), mail(from = board, daysAgo = 220), mail(from = me, to = listOf(teacher))),
        )
        // But often enough, lately, wins.
        assertEquals(
            listOf("board@example.com", "wang@example.com"),
            ranked(mail(from = board), mail(from = board), mail(from = board), mail(from = me, to = listOf(teacher), daysAgo = 90)),
        )
    }

    @Test
    fun `drafts, mailboxes no one can write to and noreply senders are left out`() {
        assertEquals(
            listOf("ta@example.com"),
            ranked(
                mail(from = me, to = listOf(teacher), draft = true),
                mail(from = MailAddress("Mail Deliver System", "")),
                mail(from = MailAddress("Moodle", "noreply@example.com")),
                mail(from = MailAddress(null, "do-not-reply@example.com")),
                mail(from = ta),
            ),
        )
    }

    @Test
    fun `one person under two spellings of an address is one contact, named as lately as possible`() {
        val contacts = RecipientSuggestions.contacts(
            listOf(
                mail(from = MailAddress("Wang", "Wang@Example.com"), daysAgo = 30),
                mail(from = MailAddress("王大明", "wang@example.com"), daysAgo = 2),
                mail(from = MailAddress(null, "wang@example.com"), daysAgo = 1),
            ),
            self, now,
        )
        assertEquals(1, contacts.size)
        assertEquals(MailAddress("王大明", "wang@example.com"), contacts.single().address)
    }

    private val contacts by lazy {
        RecipientSuggestions.contacts(
            listOf(
                mail(from = board),
                mail(from = board),
                mail(from = me, to = listOf(teacher)),
                mail(from = ta),
                mail(from = MailAddress("Lin", "lin.wang@example.com")),
            ),
            self, now,
        )
    }

    private fun matching(typed: String, taken: Collection<String> = emptyList()) =
        RecipientSuggestions.matching(contacts, typed, taken).map { it.address }

    @Test
    fun `what is typed matches a name or an address anywhere, ignoring case, the ones it starts first`() {
        assertEquals(listOf("wang@example.com", "lin.wang@example.com"), matching("WANG"))
        assertEquals(listOf("wang@example.com"), matching("大明"))
        assertEquals(listOf("ta@example.com"), matching("assist"))
        assertEquals(listOf("board@example.com"), matching("公告"))
    }

    @Test
    fun `nothing is suggested for nothing typed, nor anyone already on the mail`() {
        assertEquals(emptyList<String>(), matching("  "))
        assertEquals(listOf("lin.wang@example.com"), matching("wang", taken = listOf("WANG@example.com")))
    }

    @Test
    fun `at most a few are suggested at once`() {
        val many = RecipientSuggestions.contacts(
            (1..9).map { mail(from = MailAddress(null, "student$it@example.com")) }, self, now,
        )
        assertEquals(RecipientSuggestions.SHOWN, RecipientSuggestions.matching(many, "student", emptyList()).size)
    }
}
