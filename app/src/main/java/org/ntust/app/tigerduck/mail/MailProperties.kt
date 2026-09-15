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

    fun imapProtocol(config: MailServerConfig) = if (config.secure) "imaps" else "imap"

    fun smtpProtocol(config: MailServerConfig) = if (config.secure) "smtps" else "smtp"

    fun imap(config: MailServerConfig): Properties {
        installSystemProperties()
        val p = "mail.${imapProtocol(config)}"
        return Properties().apply {
            put("$p.host", config.host)
            put("$p.port", config.imapPort.toString())
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
            if (config.secure) put("$p.ssl.checkserveridentity", "true")
        }
    }

    fun smtp(config: MailServerConfig): Properties {
        installSystemProperties()
        val p = "mail.${smtpProtocol(config)}"
        return Properties().apply {
            put("$p.host", config.host)
            put("$p.port", config.smtpPort.toString())
            put("$p.auth", "true")
            put("$p.connectiontimeout", "15000")
            put("$p.timeout", "60000")
            put("$p.writetimeout", "60000")
            if (config.secure) put("$p.ssl.checkserveridentity", "true")
        }
    }
}
