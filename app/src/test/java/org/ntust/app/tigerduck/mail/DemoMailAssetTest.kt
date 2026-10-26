package org.ntust.app.tigerduck.mail

import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.mail.sanitize.HtmlSanitizer
import org.ntust.app.tigerduck.mail.sanitize.PlainTextLinks
import org.ntust.app.tigerduck.mail.warning.MailWarning
import org.ntust.app.tigerduck.mail.warning.MailWarnings
import java.io.File

/** Spec §7.6: the store-review mailbox shows an HTML mail, an attachment, an outside sender, a phishing warning and a plain-text link. */
class DemoMailAssetTest {
    private val json = File("src/main/assets/demo.json").readText()

    @Test
    fun `the shipped demo mailbox covers every case the reviewers should see`() {
        for (lang in listOf("zh", "en")) {
            val box = DemoMailFixture.parse(json, lang)!!
            assertTrue(box.matches("B11308964", "its-my-duty"))
            val warnings = box.messages.flatMap { m ->
                val html = m.body.html?.let { HtmlSanitizer.sanitize(it, allowRemoteImages = false) }
                val plain = m.body.plain ?: html?.let { HtmlSanitizer.plainText(it.html) }.orEmpty()
                MailWarnings.evaluate(m.summary.from, m.summary.subject, plain, html?.links.orEmpty(), m.body.attachments)
            }
            assertTrue(warnings.any { it is MailWarning.ExternalSender })
            assertTrue(warnings.any { it is MailWarning.PasswordBait })
            assertTrue(box.messages.any { it.body.html != null })
            assertTrue(box.messages.any { it.body.attachments.isNotEmpty() })
            assertTrue(box.messages.any { m -> m.body.html?.let { HtmlSanitizer.sanitize(it, false).blockedRemoteImages > 0 } == true })
            // A plain-text mail with a web link and an address in it, so the plain view's links,
            // and a mailto link opening School Mail compose, can be tried too.
            val plainLinks = box.messages.filter { it.body.html == null }.flatMap { PlainTextLinks.find(it.body.plain.orEmpty()) }
            assertTrue(plainLinks.any { it.href.startsWith("https://") })
            assertTrue(plainLinks.any { it.href.startsWith("mailto:") })
        }
    }
}
