package org.ntust.app.tigerduck.mail

import android.content.Context
import androidx.core.os.ConfigurationCompat
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.debug.DebugFixtureStore
import org.ntust.app.tigerduck.mail.imap.SpecialFolder
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.model.MailAttachment
import org.ntust.app.tigerduck.mail.model.MailBody
import org.ntust.app.tigerduck.mail.model.MailFlags
import org.ntust.app.tigerduck.mail.model.MailSummary
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** [folder] is which of the demo mailbox's folders this lives in -- INBOX unless the fixture says otherwise. */
data class DemoMail(val summary: MailSummary, val body: MailBody, val folder: SpecialFolder = SpecialFolder.INBOX)

/** The store-review mailbox from assets/demo.json's `mail` section. Never touches a socket. */
class DemoMailbox(
    val studentId: String?,
    val password: String?,
    val displayName: String?,
    val messages: List<DemoMail>,
) {
    fun matches(studentId: String, password: String): Boolean {
        val id = this.studentId?.trim()?.uppercase().orEmpty()
        val pw = this.password.orEmpty()
        return id.isNotEmpty() && pw.isNotEmpty() && studentId.trim().uppercase() == id && password == pw
    }

    companion object {
        val EMPTY = DemoMailbox(null, null, null, emptyList())
    }
}

/** Gson tree parsing, like DemoFixture: org.json is a stub under the JVM unit tests. */
object DemoMailFixture {
    fun parse(json: String, lang: String): DemoMailbox? {
        val root = JsonParser.parseString(json).asJsonObject
        val mail = root.get("mail")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val messages = mail.get("messages")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapIndexedNotNull { i, el -> el.takeIf { it.isJsonObject }?.let { parseMessage(it.asJsonObject, i, lang) } }
            .orEmpty()
            .sortedByDescending { it.summary.sentAt }
        return DemoMailbox(
            // The mail demo ID matches the iOS plan (B99999999); the app-wide demo ID is only a fallback.
            studentId = (mail.get("studentId") ?: root.get("studentId"))?.takeIf { it.isJsonPrimitive }?.asString,
            password = mail.get("password")?.takeIf { it.isJsonPrimitive }?.asString,
            displayName = localized(mail, "displayName", lang).ifBlank { null },
            messages = messages,
        )
    }

    private fun parseMessage(o: JsonObject, index: Int, lang: String): DemoMail? {
        val fromObj = o.get("from")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val address = fromObj.get("address")?.asString ?: return null
        val attachments = o.get("attachments")?.takeIf { it.isJsonArray }?.asJsonArray?.mapIndexedNotNull { i, el ->
            val a = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapIndexedNotNull null
            MailAttachment(
                partId = "${i + 2}",
                fileName = localized(a, "name", lang).ifBlank { return@mapIndexedNotNull null },
                contentType = a.get("type")?.asString ?: "application/octet-stream",
                sizeBytes = a.get("size")?.asLong ?: 0L,
                contentId = null,
            )
        }.orEmpty()
        val sentAt = o.get("date")?.asString?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
        val folder = parseFolder(o)
        val summary = MailSummary(
            uid = 1_000L + index,
            from = MailAddress(fromObj.get("name")?.asString, address),
            replyTo = emptyList(), to = parseAddresses(o, "to"), cc = parseAddresses(o, "cc"),
            subject = localized(o, "subject", lang),
            sentAt = sentAt, receivedAt = sentAt,
            // A Drafts-folder entry is flagged \Draft the same way a real IMAP append would be.
            flags = MailFlags.NONE.copy(seen = o.get("seen")?.asBoolean ?: false, draft = folder == SpecialFolder.DRAFTS),
            sizeBytes = 2_048, hasAttachments = attachments.isNotEmpty(),
            messageId = "<demo-$index@${MailServerConfig.DOMAIN}>", inReplyTo = null, references = null,
        )
        val body = MailBody(
            html = localized(o, "html", lang).ifBlank { null },
            plain = localized(o, "plain", lang).ifBlank { null },
            attachments = attachments,
            inlineImages = emptyMap(),
        )
        return DemoMail(summary, body, folder)
    }

    /** Which demo folder a message belongs to; absent or unrecognised means INBOX. */
    private fun parseFolder(o: JsonObject): SpecialFolder =
        when (o.get("folder")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.lowercase()) {
            "drafts", "draft" -> SpecialFolder.DRAFTS
            "sent" -> SpecialFolder.SENT
            "junk" -> SpecialFolder.JUNK
            "trash" -> SpecialFolder.TRASH
            else -> SpecialFolder.INBOX
        }

    private fun parseAddresses(o: JsonObject, key: String): List<MailAddress> =
        o.get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { el ->
            val a = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val address = a.get("address")?.asString ?: return@mapNotNull null
            MailAddress(a.get("name")?.asString, address)
        }.orEmpty()

    /** A plain string or a `{"zh", "en"}` pair, falling back to the other language rather than blank. */
    private fun localized(o: JsonObject, key: String, lang: String): String {
        val el = o.get(key) ?: return ""
        if (el.isJsonPrimitive) return el.asString
        if (!el.isJsonObject) return ""
        val pair = el.asJsonObject
        return pair.get(lang)?.asString ?: pair.get(if (lang == "zh") "en" else "zh")?.asString.orEmpty()
    }
}

interface MailDemoGate {
    /** The whole-app demo account is signed in: no real mail sign-in may reach the server. */
    val appDemoActive: Boolean
    fun matches(studentId: String, password: String): Boolean
    fun mailbox(): DemoMailbox
}

@Singleton
class AssetsMailDemoGate @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val store: DebugFixtureStore,
    private val appPreferences: AppPreferences,
) : MailDemoGate {
    override val appDemoActive: Boolean get() = store.demoMode

    private val box: DemoMailbox by lazy {
        runCatching {
            context.assets.open("demo.json").bufferedReader().use { DemoMailFixture.parse(it.readText(), lang()) }
        }.getOrNull() ?: DemoMailbox.EMPTY
    }

    override fun matches(studentId: String, password: String) = box.matches(studentId, password)

    override fun mailbox(): DemoMailbox = box

    private fun lang(): String {
        val locale = AppLanguageManager.resolveExplicitLocale(appPreferences.appLanguage)
            ?: ConfigurationCompat.getLocales(context.resources.configuration)[0]
        return if (locale?.language == "zh") "zh" else "en"
    }
}
