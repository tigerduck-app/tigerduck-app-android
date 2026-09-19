package org.ntust.app.tigerduck.mail.imap

import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.ntust.app.tigerduck.mail.MailError
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

    /**
     * The round trip the create path stands or falls on: a folder made by its decoded name
     * comes back from a fresh `LIST` under that same decoded name, and [MailFolders.resolve]
     * maps it to the role it was made for. Angus encodes to modified UTF-7 on the wire itself,
     * so this is the only form that produces the folder Mail2000 already calls by that name.
     */
    @Test
    fun `a folder created by its decoded name lists back and resolves to the same role`() {
        session().use { s ->
            listOf(SpecialFolder.SENT, SpecialFolder.DRAFTS, SpecialFolder.TRASH).forEach { s.createFolder(it.decodedName) }
            val names = s.listFolders()
            assertTrue("寄件備份匣" in names)
            assertTrue("草稿匣" in names)
            assertTrue("回收筒" in names)
            val resolved = MailFolders.resolve(names)
            assertEquals("寄件備份匣", resolved.nameOf(SpecialFolder.SENT))
            assertEquals("草稿匣", resolved.nameOf(SpecialFolder.DRAFTS))
            assertEquals("回收筒", resolved.nameOf(SpecialFolder.TRASH))
        }
    }

    /**
     * The counter-proof to the test above: handing the create path the already-encoded name
     * gets the `&` encoded a second time, and the account ends up with a mailbox literally
     * called `&W8RO9lCZTv1TIw-` instead of the one the user reads their sent mail in.
     */
    @Test
    fun `a folder created by its raw modified UTF-7 name is not the folder that was wanted`() {
        session().use { s ->
            s.createFolder(SpecialFolder.SENT.imapName)
            val names = s.listFolders()
            assertFalse("寄件備份匣" in names)
            assertTrue(SpecialFolder.SENT.imapName in names)
        }
    }

    /** A created folder must hold messages, not only other folders. */
    @Test
    fun `a created folder can be appended to and read back`() {
        session().use { s ->
            s.createFolder(SpecialFolder.DRAFTS.decodedName)
            s.append("草稿匣", rfc822("kept", "b10000001@mail.ntust.edu.tw", "<kept-1@x>"), setOf(AppendFlag.SEEN, AppendFlag.DRAFT))
            assertEquals(listOf("kept"), s.fetchPage("草稿匣", null, 10).messages.map { it.subject })
        }
    }

    @Test
    fun `creating a folder that already exists is a success, not a failure`() {
        server.createFolders("回收筒")
        session().use { s ->
            s.createFolder("回收筒") // must not throw
            assertEquals("回收筒", MailFolders.resolve(s.listFolders()).nameOf(SpecialFolder.TRASH))
        }
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
    fun `setAnswered silently skips a uid that is already gone`() {
        server.deliver("a")
        session().use { s ->
            s.setAnswered("INBOX", 999_999L) // does not throw
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
    fun `move checks the server's Deleted state, not a locally cached one`() {
        server.createFolders("回收筒")
        server.deliver("theirs")
        server.deliver("ours")
        session().use { s ->
            // Load the page first, so any per-message flag state Angus caches
            // locally is as of THIS point -- before "theirs" gets flagged by
            // another client below. If expungeIfOnlyOurs trusted that local
            // cache instead of asking the server fresh, it would miss "theirs"
            // and wrongly expunge.
            val ours = s.fetchPage("INBOX", null, 10).messages.single { it.subject == "ours" }.uid
            server.rawStore().use { store ->
                val inbox = store.getFolder("INBOX")
                inbox.open(Folder.READ_WRITE)
                inbox.messages.first { it.subject == "theirs" }.setFlag(Flags.Flag.DELETED, true)
                inbox.close(false)
            }
            assertFalse(s.move("INBOX", ours, "回收筒", ownedDeleted = emptySet()))
        }
        assertEquals(2, countIn("INBOX"))
        assertEquals(1, countIn("回收筒"))
    }

    @Test
    fun `move to a missing folder throws and does not flag the source deleted`() {
        server.deliver("keep")
        session().use { s ->
            val uid = s.fetchPage("INBOX", null, 10).messages.single().uid
            try {
                s.move("INBOX", uid, "NoSuchFolder", ownedDeleted = emptySet())
                fail("expected a MailError")
            } catch (e: MailError) {
                // expected
            }
        }
        server.rawStore().use { store ->
            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)
            assertFalse(inbox.messages.single().isSet(Flags.Flag.DELETED))
            inbox.close(false)
        }
        assertEquals(1, countIn("INBOX"))
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
    fun `permanent delete never expunges mail another client marked deleted`() {
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
            assertFalse(s.deletePermanently("INBOX", ours, emptySet()))
        }
        assertEquals(2, countIn("INBOX"))
    }

    @Test
    fun `search by subject, sender and body, including Chinese`() {
        server.deliver("課程公告", body = "期中考")
        server.deliver("hello", from = "prof@mail.ntust.edu.tw")
        session().use { s ->
            val bySubject = s.search("INBOX", "課程")
            assertEquals(1, bySubject.size)
            // RFC 3501 FROM is a substring match; GreenMail matches exactly;
            // confirm against the real server.
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

    @Test
    fun `append with the draft flag sets Draft and Seen`() {
        server.createFolders("草稿匣")
        session().use { s ->
            s.append(
                "草稿匣",
                rfc822("draft", "b10000001@mail.ntust.edu.tw", "<draft-1@x>"),
                setOf(AppendFlag.SEEN, AppendFlag.DRAFT),
            )
            val uid = s.fetchPage("草稿匣", null, 10).messages.single().uid
            val flags = s.refreshFlags("草稿匣", listOf(uid)).getValue(uid)
            assertTrue(flags.seen)
            assertTrue(flags.draft)
        }
    }

    @Test
    fun `hasRecentMessageId and newestSenderName respect the window`() {
        server.deliver("old", from = "\"Target Name\" <target@x.com>", messageId = "<old@x>")
        server.deliver("filler1")
        server.deliver("filler2")
        session().use { s ->
            // "old" is 3rd-from-newest; a window of 2 doesn't reach it.
            assertFalse(s.hasRecentMessageId("INBOX", "<old@x>", window = 2))
            assertNull(s.newestSenderName("INBOX", "target@x.com", window = 2))
            assertTrue(s.hasRecentMessageId("INBOX", "<old@x>", window = 3))
            assertEquals("Target Name", s.newestSenderName("INBOX", "target@x.com", window = 3))
        }
    }

    @Test
    fun `hasRecentMessageId and newestSenderName reject a non-positive window`() {
        server.deliver("a")
        session().use { s ->
            try {
                s.hasRecentMessageId("INBOX", "<x@x>", window = 0)
                fail("expected IllegalArgumentException")
            } catch (e: IllegalArgumentException) {
                // expected
            }
            try {
                s.newestSenderName("INBOX", "x@x.com", window = 0)
                fail("expected IllegalArgumentException")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }
}
