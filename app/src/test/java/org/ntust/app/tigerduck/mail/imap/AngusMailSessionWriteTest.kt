package org.ntust.app.tigerduck.mail.imap

import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.ntust.app.tigerduck.mail.MailTestServer
import java.io.ByteArrayOutputStream
import java.util.Properties

class AngusMailSessionWriteTest {
    @get:Rule val server = MailTestServer()

    private fun session() = AngusMailSessionFactory(server.config).open(server.credentials)

    private fun countIn(folder: String): Int = server.rawStore().use { store ->
        val f = store.getFolder(folder)
        f.open(Folder.READ_ONLY)
        f.messageCount.also { f.close(false) }
    }

    private fun rfc822(subject: String, from: String, messageId: String): ByteArray {
        val msg = object : MimeMessage(Session.getInstance(Properties())) {
            override fun updateMessageID() = setHeader("Message-ID", messageId)
        }
        msg.setFrom(InternetAddress(from))
        msg.setSubject(subject, "UTF-8")
        msg.setText("x", "UTF-8")
        msg.saveChanges()
        return ByteArrayOutputStream().also { msg.writeTo(it) }.toByteArray()
    }

    @Test
    fun `seen and answered flags`() {
        server.deliver("a")
        session().use { s ->
            val uid = s.fetchPage("INBOX", null, 10).messages.single().uid
            s.setSeen("INBOX", listOf(uid), true)
            assertTrue(s.refreshFlags("INBOX", listOf(uid)).getValue(uid).seen)
            s.setSeen("INBOX", listOf(uid), false)
            assertFalse(s.refreshFlags("INBOX", listOf(uid)).getValue(uid).seen)
            s.setAnswered("INBOX", uid)
            assertTrue(s.refreshFlags("INBOX", listOf(uid)).getValue(uid).answered)
        }
    }

    @Test
    fun `move expunges when only our mail is deleted`() {
        server.createFolders("回收筒")
        server.deliver("keep")
        server.deliver("trash me")
        session().use { s ->
            val target = s.fetchPage("INBOX", null, 10).messages.first { it.subject == "trash me" }.uid
            assertTrue(s.move("INBOX", target, "回收筒", ownedDeleted = emptySet()))
            assertEquals(listOf("keep"), s.fetchPage("INBOX", null, 10).messages.map { it.subject })
        }
        assertEquals(1, countIn("INBOX"))
        assertEquals(1, countIn("回收筒"))
    }

    @Test
    fun `move never expunges mail another client marked deleted`() {
        server.createFolders("回收筒")
        server.deliver("theirs")
        server.deliver("ours")
        server.rawStore().use { store ->
            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_WRITE)
            inbox.messages.first { it.subject == "theirs" }.setFlag(Flags.Flag.DELETED, true)
            inbox.close(false)
        }
        session().use { s ->
            val ours = s.fetchPage("INBOX", null, 10).messages.single { it.subject == "ours" }.uid
            assertFalse(s.move("INBOX", ours, "回收筒", ownedDeleted = emptySet()))
            // Ours is hidden (flagged), theirs is still there, nothing was expunged.
            assertTrue(s.fetchPage("INBOX", null, 10).messages.isEmpty())
        }
        assertEquals(2, countIn("INBOX"))
        assertEquals(1, countIn("回收筒"))
    }

    @Test
    fun `owned deleted mail counts as ours on a later move`() {
        server.createFolders("回收筒")
        server.deliver("first")
        server.deliver("second")
        session().use { s ->
            val uids = s.fetchPage("INBOX", null, 10).messages.associate { it.subject to it.uid }
            // Simulate a previous move that could not expunge: "first" is flagged by us.
            server.rawStore().use { store ->
                val inbox = store.getFolder("INBOX")
                inbox.open(Folder.READ_WRITE)
                inbox.messages.first { it.subject == "first" }.setFlag(Flags.Flag.DELETED, true)
                inbox.close(false)
            }
            assertTrue(s.move("INBOX", uids.getValue("second"), "回收筒", ownedDeleted = setOf(uids.getValue("first"))))
        }
        assertEquals(0, countIn("INBOX"))
    }

    @Test
    fun `permanent delete`() {
        server.createFolders("回收筒")
        server.deliver("gone")
        session().use { s ->
            val uid = s.fetchPage("INBOX", null, 10).messages.single().uid
            assertTrue(s.deletePermanently("INBOX", uid, emptySet()))
        }
        assertEquals(0, countIn("INBOX"))
    }

    @Test
    fun `search by subject, sender and body, including Chinese`() {
        server.deliver("課程公告", body = "期中考")
        server.deliver("hello", from = "prof@mail.ntust.edu.tw")
        session().use { s ->
            val bySubject = s.search("INBOX", "課程")
            assertEquals(1, bySubject.size)
            // GreenMail's server-side FROM key parses the argument as a full
            // jakarta.mail.internet.InternetAddress and requires exact address
            // equality (verified directly: FromStringTerm("prof@mail") finds
            // nothing, FromStringTerm("prof@mail.ntust.edu.tw") finds it) -
            // unlike SUBJECT/BODY, which do the RFC 3501 substring match. Mail2000
            // has no such restriction, so production code (FromStringTerm, a plain
            // substring term) is unchanged; the test uses the full address so it
            // still exercises the sender branch of search() against GreenMail.
            assertEquals(1, s.search("INBOX", "prof@mail.ntust.edu.tw").size)
            assertEquals(1, s.search("INBOX", "期中").size)
            assertTrue(s.search("INBOX", "nothing-matches").isEmpty())
        }
    }

    @Test
    fun `append, message-id lookup and sender name`() {
        server.createFolders("寄件備份匣")
        session().use { s ->
            s.append("寄件備份匣", rfc822("sent", "\"=?UTF-8?B?5Lit5paH?=\" <b10000001@mail.ntust.edu.tw>", "<sent-1@x>"), setOf(AppendFlag.SEEN))
            assertTrue(s.hasRecentMessageId("寄件備份匣", "<sent-1@x>", window = 10))
            assertFalse(s.hasRecentMessageId("寄件備份匣", "<other@x>", window = 10))
            assertEquals("中文", s.newestSenderName("寄件備份匣", "B10000001@mail.ntust.edu.tw", window = 20))
            val uid = s.fetchPage("寄件備份匣", null, 10).messages.single().uid
            assertTrue(s.refreshFlags("寄件備份匣", listOf(uid)).getValue(uid).seen)
        }
    }
}
