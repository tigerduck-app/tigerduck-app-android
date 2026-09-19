package org.ntust.app.tigerduck.mail

import android.content.Context
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.mail.warning.MailWarnings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Developer -> Email override: another mail server to run the whole School Mail
 * feature against, so it can be exercised without touching a real school mailbox.
 *
 * Debug builds only -- [MailSite] is the only thing that reads it, and it reads nothing
 * in a release build. Off by default, and while it is off nothing anywhere behaves
 * differently: [MailSite.config] answers [MailServerConfig.NTUST], byte for byte what it
 * answered before this existed.
 */
data class MailDevServerSettings(
    val enabled: Boolean = false,
    /** Replaces `mail.ntust.edu.tw` as the domain the account's own address lives on. */
    val domain: String = MailServerConfig.DOMAIN,
    val imap: MailEndpoint = MailServerConfig.NTUST.imap,
    val smtp: MailEndpoint = MailServerConfig.NTUST.smtp,
) {
    /** Nothing half-typed is ever applied: a blank host would resolve to "connect to nowhere". */
    val isComplete: Boolean get() = domain.isNotBlank() && imap.isComplete && smtp.isComplete

    fun toConfig(): MailServerConfig = MailServerConfig(
        domain = domain.trim().lowercase(),
        imap = imap.schoolSafe(MailServerConfig.NTUST.imap),
        smtp = smtp.schoolSafe(MailServerConfig.NTUST.smtp),
    )

    companion object {
        val OFF = MailDevServerSettings()

        /**
         * A school host keeps the transport spec §1.1 fixes for it -- implicit TLS on 993 and
         * 465 -- however the override spells it.
         *
         * The pins in `network_security_config.xml` are per-domain, so naming the school here
         * is still a pinned connection and an overridden host is simply unpinned (while still
         * requiring full system trust and hostname validation). This closes the other half:
         * the override is a debug-only exception to §1.1 for *other* servers, never a way to
         * reach the real school server over STARTTLS, in the clear, or on 143/587.
         */
        private fun MailEndpoint.schoolSafe(school: MailEndpoint): MailEndpoint {
            val trimmed = copy(host = host.trim())
            return if (MailWarnings.isSchoolDomain(trimmed.host)) {
                trimmed.copy(port = school.port, security = MailTransportSecurity.IMPLICIT_TLS)
            } else {
                trimmed
            }
        }
    }
}

/**
 * Where the override is kept. Its own preferences file, for the reason
 * [org.ntust.app.tigerduck.mail.store.MailStateStore]'s is: no AppPreferences migration
 * may ever touch it.
 */
interface MailDevServerStore {
    var settings: MailDevServerSettings
}

class SharedPrefsMailDevServerStore(context: Context) : MailDevServerStore {
    private val prefs by lazy { context.getSharedPreferences("school_mail_dev_server", Context.MODE_PRIVATE) }

    /** Read on every connection and on every External badge, so the parse is done once. */
    @Volatile private var cached: MailDevServerSettings? = null

    override var settings: MailDevServerSettings
        get() = cached ?: read().also { cached = it }
        set(value) {
            prefs.edit()
                .putBoolean(K_ENABLED, value.enabled)
                .putString(K_DOMAIN, value.domain)
                .putString(K_IMAP_HOST, value.imap.host)
                .putInt(K_IMAP_PORT, value.imap.port)
                .putString(K_IMAP_SECURITY, value.imap.security.name)
                .putString(K_SMTP_HOST, value.smtp.host)
                .putInt(K_SMTP_PORT, value.smtp.port)
                .putString(K_SMTP_SECURITY, value.smtp.security.name)
                .apply()
            cached = value
        }

    private fun read(): MailDevServerSettings {
        val fallback = MailDevServerSettings.OFF
        return MailDevServerSettings(
            enabled = prefs.getBoolean(K_ENABLED, false),
            domain = prefs.getString(K_DOMAIN, null) ?: fallback.domain,
            imap = endpoint(K_IMAP_HOST, K_IMAP_PORT, K_IMAP_SECURITY, fallback.imap),
            smtp = endpoint(K_SMTP_HOST, K_SMTP_PORT, K_SMTP_SECURITY, fallback.smtp),
        )
    }

    private fun endpoint(hostKey: String, portKey: String, securityKey: String, fallback: MailEndpoint) = MailEndpoint(
        host = prefs.getString(hostKey, null) ?: fallback.host,
        port = prefs.getInt(portKey, fallback.port),
        security = prefs.getString(securityKey, null)
            ?.let { name -> MailTransportSecurity.entries.firstOrNull { it.name == name } }
            ?: fallback.security,
    )

    private companion object {
        const val K_ENABLED = "enabled"
        const val K_DOMAIN = "domain"
        const val K_IMAP_HOST = "imap_host"
        const val K_IMAP_PORT = "imap_port"
        const val K_IMAP_SECURITY = "imap_security"
        const val K_SMTP_HOST = "smtp_host"
        const val K_SMTP_PORT = "smtp_port"
        const val K_SMTP_SECURITY = "smtp_security"
    }
}

/**
 * Which mail server the app is pointed at, and which domain its own address lives on.
 *
 * A release build always answers [MailServerConfig.NTUST]: [BuildConfig.DEBUG] is a
 * compile-time constant, so there the override is not hidden but *absent* -- the branch
 * that would read it is removed and no release code path can reach the store. `debug` is
 * a parameter, defaulted to that constant, only so a JVM test can ask for the release
 * answer (`BuildConfig.DEBUG` is always true under unit tests) -- the same shape
 * [SchoolMailAvailability] uses for the same reason.
 */
@Singleton
class MailSite @Inject constructor(private val store: MailDevServerStore) {
    /** The override in force, or null -- which is always the answer in a release build. */
    fun activeOverride(debug: Boolean = BuildConfig.DEBUG): MailDevServerSettings? =
        if (!debug) null else store.settings.takeIf { it.enabled && it.isComplete }

    fun config(debug: Boolean = BuildConfig.DEBUG): MailServerConfig =
        activeOverride(debug)?.toConfig() ?: MailServerConfig.NTUST

    /** The domain the account's own address lives on; also the yardstick the warning rules use. */
    fun domain(debug: Boolean = BuildConfig.DEBUG): String = config(debug).domain
}

/**
 * Applies a change to the Developer -> Email override.
 *
 * Nothing of the old account may survive being pointed somewhere else. The mail cache is
 * keyed by folder and UID with no account dimension, the repository caches the resolved
 * folder list, and the notification seen marker is a bare INBOX UID -- under another
 * mailbox all three name someone else's mail, with live actions attached to it.
 * [MailAccount.signOut] is exactly that wipe (credentials, mail state and its seen
 * markers, the cache tree, posted notifications, the background check), plus the
 * signed-out transition [MailRepository] listens for to drop its resolved folders and
 * close the open session.
 *
 * Signing out is also what keeps spec §7.4 honest across a change: the
 * rejected-password lockout and the credentials it was about are cleared together, so a
 * lockout can neither follow the school password onto the test server nor block a test
 * account that was never rejected.
 */
@Singleton
class MailDevServerController @Inject constructor(
    private val store: MailDevServerStore,
    private val site: MailSite,
    private val account: MailAccount,
) {
    val settings: MailDevServerSettings get() = store.settings

    /**
     * Saves [settings] and, when that changes what the app effectively talks to, resets
     * every trace of the previous account. Returns whether it did.
     *
     * The comparison is on the resolved [MailServerConfig], not the typed values: turning
     * the switch off while the fields still spell out the school server changes nothing,
     * and nothing is what should then happen.
     */
    fun apply(settings: MailDevServerSettings): Boolean {
        val before = site.config()
        store.settings = settings
        if (site.config() == before) return false
        account.signOut()
        return true
    }

    /** Back to the school server. */
    fun reset(): Boolean = apply(MailDevServerSettings.OFF)
}
