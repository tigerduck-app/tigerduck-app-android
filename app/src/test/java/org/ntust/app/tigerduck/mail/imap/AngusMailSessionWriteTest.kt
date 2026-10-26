package org.ntust.app.tigerduck.mail.imap

import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.eclipse.angus.mail.imap.IMAPStore
import org.ntust.app.tigerduck.mail.MailError
import org.ntust.app.tigerduck.mail.MailProperties
import org.ntust.app.tigerduck.mail.MailTestServer
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.Properties
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory

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

    /**
     * The UIDVALIDITY a caller read its UID under is checked against the folder as the write
     * selects it: under any other the UID names another mail, and nothing may be copied or flagged.
     */
    @Test
    fun `move and permanent delete send nothing to a folder selected under another UIDVALIDITY`() {
        server.createFolders("回收筒")
        server.deliver("not this one")
        session().use { s ->
            val page = s.fetchPage("INBOX", null, 10)
            val uid = page.messages.single().uid
            val stale = page.uidValidity + 1

            val moved = runCatching { s.move("INBOX", uid, "回收筒", ownedDeleted = emptySet(), uidValidity = stale) }
            val deleted = runCatching { s.deletePermanently("INBOX", uid, emptySet(), uidValidity = stale) }

            assertTrue(moved.exceptionOrNull() is MailError.FolderChanged)
            assertTrue(deleted.exceptionOrNull() is MailError.FolderChanged)
            assertEquals(listOf("not this one"), s.fetchPage("INBOX", null, 10).messages.map { it.subject })
            // Under the folder's own UIDVALIDITY the same move goes through.
            assertTrue(s.move("INBOX", uid, "回收筒", ownedDeleted = emptySet(), uidValidity = page.uidValidity))
        }
        assertEquals(0, countIn("INBOX"))
        assertEquals(1, countIn("回收筒"))
    }

    @Test
    fun `the search index reads each mail's text, Chinese and HTML alike, and only mail not deleted`() {
        server.deliver("課程公告", body = "期中考\n時間")
        server.deliver("html only", body = "", html = "<p>期末<b>報告</b></p>")
        server.deliver("gone", body = "x")
        session().use { s ->
            val uids = s.undeletedUids("INBOX").sorted()
            assertEquals(3, uids.size)
            s.deletePermanently("INBOX", uids[2], ownedDeleted = emptySet())

            val left = s.undeletedUids("INBOX").sorted()
            assertEquals(uids.take(2), left)
            val texts = s.fetchSearchTexts("INBOX", left)
            assertEquals("期中考 時間", texts.getValue(uids[0]))
            assertEquals("期末報告", texts.getValue(uids[1]))
        }
    }

    @Test
    fun `the search index reads a mail's plain text over its HTML, and none of its attachments`() {
        val mime = Session.getInstance(Properties())
        val message = MimeMessage(mime).apply {
            setFrom(InternetAddress("prof@example.com"))
            setRecipients(Message.RecipientType.TO, server.credentials.address)
            setSubject("期中考", "UTF-8")
            setContent(MimeMultipart("mixed").apply {
                addBodyPart(MimeBodyPart().apply {
                    setContent(MimeMultipart("alternative").apply {
                        addBodyPart(MimeBodyPart().apply { setText("考試時間改到週五", "UTF-8") })
                        addBodyPart(MimeBodyPart().apply { setContent("<p>考試<b>時間</b></p>", "text/html; charset=UTF-8") })
                    })
                })
                addBodyPart(MimeBodyPart().apply {
                    setContent(ByteArray(200_000) { 'A'.code.toByte() }, "application/pdf")
                    fileName = "notes.pdf"
                })
            })
            saveChanges()
        }
        server.deliver(message)
        session().use { s ->
            val uid = s.undeletedUids("INBOX").single()
            assertEquals("考試時間改到週五", s.fetchSearchTexts("INBOX", listOf(uid)).getValue(uid))
        }
    }

    @Test
    fun `a mail longer than the search reads is searched by its start`() {
        server.deliver("長信", body = "開頭 " + "字".repeat(100_000) + " 結尾")
        session().use { s ->
            val uid = s.undeletedUids("INBOX").single()
            val text = s.fetchSearchTexts("INBOX", listOf(uid)).getValue(uid)
            assertTrue(text.startsWith("開頭 字"))
            assertFalse(text.endsWith("結尾"))
        }
    }

    /** [text] in base64, in lines of 72 characters, as some senders wrap it. */
    private fun base64Lines(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)).chunked(72).joinToString("\r\n")

    /** A mail of [headers] and [body], as it arrives: the body's bytes are its `BODY[TEXT]`. */
    private fun deliverRaw(headers: String, body: String) {
        val raw = "From: prof@example.com\r\nTo: ${server.credentials.address}\r\nMIME-Version: 1.0\r\n$headers\r\n\r\n$body"
        server.deliver(MimeMessage(Session.getInstance(Properties()), raw.toByteArray(Charsets.UTF_8).inputStream()))
    }

    /**
     * Whether the cut at [AngusMailSession.SEARCH_TEXT_BYTES] falls inside one of base64's groups
     * of four, in the base64 that starts at [from] in [body].
     */
    private fun cutsAGroup(body: String, from: Int): Boolean {
        val fetched = body.toByteArray(Charsets.UTF_8).copyOf(AngusMailSession.SEARCH_TEXT_BYTES).toString(Charsets.UTF_8)
        return fetched.drop(from).count { it.isLetterOrDigit() || it == '+' || it == '/' } % 4 != 0
    }

    @Test
    fun `a base64 mail cut short is searched by the text before the cut`() {
        val body = base64Lines("開頭 " + "字".repeat(30_000)) + "\r\n"
        assertTrue("the cut falls inside a group of four", cutsAGroup(body, 0))
        deliverRaw("Subject: long\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64", body)

        session().use { s ->
            val uid = s.undeletedUids("INBOX").single()
            assertTrue(s.fetchSearchTexts("INBOX", listOf(uid)).getValue(uid).startsWith("開頭 字字"))
        }
    }

    @Test
    fun `a part cut short keeps the parts before it`() {
        val second = "--b1\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n"
        // Padded until the cut falls inside one of the second part's groups of four.
        val body = (0..3).map { pad ->
            "--b1\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: 8bit\r\n\r\n" +
                "第一段" + " ".repeat(pad) + "\r\n" + second + base64Lines("第二段 " + "字".repeat(30_000)) + "\r\n--b1--\r\n"
        }.first { cutsAGroup(it, it.indexOf(second) + second.length) }
        deliverRaw("Subject: parts\r\nContent-Type: multipart/mixed; boundary=\"b1\"", body)

        session().use { s ->
            val uid = s.undeletedUids("INBOX").single()
            val text = s.fetchSearchTexts("INBOX", listOf(uid)).getValue(uid)
            assertTrue(text, text.startsWith("第一段 第二段 字字"))
        }
    }

    @Test
    fun `a part that will not read keeps the parts before it`() {
        val body = "--b1\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: 8bit\r\n\r\n第一段\r\n" +
            // uuencode with no begin line, which its decoder refuses.
            "--b1\r\nContent-Type: text/plain\r\nContent-Transfer-Encoding: x-uuencode\r\n\r\nnot uuencoded\r\n--b1--\r\n"
        deliverRaw("Subject: parts\r\nContent-Type: multipart/mixed; boundary=\"b1\"", body)

        session().use { s ->
            val uid = s.undeletedUids("INBOX").single()
            assertEquals("第一段", s.fetchSearchTexts("INBOX", listOf(uid)).getValue(uid))
        }
    }

    /** A socket factory that counts the bytes the server sends on every socket it makes. */
    private class CountingSocketFactory : SocketFactory() {
        val received = AtomicLong()

        override fun createSocket(): Socket = object : Socket() {
            override fun getInputStream(): InputStream = object : FilterInputStream(super.getInputStream()) {
                override fun read(): Int = super.read().also { if (it >= 0) received.incrementAndGet() }

                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    super.read(b, off, len).also { if (it > 0) received.addAndGet(it.toLong()) }
            }
        }

        override fun createSocket(host: String, port: Int): Socket =
            createSocket().apply { connect(InetSocketAddress(host, port)) }

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            createSocket().apply {
                bind(InetSocketAddress(localHost, localPort))
                connect(InetSocketAddress(host, port))
            }

        override fun createSocket(host: InetAddress, port: Int): Socket =
            createSocket().apply { connect(InetSocketAddress(host, port)) }

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            createSocket().apply {
                bind(InetSocketAddress(localAddress, localPort))
                connect(InetSocketAddress(address, port))
            }
    }

    /** A session as the app opens one, its connection made by [sockets]. */
    private fun session(sockets: SocketFactory): AngusMailSession {
        val properties = MailProperties.imap(server.config).apply { put("mail.imap.socketFactory", sockets) }
        val mail = Session.getInstance(properties)
        val store = mail.getStore("imap") as IMAPStore
        store.connect(server.config.imap.host, server.config.imap.port, server.credentials.loginName, server.credentials.password)
        return AngusMailSession(store, mail)
    }

    @Test
    fun `the search index reads of a mail's header only what its text needs`() {
        val message = MimeMessage(Session.getInstance(Properties())).apply {
            setFrom(InternetAddress("prof@example.com"))
            setRecipients(Message.RecipientType.TO, server.credentials.address)
            setSubject("長標頭", "UTF-8")
            // Hundreds of kilobytes of header, which a mail can carry and its text has no use for.
            repeat(4_000) { addHeader("X-Padding-$it", "a".repeat(100)) }
            setText("期中考", "UTF-8")
            saveChanges()
        }
        server.deliver(message)
        val sockets = CountingSocketFactory()
        session(sockets).use { s ->
            val uid = s.undeletedUids("INBOX").single()
            val before = sockets.received.get()

            assertEquals("期中考", s.fetchSearchTexts("INBOX", listOf(uid)).getValue(uid))

            val read = sockets.received.get() - before
            assertTrue("read $read bytes", read < 16 * 1024)
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
