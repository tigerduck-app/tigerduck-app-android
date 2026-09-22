package org.ntust.app.tigerduck.mail

/**
 * A mail account. On the school server only `@mail.ntust.edu.tw` exists, so the ID is all
 * we ask for; the debug-only Developer -> Email override is the one thing that puts
 * another [domain] here, and it is also the only case where the ID may be a whole address.
 */
data class MailCredentials(
    val studentId: String,
    val password: String,
    val domain: String = MailServerConfig.DOMAIN,
) {
    private val id: String get() = studentId.trim()

    /** True while this is the school mailbox -- the only case any of the rules below bend for. */
    private val isSchool: Boolean get() = domain.trim().equals(MailServerConfig.DOMAIN, ignoreCase = true)

    /**
     * What IMAP/SMTP `LOGIN` gets. On the school server that is the bare student ID,
     * uppercased like the NTUST sign-in; anywhere else the login name is whatever was
     * typed, because uppercasing it is a Mail2000 convention and every other server takes
     * it literally.
     */
    val loginName: String get() = if (isSchool) id.uppercase() else id

    /**
     * The mailbox address. On the school server, the ID lowercased the way Mail2000 writes
     * it in `From`; under the override the ID may already be a whole address, in which case
     * that is the address.
     */
    val address: String get() = when {
        isSchool -> "${id.lowercase()}@${MailServerConfig.DOMAIN}"
        '@' in id -> id
        else -> "$id@${domain.trim().lowercase()}"
    }

    override fun toString(): String = "MailCredentials(studentId=$studentId, password=***)"
}
