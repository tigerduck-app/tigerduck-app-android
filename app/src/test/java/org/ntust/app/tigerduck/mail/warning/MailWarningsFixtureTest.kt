package org.ntust.app.tigerduck.mail.warning

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.model.MailAttachment
import org.ntust.app.tigerduck.mail.sanitize.MailLink

/**
 * Drives `app/src/test/resources/mail/warnings.json`, a fixture shared byte-for-byte with the
 * iOS repo (spec §12.4). Never edit that file here — if a case in it looks wrong against spec
 * A.4, fix `MailWarnings` instead of the fixture, or report the disagreement.
 */
class MailWarningsFixtureTest {
    private val root: JsonObject by lazy {
        val stream = requireNotNull(javaClass.getResourceAsStream("/mail/warnings.json")) {
            "missing fixture app/src/test/resources/mail/warnings.json"
        }
        JsonParser.parseString(stream.bufferedReader().readText()).asJsonObject
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeUnless { it.isJsonNull }?.asString

    private fun reasonCode(reason: RiskReason): String = when (reason) {
        RiskReason.DOUBLE_EXTENSION -> "double_extension"
        RiskReason.TYPE_MISMATCH -> "type_mismatch"
        RiskReason.RISKY_TYPE -> "dangerous_extension"
        RiskReason.PROTECTED_ARCHIVE -> "encrypted_archive"
    }

    @Test
    fun `message warnings match the shared fixture`() {
        val failures = mutableListOf<String>()
        for (case in root.getAsJsonArray("messages")) {
            val obj = case.asJsonObject
            val name = obj.get("name").asString
            val subject = obj.get("subject").asString
            val text = obj.get("text").asString
            val from = MailAddress(obj.stringOrNull("from_name"), obj.get("from_address").asString)
            val links = obj.getAsJsonArray("links").map {
                val l = it.asJsonObject
                MailLink(l.get("text").asString, l.get("href").asString)
            }
            // (fileName, contentType) for each attachment, in document order.
            val attachmentDtos = obj.getAsJsonArray("attachments").map {
                val a = it.asJsonObject
                a.get("filename").asString to a.stringOrNull("content_type")
            }
            val attachments = attachmentDtos.mapIndexed { index, (fileName, contentType) ->
                MailAttachment(
                    partId = index.toString(),
                    fileName = fileName,
                    contentType = contentType.orEmpty(),
                    sizeBytes = 0L,
                    contentId = null,
                )
            }

            val warnings = MailWarnings.evaluate(from, subject, text, links, attachments)
            val subjectAndBody = "$subject\n$text"
            // Same predicate `evaluate` used internally to build its RiskyAttachments.fileNames,
            // so this lines up 1:1 (by position) with that list.
            val riskyContentTypes = attachmentDtos
                .filter { (fileName, contentType) -> MailWarnings.riskReason(fileName, contentType.orEmpty(), subjectAndBody) != null }
                .map { it.second }

            val codes = mutableListOf<String>()
            var riskyIndex = 0
            for (warning in warnings) {
                when (warning) {
                    is MailWarning.ExternalSender -> codes += "external_sender:${warning.address}"
                    is MailWarning.DisplayNameMismatch -> codes += "display_name_mismatch:${warning.actualAddress}"
                    MailWarning.PasswordBait -> codes += "password_bait"
                    // No fixture case carries a Return-Path, so this never fires today; it is
                    // here so the code is already agreed on if the shared fixture grows one.
                    MailWarning.MistypedRecipient -> codes += "mistyped_recipient"
                    is MailWarning.RiskyAttachments -> for (fileName in warning.fileNames) {
                        val contentType = riskyContentTypes.getOrNull(riskyIndex).orEmpty()
                        riskyIndex++
                        val reason = checkNotNull(MailWarnings.riskReason(fileName, contentType, subjectAndBody)) {
                            "'$name': '$fileName' is in RiskyAttachments but riskReason is null"
                        }
                        codes += "risky_attachment:$fileName:${reasonCode(reason)}"
                    }
                }
            }

            val expect = obj.getAsJsonArray("expect").map { it.asString }
            if (codes != expect) failures += "[$name] expected=$expect actual=$codes"
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun `link checks match the shared fixture`() {
        val failures = mutableListOf<String>()
        for (case in root.getAsJsonArray("links")) {
            val obj = case.asJsonObject
            val name = obj.get("name").asString
            val verdict = MailWarnings.checkLink(obj.get("text").asString, obj.get("href").asString)

            val codes = mutableListOf<String>()
            if (verdict.insecure) codes += "insecure"
            if (verdict.punycode) codes += "punycode:${verdict.host}"
            if (verdict.mismatch) codes += "mismatch:${verdict.shownHost}:${verdict.host}"

            val expect = obj.getAsJsonArray("expect").map { it.asString }
            if (codes != expect) failures += "[$name] expected=$expect actual=$codes"
        }
        assertEquals(emptyList<String>(), failures)
    }
}
