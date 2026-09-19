package org.ntust.app.tigerduck.mail

/** How a mail connection is secured. */
enum class MailTransportSecurity {
    /** TLS from the first byte -- the only scheme the school server offers (spec §1.1). */
    IMPLICIT_TLS,

    /** Plain connect, then a *mandatory* `STARTTLS` upgrade before any credential is sent. */
    STARTTLS,

    /** No TLS at all. Only a test server or the debug-only override ever asks for this. */
    NONE,
}

/** One end of the mail connection: where to connect and how to secure it. */
data class MailEndpoint(val host: String, val port: Int, val security: MailTransportSecurity) {
    /** A half-typed endpoint is never applied; see [MailDevServerSettings.isComplete]. */
    val isComplete: Boolean get() = host.isNotBlank() && port in 1..65535
}

/**
 * Where the school mail lives. Tests point this at an in-memory server, and in a debug
 * build the Developer -> Email override points it at another server entirely
 * ([MailSite]) -- it is the one seam the override travels through, [domain] included,
 * so nothing downstream needs a second parameter for "which account is this".
 */
data class MailServerConfig(
    /** The domain the account's own address lives on -- what `@` is followed by. */
    val domain: String,
    val imap: MailEndpoint,
    val smtp: MailEndpoint,
) {
    companion object {
        const val DOMAIN = "mail.ntust.edu.tw"

        /** IMAP 993 and SMTP 465, both implicit TLS (spec §1.1). Never 25/143/587. */
        val NTUST = MailServerConfig(
            domain = DOMAIN,
            imap = MailEndpoint(DOMAIN, 993, MailTransportSecurity.IMPLICIT_TLS),
            smtp = MailEndpoint(DOMAIN, 465, MailTransportSecurity.IMPLICIT_TLS),
        )
    }
}

/**
 * The server to use, asked for once per connection rather than captured once.
 *
 * The session factory and the SMTP transport are singletons, so a config read at
 * construction would outlive a change to it -- and the debug override can change while
 * the process runs. Resolving per connection is what lets it take effect without a
 * restart; everything already open is closed by the sign-out the change forces
 * ([MailDevServerController]).
 */
fun interface MailServerConfigSource {
    fun current(): MailServerConfig
}
