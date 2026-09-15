package org.ntust.app.tigerduck.mail

/** A school mail account. Only `@mail.ntust.edu.tw` exists here, so the ID is all we ask for. */
data class MailCredentials(val studentId: String, val password: String) {
    /** What IMAP/SMTP `LOGIN` gets: the bare student ID, uppercased like the NTUST sign-in. */
    val loginName: String get() = studentId.trim().uppercase()

    /** The mailbox address, lowercased the way Mail2000 writes it in `From`. */
    val address: String get() = "${studentId.trim().lowercase()}@${MailServerConfig.DOMAIN}"

    override fun toString(): String = "MailCredentials(studentId=$studentId, password=***)"
}
