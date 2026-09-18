package org.ntust.app.tigerduck.mail.warning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.model.MailAttachment
import org.ntust.app.tigerduck.mail.sanitize.MailLink

class MailWarningsTest {
    private fun att(name: String, type: String = "application/octet-stream") =
        MailAttachment(partId = "2", fileName = name, contentType = type, sizeBytes = 10, contentId = null)

    @Test
    fun `school domain means ntust-edu-tw or a subdomain`() {
        assertTrue(MailWarnings.isSchoolDomain("ntust.edu.tw"))
        assertTrue(MailWarnings.isSchoolDomain("MAIL.NTUST.EDU.TW."))
        assertFalse(MailWarnings.isSchoolDomain("ntust.edu.tw.evil.example"))
        assertFalse(MailWarnings.isSchoolDomain("fakentust.edu.tw"))
        assertTrue(MailWarnings.isExternal("x@gmail.com"))
        assertFalse(MailWarnings.isExternal("x@mail.ntust.edu.tw"))
        assertTrue("a missing sender counts as external", MailWarnings.isExternal(null))
    }

    @Test
    fun `external sender and display-name spoofing`() {
        val warnings = MailWarnings.evaluate(
            from = MailAddress("電算中心 <admin@mail.ntust.edu.tw>", "someone@gmail.com"),
            subject = "hi", plainText = "", links = emptyList(), attachments = emptyList(),
        )
        assertEquals(
            listOf(
                MailWarning.ExternalSender("someone@gmail.com"),
                MailWarning.DisplayNameMismatch("admin@mail.ntust.edu.tw", "someone@gmail.com"),
            ),
            warnings,
        )
        assertEquals(
            emptyList<MailWarning>(),
            MailWarnings.evaluate(MailAddress("教務處", "office@mail.ntust.edu.tw"), "hi", "", emptyList(), emptyList()),
        )
    }

    @Test
    fun `a sentence-ending dot after the display-name address is not a mismatch`() {
        val warnings = MailWarnings.evaluate(
            from = MailAddress("Contact admin@mail.ntust.edu.tw.", "admin@mail.ntust.edu.tw"),
            subject = "x", plainText = "", links = emptyList(), attachments = emptyList(),
        )
        assertEquals(emptyList<MailWarning>(), warnings)
    }

    @Test
    fun `a null sender is treated as external with an empty address`() {
        assertEquals(
            listOf(MailWarning.ExternalSender("")),
            MailWarnings.evaluate(from = null, subject = "hi", plainText = "", links = emptyList(), attachments = emptyList()),
        )
    }

    @Test
    fun `a sender kept only for its name is external, and its name is not a mismatch`() {
        // No domain to vouch for it and no Return-Path to say where it came from: it must warn
        // exactly as a missing sender already did, and nothing more -- the display-name check has
        // no address in the name to disagree with the (absent) sender.
        assertTrue("an address-less sender counts as external", MailWarnings.isExternal(""))
        assertEquals(
            listOf(MailWarning.ExternalSender("")),
            MailWarnings.evaluate(
                from = MailAddress("Mail Deliver System", ""),
                subject = "Returned Mail: Hostname cannot be resolved",
                plainText = "", links = emptyList(), attachments = emptyList(),
            ),
        )
    }

    // --- delivery failures (Mail2000 bounces) ---------------------------------------------

    @Test
    fun `only a null reverse-path marks a bounce`() {
        assertTrue(MailWarnings.isBounce("<>"))
        assertTrue("folding whitespace is not part of the value", MailWarnings.isBounce("  <> "))
        assertFalse(MailWarnings.isBounce(null))
        assertFalse(MailWarnings.isBounce(""))
        assertFalse("a real reverse-path is not a bounce", MailWarnings.isBounce("<postmaster@mail.ntust.edu.tw>"))
    }

    @Test
    fun `a Mail2000 bounce is not marked 校外`() {
        // From: "Mail Deliver System" <MAILER-DAEMON> -- no domain -- but Return-Path: <> says the
        // receiving server generated it, so badging it 校外 was simply wrong.
        val daemon = MailAddress("Mail Deliver System", "")
        assertFalse(MailWarnings.isExternalSender(daemon, "<>"))
        assertTrue("without the header it is judged exactly as before", MailWarnings.isExternalSender(daemon, null))
        assertEquals(
            emptyList<MailWarning>(),
            MailWarnings.evaluate(
                from = daemon,
                subject = "Returned Mail: Hostname cannot be resolved",
                plainText = "The original message was received from B11315025@mail.ntust.edu.tw",
                links = emptyList(), attachments = emptyList(), returnPath = "<>",
            ),
        )
    }

    @Test
    fun `the bounce exemption only covers a sender with no domain at all`() {
        assertTrue(MailWarnings.isExternalSender(MailAddress(null, "daemon@gmail.com"), "<>"))
        assertFalse(MailWarnings.isExternalSender(MailAddress(null, "daemon@mail.ntust.edu.tw"), "<>"))
    }

    @Test
    fun `a forged link-carrying bounce still trips the password-bait rule`() {
        // The exemption costs nothing here: password bait fires on keyword && (external ||
        // outsideLink), and the link is the outside one.
        val warnings = MailWarnings.evaluate(
            from = MailAddress("Mail Deliver System", ""),
            subject = "Returned Mail: 帳號停用",
            plainText = "verify", links = listOf(MailLink("verify", "https://evil.example/login")),
            attachments = emptyList(), returnPath = "<>",
        )
        assertTrue(MailWarning.PasswordBait in warnings)
    }

    @Test
    fun `a near miss of the school mail domain is a typo, an exact match or an unrelated host is not`() {
        assertTrue("edj for edu", MailWarnings.isMistypedSchoolMailDomain("mail.ntust.edj.tw"))
        assertTrue("a transposition is two edits", MailWarnings.isMistypedSchoolMailDomain("mail.ntsut.edu.tw"))
        assertTrue(MailWarnings.isMistypedSchoolMailDomain("MAIL.NTUST.EDU.TW2"))
        assertFalse("the real domain is not a typo of itself", MailWarnings.isMistypedSchoolMailDomain("mail.ntust.edu.tw"))
        assertFalse("nor is another real school domain", MailWarnings.isMistypedSchoolMailDomain("ntust.edu.tw"))
        assertFalse(MailWarnings.isMistypedSchoolMailDomain("gmail.com"))
        assertFalse(MailWarnings.isMistypedSchoolMailDomain("mail.ntust.edu.tw.evil.example"))
        assertFalse(MailWarnings.isMistypedSchoolMailDomain(""))
    }

    @Test
    fun `a bounce naming a near-miss address asks about a typo, one naming anywhere else does not`() {
        fun bounce(text: String) = MailWarnings.evaluate(
            from = MailAddress("Mail Deliver System", ""),
            subject = "Returned Mail: Hostname cannot be resolved",
            plainText = text, links = emptyList(), attachments = emptyList(), returnPath = "<>",
        )
        assertTrue(MailWarning.MistypedRecipient in bounce("... <B11315025@mail.ntust.edj.tw>: Hostname cannot be resolved"))
        assertFalse("nothing says a gmail address was meant to be ours", MailWarning.MistypedRecipient in bounce("<someone@gmail.com>: user unknown"))
        assertFalse(MailWarning.MistypedRecipient in bounce("<B11315025@mail.ntust.edu.tw>: mailbox full"))
        assertFalse(
            "without the null reverse-path this is not a bounce at all",
            MailWarning.MistypedRecipient in MailWarnings.evaluate(
                from = MailAddress(null, "x@mail.ntust.edu.tw"), subject = "fyi",
                plainText = "write to B11315025@mail.ntust.edj.tw", links = emptyList(), attachments = emptyList(),
            ),
        )
    }

    @Test
    fun `a display name claiming an address still mismatches when there is no real address`() {
        assertEquals(
            listOf(MailWarning.ExternalSender(""), MailWarning.DisplayNameMismatch("admin@mail.ntust.edu.tw", "")),
            MailWarnings.evaluate(
                from = MailAddress("admin@mail.ntust.edu.tw", ""),
                subject = "x", plainText = "", links = emptyList(), attachments = emptyList(),
            ),
        )
    }

    @Test
    fun `password bait needs a keyword plus an external sender or an outside link`() {
        val internal = MailAddress(null, "cc@mail.ntust.edu.tw")
        val external = MailAddress(null, "x@evil.example")
        val outsideLink = listOf(MailLink("verify", "https://evil.example/login"))
        val schoolLink = listOf(MailLink("portal", "https://www.ntust.edu.tw"))
        assertTrue(MailWarning.PasswordBait in MailWarnings.evaluate(external, "請更新密碼", "", emptyList(), emptyList()))
        assertTrue(MailWarning.PasswordBait in MailWarnings.evaluate(internal, "Mailbox quota", "click", outsideLink, emptyList()))
        assertFalse(MailWarning.PasswordBait in MailWarnings.evaluate(internal, "密碼", "", schoolLink, emptyList()))
        assertFalse(MailWarning.PasswordBait in MailWarnings.evaluate(external, "hello", "", outsideLink, emptyList()))
    }

    @Test
    fun `risky attachments`() {
        assertEquals(RiskReason.RISKY_TYPE, MailWarnings.riskReason("setup.APK", "application/vnd.android.package-archive", ""))
        assertEquals(RiskReason.DOUBLE_EXTENSION, MailWarnings.riskReason("report.pdf.exe", "application/pdf", ""))
        assertEquals(RiskReason.TYPE_MISMATCH, MailWarnings.riskReason("scan.js", "image/png", ""))
        assertEquals(RiskReason.RISKY_TYPE, MailWarnings.riskReason("invoice\u202efdp.html", "text/html", ""))
        assertEquals(RiskReason.PROTECTED_ARCHIVE, MailWarnings.riskReason("files.zip", "application/zip", "解壓縮密碼 1234"))
        assertNull(MailWarnings.riskReason("files.zip", "application/zip", "see attached"))
        assertNull(MailWarnings.riskReason("notes.pdf", "application/pdf", ""))
        assertNull(MailWarnings.riskReason("README", "text/plain", ""))
        assertEquals(RiskReason.RISKY_TYPE, MailWarnings.riskReason("macro.docm. ", "application/octet-stream", ""))

        val warnings = MailWarnings.evaluate(
            MailAddress(null, "a@mail.ntust.edu.tw"), "s", "", emptyList(), listOf(att("a.pdf"), att("b.exe")),
        )
        assertEquals(listOf(MailWarning.RiskyAttachments(listOf("b.exe"))), warnings)
    }

    @Test
    fun `link checks`() {
        val mismatch = MailWarnings.checkLink("https://www.ntust.edu.tw", "https://ntust-login.xyz/a")
        assertTrue(mismatch.mismatch)
        assertEquals("ntust-login.xyz", mismatch.host)
        assertEquals("ntust.edu.tw", mismatch.shownHost)

        assertFalse(MailWarnings.checkLink("www.ntust.edu.tw", "https://moodle2.ntust.edu.tw/x").mismatch)
        assertFalse(MailWarnings.checkLink("click here", "https://anything.example").mismatch)
        assertTrue(MailWarnings.checkLink("x", "http://plain.example").insecure)
        assertTrue(MailWarnings.checkLink("x", "https://xn--ntst-0ra.example").punycode)
        assertTrue(MailWarnings.checkLink("x", "https://ntüst.example").punycode)
        assertTrue(MailWarnings.checkLink("admin@mail.ntust.edu.tw", "mailto:thief@evil.example").mismatch)
        assertFalse(MailWarnings.checkLink("a@b.tw", "mailto:A@B.tw?subject=x").mismatch)
    }

    @Test
    fun `a sentence-ending dot after the mailto link text is not a mismatch`() {
        assertFalse(MailWarnings.checkLink("admin@mail.ntust.edu.tw.", "mailto:admin@mail.ntust.edu.tw").mismatch)
    }
}
