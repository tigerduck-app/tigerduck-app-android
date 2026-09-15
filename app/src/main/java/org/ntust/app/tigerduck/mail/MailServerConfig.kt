package org.ntust.app.tigerduck.mail

/** Where the school mail lives. Tests point this at an in-memory server. */
data class MailServerConfig(
    val host: String,
    val imapPort: Int,
    val smtpPort: Int,
    /** Implicit TLS on both ports. Only tests turn this off. */
    val secure: Boolean,
) {
    companion object {
        const val DOMAIN = "mail.ntust.edu.tw"

        /** IMAP 993 and SMTP 465, both implicit TLS (spec §1.1). Never 25/143/587. */
        val NTUST = MailServerConfig(host = DOMAIN, imapPort = 993, smtpPort = 465, secure = true)
    }
}
