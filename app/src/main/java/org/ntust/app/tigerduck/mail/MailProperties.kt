package org.ntust.app.tigerduck.mail

import java.util.Properties

/**
 * Angus Mail settings. The TLS socket factory is deliberately left at the
 * platform default: that is what makes network_security_config.xml's
 * ntust.edu.tw pins cover the mail connection (spec §1.4).
 */
object MailProperties {
    @Volatile private var systemPropertiesInstalled = false

    /** MimeUtility reads these from System properties, not from the Session. */
    fun installSystemProperties() {
        if (systemPropertiesInstalled) return
        System.setProperty("mail.mime.decodetext.strict", "false")
        System.setProperty("mail.mime.decodefilename", "true")
        System.setProperty("mail.mime.decodeparameters", "true")
        System.setProperty("mail.mime.encodeparameters", "true")
        systemPropertiesInstalled = true
    }

    fun imapProtocol(config: MailServerConfig) = protocol("imap", config.imap)

    fun smtpProtocol(config: MailServerConfig) = protocol("smtp", config.smtp)

    private fun protocol(base: String, endpoint: MailEndpoint) =
        if (endpoint.security == MailTransportSecurity.IMPLICIT_TLS) "${base}s" else base

    /**
     * The TLS half of a protocol's properties.
     *
     * `starttls.required` as well as `enable`: with only the latter, a server that does
     * not advertise STARTTLS is talked to in the clear instead of refused, which is the
     * one way a STARTTLS setting could end up weaker than it reads. Server identity is
     * checked for both TLS schemes -- an overridden host is not pinned, so hostname
     * validation is the whole of what stands between it and the wrong certificate.
     */
    private fun Properties.putSecurity(p: String, endpoint: MailEndpoint) {
        when (endpoint.security) {
            MailTransportSecurity.IMPLICIT_TLS -> put("$p.ssl.checkserveridentity", "true")
            MailTransportSecurity.STARTTLS -> {
                put("$p.starttls.enable", "true")
                put("$p.starttls.required", "true")
                put("$p.ssl.checkserveridentity", "true")
            }
            MailTransportSecurity.NONE -> Unit
        }
    }

    fun imap(config: MailServerConfig): Properties {
        installSystemProperties()
        val p = "mail.${imapProtocol(config)}"
        return Properties().apply {
            put("$p.host", config.imap.host)
            put("$p.port", config.imap.port.toString())
            put("$p.connectiontimeout", "15000")
            put("$p.timeout", "30000")
            put("$p.writetimeout", "30000")
            // Every read is BODY.PEEK; marking read is always explicit.
            put("$p.peek", "true")
            put("$p.partialfetch", "true")
            put("$p.fetchsize", "16384")
            // Only covers IMAPProtocol's SearchException case (an unformattable
            // search, e.g. an unsupported CHARSET) -- NOT a tagged NO to SEARCH
            // itself, which IMAPFolder.search() unconditionally retries as a
            // full client-side scan of already-fetched message state regardless
            // of this setting (verified against Angus 2.0.5 bytecode). Because
            // of that, AngusMailSession never calls IMAPFolder.search(): it
            // issues its own UID SEARCH via IMAPFolder.doCommand instead, so a
            // NO always throws. Left set here for defense in depth.
            put("$p.throwsearchexception", "true")
            putSecurity(p, config.imap)
        }
    }

    fun smtp(config: MailServerConfig): Properties {
        installSystemProperties()
        val p = "mail.${smtpProtocol(config)}"
        return Properties().apply {
            put("$p.host", config.smtp.host)
            put("$p.port", config.smtp.port.toString())
            put("$p.auth", "true")
            put("$p.connectiontimeout", "15000")
            put("$p.timeout", "60000")
            put("$p.writetimeout", "60000")
            putSecurity(p, config.smtp)
        }
    }
}
