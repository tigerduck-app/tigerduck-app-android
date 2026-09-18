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
        // A Mail2000 bounce: "Mail Deliver System" with no routable address. It must warn exactly
        // as a missing sender already did -- no domain to vouch for it -- and nothing more: the
        // display-name check has no address in the name to disagree with the (absent) sender.
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
