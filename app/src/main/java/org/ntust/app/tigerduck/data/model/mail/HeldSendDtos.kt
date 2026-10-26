package org.ntust.app.tigerduck.data.model.mail

// A mail the compose screen holds or is sending, kept on the phone so the system ending the app
// then does not lose it (mail/store/HeldSendStore). Upgrade-safe rule, as in MailCacheDtos:
// reference fields are nullable, everything else is a primitive. R8 keeps these through the
// existing `data.model.**` keep rule.

/**
 * [sendingSinceMillis] 0 means the mail is held, its send not started; [sourceUid] -1 means it
 * answers or reopens no mail.
 */
data class HeldSendDto(
    val version: Int,
    val owner: String?,
    val savedAtMillis: Long,
    val sendingSinceMillis: Long,
    val mode: String?,
    val sourceFolder: String?,
    val sourceUid: Long,
    val sourceLoaded: Boolean,
    val inReplyTo: String?,
    val references: String?,
    val to: List<String?>?,
    val cc: List<String?>?,
    val bcc: List<String?>?,
    val subject: String?,
    val body: String?,
    val attachments: List<HeldAttachmentDto?>?,
)

/**
 * A file picked on the phone, kept as [copy], the name of its copy beside the record; or one
 * carried over from a mail on the server, named by [folder], [uid] and [partId].
 */
data class HeldAttachmentDto(
    val id: String?,
    val fileName: String?,
    val contentType: String?,
    val sizeBytes: Long,
    val copy: String?,
    val folder: String?,
    val uid: Long,
    val partId: String?,
)
