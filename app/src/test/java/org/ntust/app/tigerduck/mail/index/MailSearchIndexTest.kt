package org.ntust.app.tigerduck.mail.index

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.mail.index.MailSearchIndex.Entry
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.model.MailFlags
import org.ntust.app.tigerduck.mail.model.MailSummary
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MailSearchIndexTest {

    /** A downloaded mail, its summary to match. */
    @Suppress("TestFunctionName")
    private fun Entry(uid: Long, sender: String, subject: String, body: String, seen: Boolean = false) =
        MailSearchIndex.Entry(
            uid, sender, subject, body,
            MailSummary(uid, MailAddress(null, "x@example.com"), emptyList(), emptyList(), emptyList(), subject, null, null,
                MailFlags.NONE.copy(seen = seen), 100, false, null, null, null),
        )

    // In memory: nothing outlives the test.
    private val index = SqliteMailSearchIndex(RuntimeEnvironment.getApplication(), name = null)
    private val me = "b10000001@example.com"
    private val current = { true }

    @After
    fun tearDown() = index.close()

    private fun seed(folder: String = "INBOX", vararg entries: Entry) {
        assertTrue(index.open(me, folder, 7, current))
        assertTrue(index.add(me, folder, 7, entries.toList(), current))
    }

    @Test
    fun `a Chinese word finds the mail by its sender, subject or text, newest first`() {
        seed(
            entries = arrayOf(
                Entry(1, "教務處 <reg@example.com>", "期中考時間", "請準時到場"),
                Entry(2, "總務處 <ga@example.com>", "【施工公告】", "電梯停用"),
                Entry(3, "x@example.com", "Hello", "本週公告事項"),
                Entry(4, "公告欄 <board@example.com>", "Weekly", ""),
            ),
        )

        assertEquals(listOf(4L, 3L, 2L), index.search(me, "INBOX", 7, "公告"))
        assertEquals(listOf(1L), index.search(me, "INBOX", 7, "期中考"))
    }

    @Test
    fun `English matches whatever the case, and a percent sign or underscore is just a character`() {
        seed(
            entries = arrayOf(
                Entry(1, "Moodle <noreply@example.com>", "Assignment due", ""),
                Entry(2, "x@example.com", "100% done", "file_name.pdf"),
                Entry(3, "x@example.com", "1000 done", "filename.pdf"),
            ),
        )

        assertEquals(listOf(1L), index.search(me, "INBOX", 7, "moodle"))
        assertEquals(listOf(2L), index.search(me, "INBOX", 7, "100%"))
        assertEquals(listOf(2L), index.search(me, "INBOX", 7, "file_name"))
    }

    @Test
    fun `every folder's mail comes back for its owner, and none for anyone else`() {
        seed("INBOX", Entry(1, "a@example.com", "公告", ""), Entry(2, "b@example.com", "Hi", ""))
        seed("寄件備份匣", Entry(1, "me@example.com", "公告回覆", ""))

        assertEquals(
            setOf("INBOX" to 1L, "INBOX" to 2L, "寄件備份匣" to 1L),
            index.allMail(me).map { it.folder to it.uid }.toSet(),
        )
        assertEquals("公告回覆", index.allMail(me).single { it.folder == "寄件備份匣" }.summary.subject)
        assertTrue(index.allMail("b10000002@example.com").isEmpty())
    }

    @Test
    fun `a search stays in its folder`() {
        seed("INBOX", Entry(1, "a@example.com", "公告", ""))
        seed("寄件備份匣", Entry(1, "me@example.com", "公告回覆", ""))

        assertEquals(listOf(1L), index.search(me, "寄件備份匣", 7, "回覆"))
        assertEquals(emptyList<Long>(), index.search(me, "INBOX", 7, "回覆"))
    }

    @Test
    fun `a folder recreated on the server starts over`() {
        seed(entries = arrayOf(Entry(1, "a@example.com", "公告", "")))

        assertTrue(index.open(me, "INBOX", 8, current))

        assertEquals(8L, index.uidValidity(me, "INBOX"))
        assertEquals(emptySet<Long>(), index.uids(me, "INBOX"))
    }

    @Test
    fun `under another UIDVALIDITY a search finds nothing, and nothing is flagged or removed`() {
        seed(entries = arrayOf(Entry(1, "a@example.com", "公告", "")))

        assertEquals(emptyList<Long>(), index.search(me, "INBOX", 8, "公告"))
        index.updateFlags(me, "INBOX", 8, mapOf(1L to MailFlags.NONE.copy(seen = true)), current)
        index.remove(me, "INBOX", 8, listOf(1L), current)

        assertFalse(index.summaries(me, "INBOX", 7, listOf(1L)).getValue(1L).flags.seen)
    }

    @Test
    fun `mail added under an old UIDVALIDITY is dropped`() {
        seed(entries = arrayOf(Entry(1, "a@example.com", "公告", "")))
        index.open(me, "INBOX", 8, current)

        assertFalse(index.add(me, "INBOX", 7, listOf(Entry(2, "b@example.com", "公告", "")), current))
        assertEquals(emptySet<Long>(), index.uids(me, "INBOX"))
    }

    @Test
    fun `nothing is written for an account that has left`() {
        assertFalse(index.open(me, "INBOX", 7) { false })
        assertNull(index.uidValidity(me, "INBOX"))

        index.open(me, "INBOX", 7, current)
        assertFalse(index.add(me, "INBOX", 7, listOf(Entry(1, "a@example.com", "公告", ""))) { false })
        assertEquals(emptySet<Long>(), index.uids(me, "INBOX"))
    }

    @Test
    fun `nothing is removed or flagged anew for an account that has left`() {
        seed(entries = arrayOf(Entry(1, "a@example.com", "公告", ""), Entry(2, "b@example.com", "公告", "")))

        index.remove(me, "INBOX", 7, listOf(1L)) { false }
        index.updateFlags(me, "INBOX", 7, mapOf(2L to MailFlags.NONE.copy(seen = true))) { false }

        val stored = index.summaries(me, "INBOX", 7, listOf(1L, 2L))
        assertEquals(setOf(1L, 2L), stored.keys)
        assertFalse(stored.getValue(2L).flags.seen)
    }

    @Test
    fun `mail gone from the server leaves the index`() {
        seed(entries = arrayOf(Entry(1, "a@example.com", "公告", ""), Entry(2, "b@example.com", "公告", "")))

        index.remove(me, "INBOX", 7, listOf(1L), current)

        assertEquals(setOf(2L), index.uids(me, "INBOX"))
    }

    @Test
    fun `another account never sees this one's mail, and starts the index over`() {
        seed(entries = arrayOf(Entry(1, "a@example.com", "公告", "")))
        val next = "b10000002@example.com"

        assertEquals(emptyList<Long>(), index.search(next, "INBOX", 7, "公告"))
        assertTrue(index.open(next, "INBOX", 7, current))
        assertEquals(emptySet<Long>(), index.uids(next, "INBOX"))
        assertFalse("the one that left adds nothing", index.add(me, "INBOX", 7, listOf(Entry(2, "b@example.com", "公告", "")), current))
        assertEquals(emptyList<Long>(), index.search(me, "INBOX", 7, "公告"))
    }

    @Test
    fun `the list reads back each downloaded mail as it was, and only those downloaded`() {
        seed(entries = arrayOf(Entry(1, "a@example.com", "期中考", ""), Entry(2, "b@example.com", "公告", "")))

        val stored = index.summaries(me, "INBOX", 7, listOf(1L, 2L, 3L))

        assertEquals(setOf(1L, 2L), stored.keys)
        assertEquals("期中考", stored.getValue(1L).subject)
        assertEquals(emptyMap<Long, MailSummary>(), index.summaries(me, "INBOX", 8, listOf(1L)))
    }

    @Test
    fun `a mail read elsewhere reads as read, and one deleted there leaves`() {
        seed(entries = arrayOf(Entry(1, "a@example.com", "期中考", ""), Entry(2, "b@example.com", "公告", "")))

        index.updateFlags(me, "INBOX", 7, mapOf(1L to MailFlags.NONE.copy(seen = true), 2L to MailFlags.NONE.copy(deleted = true)), current)

        val stored = index.summaries(me, "INBOX", 7, listOf(1L, 2L))
        assertTrue(stored.getValue(1L).flags.seen)
        assertEquals(setOf(1L), stored.keys)
    }

    @Test
    fun `a cleanup keeps the mail of the owner it is told to keep, and empties anyone else's`() {
        seed(entries = arrayOf(Entry(1, "a@example.com", "公告", "")))

        index.clearUnless { it == me }
        assertEquals(setOf(1L), index.uids(me, "INBOX"))

        index.clearUnless { it == "b10000002@example.com" }
        assertNull(index.uidValidity(me, "INBOX"))
        assertEquals(emptySet<Long>(), index.uids(me, "INBOX"))
    }

    @Test
    fun `a sign-out empties every folder`() {
        seed("INBOX", Entry(1, "a@example.com", "公告", ""))
        seed("寄件備份匣", Entry(1, "me@example.com", "公告", ""))

        index.clear()

        assertNull(index.uidValidity(me, "INBOX"))
        assertEquals(emptyList<Long>(), index.search(me, "INBOX", 7, "公告"))
        assertEquals(emptyList<Long>(), index.search(me, "寄件備份匣", 7, "公告"))
    }
}
