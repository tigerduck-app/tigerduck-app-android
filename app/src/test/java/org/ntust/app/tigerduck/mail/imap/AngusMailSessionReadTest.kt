package org.ntust.app.tigerduck.mail.imap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.ntust.app.tigerduck.mail.MailCredentials
import org.ntust.app.tigerduck.mail.MailError
import org.ntust.app.tigerduck.mail.MailServerConfig
import org.ntust.app.tigerduck.mail.MailTestServer
import java.io.ByteArrayOutputStream

class AngusMailSessionReadTest {
    @get:Rule val server = MailTestServer()

    private fun session() = AngusMailSessionFactory(server.config).open(server.credentials)

    @Test
    fun `wrong password is an auth failure and a closed port is a network error`() {
        try {
            AngusMailSessionFactory(server.config).open(MailCredentials("b10000001", "wrong"))
            fail("expected AuthFailed")
        } catch (e: MailError.AuthFailed) { /* expected */ }
        try {
            AngusMailSessionFactory(server.config.copy(imapPort = 1)).open(server.credentials)
            fail("expected Network")
        } catch (e: MailError.Network) { /* expected */ }
    }

    @Test
    fun `noop works with no folder open and with one already open`() {
        server.deliver("one")
        session().use { s ->
            s.noop() // no folder open yet -- the throwaway-INBOX-object branch
            s.fetchPage("INBOX", beforeSeq = null, pageSize = 10) // opens INBOX
            s.noop() // an already-open folder -- the NOOP-in-place branch
            // The connection is still fully usable afterward either way.
            assertEquals(1, s.status("INBOX").messages)
        }
    }

    @Test
    fun `folders and status`() {
        server.createFolders("寄件備份匣", "回收筒")
        server.deliver("one")
        server.deliver("two")
        session().use { s ->
            assertTrue(s.listFolders().containsAll(listOf("INBOX", "寄件備份匣", "回收筒")))
            val st = s.status("INBOX")
            assertEquals(2, st.messages)
            assertEquals(2, st.unseen)
            assertTrue(st.uidValidity > 0)
            assertTrue(st.uidNext > 2)
        }
    }

    @Test
    fun `pages are newest first and page back by sequence number`() {
        (1..3).forEach { server.deliver("m$it") }
        session().use { s ->
            val first = s.fetchPage("INBOX", beforeSeq = null, pageSize = 2)
            assertEquals(listOf("m3", "m2"), first.messages.map { it.subject })
            assertEquals(3, first.totalMessages)
            assertEquals(2, first.nextBeforeSeq)
            val second = s.fetchPage("INBOX", beforeSeq = first.nextBeforeSeq, pageSize = 2)
            assertEquals(listOf("m1"), second.messages.map { it.subject })
            assertNull(second.nextBeforeSeq)
        }
    }

    @Test
    fun `summaries decode headers`() {
        server.deliverEml("/mail/multipart-alternative.eml")
        server.deliverEml("/mail/big5-plain.eml")
        server.deliverEml("/mail/mail2000-sample.eml")
        session().use { s ->
            val byId = s.fetchPage("INBOX", null, 10).messages.associateBy { it.messageId }
            val alt = byId.getValue("<alt-1@mail.ntust.edu.tw>")
            assertEquals("中文信件", alt.subject)
            assertEquals("教務處", alt.from?.name)
            assertEquals("office@mail.ntust.edu.tw", alt.from?.address)
            assertFalse(alt.hasAttachments)
            assertEquals("中文", byId.getValue("<big5-1@example.com>").subject)
            val own = byId.getValue("<1789466442.3683193.b10000001@mail.ntust.edu.tw>")
            assertEquals("中文", own.from?.name)
            assertEquals(listOf("b10000001@mail.ntust.edu.tw"), own.replyTo.map { it.address })
        }
    }

    @Test
    fun `fetchSince returns only mail at or after the marker`() {
        server.deliver("old")
        session().use { s ->
            val marker = s.status("INBOX").uidNext
            assertEquals(emptyList<String>(), s.fetchSince("INBOX", marker).map { it.subject })
            server.deliver("new")
            assertEquals(listOf("new"), s.fetchSince("INBOX", marker).map { it.subject })
        }
    }

    @Test
    fun `bodies, charsets, attachments and inline images`() {
        server.deliverEml("/mail/multipart-alternative.eml")
        server.deliverEml("/mail/with-attachment.eml")
        server.deliverEml("/mail/big5-plain.eml")
        server.deliverEml("/mail/related-inline.eml")
        session().use { s ->
            val all = s.fetchPage("INBOX", null, 10).messages.associateBy { it.messageId }

            val alt = s.fetchBody("INBOX", all.getValue("<alt-1@mail.ntust.edu.tw>").uid)
            assertEquals("Hello 中文", alt.plain?.trim())
            assertTrue(alt.html!!.contains("<b>html</b>"))

            val attMsg = all.getValue("<att-1@example.com>")
            assertTrue(attMsg.hasAttachments)
            val att = s.fetchBody("INBOX", attMsg.uid)
            assertEquals("See attached.", att.plain?.trim())
            assertEquals(1, att.attachments.size)
            assertEquals("報告.pdf", att.attachments[0].fileName)
            assertEquals("application/pdf", att.attachments[0].contentType)
            val bytes = ByteArrayOutputStream().also { s.writeAttachment("INBOX", attMsg.uid, att.attachments[0].partId, it) }
            assertEquals("%PDF-1.4\n", bytes.toString(Charsets.US_ASCII.name()))

            assertEquals("中文郵件", s.fetchBody("INBOX", all.getValue("<big5-1@example.com>").uid).plain?.trim())

            val rel = s.fetchBody("INBOX", all.getValue("<rel-1@mail.ntust.edu.tw>").uid)
            assertTrue(rel.attachments.isEmpty())
            assertArrayEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
                rel.inlineImages.getValue("logo@x").bytes,
            )
            assertEquals("image/png", rel.inlineImages.getValue("logo@x").contentType)
        }
    }

    @Test
    fun `reading never marks mail as read, and raw source is the whole message`() {
        server.deliverEml("/mail/with-attachment.eml")
        session().use { s ->
            val uid = s.fetchPage("INBOX", null, 10).messages.single().uid
            s.fetchBody("INBOX", uid)
            val raw = ByteArrayOutputStream().also { s.writeRawSource("INBOX", uid, it) }.toString("UTF-8")
            assertTrue(raw.contains("Subject: report"))
            assertTrue(raw.contains("JVBERi0xLjQK"))
            assertTrue(s.messageSize("INBOX", uid) > 0)
            assertFalse(s.refreshFlags("INBOX", listOf(uid)).getValue(uid).seen)
            assertEquals(1, s.status("INBOX").unseen)
        }
    }

    @Test
    fun `fetchByUids returns the requested mail newest first`() {
        (1..3).forEach { server.deliver("m$it") }
        session().use { s ->
            val uids = s.fetchPage("INBOX", null, 10).messages.map { it.uid }
            assertEquals(listOf("m3", "m1"), s.fetchByUids("INBOX", listOf(uids[2], uids[0])).map { it.subject })
        }
    }

    @Test
    fun `production config keeps TLS on`() {
        assertTrue(MailServerConfig.NTUST.secure)
        assertEquals(993, MailServerConfig.NTUST.imapPort)
        assertEquals(465, MailServerConfig.NTUST.smtpPort)
    }
}
