package org.ntust.app.tigerduck.mail

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.ntust.app.tigerduck.mail.store.MailCache
import java.io.File

/**
 * The debug-only Developer -> Email override: what it resolves to, what it refuses to
 * resolve to, and what it costs the previous account when it changes.
 */
class MailDevServerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val store = InMemoryMailDevServerStore()
    private val site = MailSite(store)

    private val gmail = MailDevServerSettings(
        enabled = true,
        domain = "gmail.com",
        imap = MailEndpoint("imap.gmail.com", 993, MailTransportSecurity.IMPLICIT_TLS),
        smtp = MailEndpoint("smtp.gmail.com", 587, MailTransportSecurity.STARTTLS),
    )

    // --- resolving -----------------------------------------------------------------------

    @Test
    fun `with nothing stored the school server is what everything sees`() {
        assertNull(site.activeOverride())
        assertEquals(MailServerConfig.NTUST, site.config())
        assertEquals(MailServerConfig.DOMAIN, site.domain())
    }

    @Test
    fun `an override that is stored but switched off changes nothing`() {
        store.settings = gmail.copy(enabled = false)
        assertNull(site.activeOverride())
        assertEquals(MailServerConfig.NTUST, site.config())
    }

    @Test
    fun `an enabled override is what the connections and the address use`() {
        store.settings = gmail
        val config = site.config()
        assertEquals("gmail.com", config.domain)
        assertEquals(MailEndpoint("imap.gmail.com", 993, MailTransportSecurity.IMPLICIT_TLS), config.imap)
        assertEquals(MailEndpoint("smtp.gmail.com", 587, MailTransportSecurity.STARTTLS), config.smtp)
        assertEquals("gmail.com", site.domain())
    }

    @Test
    fun `the typed values are trimmed and the domain lowercased`() {
        store.settings = gmail.copy(domain = "  Example.COM  ", imap = gmail.imap.copy(host = " mail.example.com "))
        assertEquals("example.com", site.config().domain)
        assertEquals("mail.example.com", site.config().imap.host)
    }

    @Test
    fun `a half-typed override is never applied`() {
        val blankHost = gmail.copy(imap = gmail.imap.copy(host = "  "))
        val noPort = gmail.copy(smtp = gmail.smtp.copy(port = 0))
        val absurdPort = gmail.copy(smtp = gmail.smtp.copy(port = 70_000))
        val noDomain = gmail.copy(domain = " ")
        listOf(blankHost, noPort, absurdPort, noDomain).forEach { settings ->
            store.settings = settings
            assertFalse("$settings must not be complete", settings.isComplete)
            assertNull(site.activeOverride())
            assertEquals(MailServerConfig.NTUST, site.config())
        }
    }

    // --- requirement 1: absent from a release build ---------------------------------------

    @Test
    fun `a release-shaped build resolves the school server and never reads the override`() {
        store.settings = gmail
        // The guarded accessor itself, not the UI: `debug` is what BuildConfig.DEBUG feeds it.
        assertNull(site.activeOverride(debug = false))
        assertEquals(MailServerConfig.NTUST, site.config(debug = false))
        assertEquals(MailServerConfig.DOMAIN, site.domain(debug = false))
        // And the store is genuinely not consulted, not merely ignored.
        val exploding = MailSite(object : MailDevServerStore {
            override var settings: MailDevServerSettings
                get() = throw AssertionError("a release build must not read the override")
                set(_) = throw AssertionError("a release build must not write the override")
        })
        assertEquals(MailServerConfig.NTUST, exploding.config(debug = false))
    }

    @Test
    fun `the store a release build is given holds no override and keeps none`() {
        // MailModule hands this one out when BuildConfig.DEBUG is false, so the
        // preferences-backed store is never built there at all.
        val none = MailDevServerStore.None
        none.settings = gmail
        assertEquals(MailDevServerSettings.OFF, none.settings)
        assertEquals(MailServerConfig.NTUST, MailSite(none).config())
    }

    // --- requirement 2: the school host keeps its transport -------------------------------

    @Test
    fun `naming the school host cannot downgrade it`() {
        store.settings = MailDevServerSettings(
            enabled = true,
            domain = MailServerConfig.DOMAIN,
            imap = MailEndpoint("mail.ntust.edu.tw", 143, MailTransportSecurity.NONE),
            smtp = MailEndpoint("MAIL.NTUST.EDU.TW.", 587, MailTransportSecurity.STARTTLS),
        )
        val config = site.config()
        assertEquals(993, config.imap.port)
        assertEquals(MailTransportSecurity.IMPLICIT_TLS, config.imap.security)
        assertEquals(465, config.smtp.port)
        assertEquals(MailTransportSecurity.IMPLICIT_TLS, config.smtp.security)
        // Still the pinned domain, so network_security_config.xml's ntust.edu.tw pins apply.
        assertTrue(config.imap.host.endsWith("ntust.edu.tw"))
    }

    @Test
    fun `any pinned school subdomain gets the same treatment, and other hosts are left alone`() {
        store.settings = gmail.copy(imap = MailEndpoint("imap.ntust.edu.tw", 143, MailTransportSecurity.NONE))
        assertEquals(MailEndpoint("imap.ntust.edu.tw", 993, MailTransportSecurity.IMPLICIT_TLS), site.config().imap)

        store.settings = gmail.copy(imap = MailEndpoint("ntust.edu.tw.evil.example", 143, MailTransportSecurity.NONE))
        assertEquals(MailEndpoint("ntust.edu.tw.evil.example", 143, MailTransportSecurity.NONE), site.config().imap)
    }

    // --- the Angus properties the schemes turn into ---------------------------------------

    @Test
    fun `implicit TLS is imaps with server identity checked, and the school config is unchanged`() {
        val p = MailProperties.imap(MailServerConfig.NTUST)
        assertEquals("imaps", MailProperties.imapProtocol(MailServerConfig.NTUST))
        assertEquals("smtps", MailProperties.smtpProtocol(MailServerConfig.NTUST))
        assertEquals("true", p["mail.imaps.ssl.checkserveridentity"])
        assertEquals("mail.ntust.edu.tw", p["mail.imaps.host"])
        assertEquals("993", p["mail.imaps.port"])
        assertNull("implicit TLS never negotiates an upgrade", p["mail.imaps.starttls.enable"])
        // The socket factory stays the platform default, which is what makes the pins apply.
        assertNull(p["mail.imaps.ssl.socketFactory"])
    }

    @Test
    fun `STARTTLS is required, never merely offered`() {
        store.settings = gmail
        val p = MailProperties.smtp(site.config())
        assertEquals("smtp", MailProperties.smtpProtocol(site.config()))
        assertEquals("true", p["mail.smtp.starttls.enable"])
        assertEquals("true", p["mail.smtp.starttls.required"])
        assertEquals("true", p["mail.smtp.ssl.checkserveridentity"])
    }

    @Test
    fun `no TLS asks for none of it`() {
        store.settings = gmail.copy(imap = MailEndpoint("127.0.0.1", 3143, MailTransportSecurity.NONE))
        val p = MailProperties.imap(site.config())
        assertEquals("imap", MailProperties.imapProtocol(site.config()))
        assertNull(p["mail.imap.starttls.enable"])
        assertNull(p["mail.imap.ssl.checkserveridentity"])
    }

    // --- requirement 3: a change resets everything ----------------------------------------

    @Test
    fun `changing the configuration signs out and clears cache, state and notifications`() = runTest {
        val credentials = InMemoryCredentialStore()
        val state = InMemoryMailStateStore()
        val scheduler = RecordingScheduler()
        val notifier = RecordingNotifier()
        val cache = MailCache(tmp.root)
        val server = FakeMailServer()
        val account = MailAccount(
            credentials, state, server.factory(), cache, FakeDemoGate(), site, scheduler, notifier, testApplicationScope(),
        )
        assertNull(account.signIn("b10000001", "pw"))
        cache.attachmentsDir.mkdirs()
        File(cache.attachmentsDir, "a.pdf").writeText("x")
        assertTrue(state.inboxSeenUidNext > 0)

        assertTrue(MailDevServerController(store, site, account).apply(gmail))

        assertFalse(account.signedIn.value)
        assertNull("the school credentials may not be reused against another server", credentials.mailStudentId)
        assertNull(credentials.mailPassword)
        assertEquals("the seen marker names a UID in the old mailbox", 0L, state.inboxSeenUidNext)
        assertEquals(1, scheduler.cancelled)
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while (File(cache.attachmentsDir, "a.pdf").exists() || notifier.cancelledAll == 0) delay(10)
            }
        }
        assertFalse("cached mail is the old account's", File(cache.attachmentsDir, "a.pdf").exists())
        assertEquals(1, notifier.cancelledAll)
    }

    @Test
    fun `a rejected password does not leave its lockout pointing at the next account`() = runTest {
        val credentials = InMemoryCredentialStore()
        val state = InMemoryMailStateStore()
        val server = FakeMailServer()
        val account = MailAccount(
            credentials, state, server.factory(), MailCache(tmp.root), FakeDemoGate(), site,
            RecordingScheduler(), RecordingNotifier(), testApplicationScope(),
        )
        account.signIn("b10000001", "pw")
        account.onAuthFailure()
        assertTrue(state.authFailed)

        MailDevServerController(store, site, account).apply(gmail)

        // Spec §7.4 stops a rejected password being retried. The lockout is about the
        // credentials, so it goes when they do -- otherwise the test account inherits a
        // block it never earned, and the school password would be the thing retried.
        assertFalse(state.authFailed)
        assertFalse(account.authFailed.value)
        assertNull(credentials.mailPassword)
    }

    @Test
    fun `saving values that resolve to the same server leaves the account alone`() = runTest {
        val credentials = InMemoryCredentialStore()
        val server = FakeMailServer()
        val account = MailAccount(
            credentials, InMemoryMailStateStore(), server.factory(), MailCache(tmp.root), FakeDemoGate(), site,
            RecordingScheduler(), RecordingNotifier(), testApplicationScope(),
        )
        account.signIn("b10000001", "pw")
        val controller = MailDevServerController(store, site, account)

        // Switched off, so however the fields are filled in the effective server is the school's.
        assertFalse(controller.apply(gmail.copy(enabled = false)))
        assertTrue(account.signedIn.value)
        assertEquals("b10000001", credentials.mailStudentId)

        // And applying the same override twice is not a change the second time.
        assertTrue(controller.apply(gmail))
        assertFalse(controller.apply(gmail))
    }

    @Test
    fun `reset goes back to the school server`() = runTest {
        val account = MailAccount(
            InMemoryCredentialStore(), InMemoryMailStateStore(), FakeMailServer().factory(), MailCache(tmp.root),
            FakeDemoGate(), site, RecordingScheduler(), RecordingNotifier(), testApplicationScope(),
        )
        val controller = MailDevServerController(store, site, account)
        controller.apply(gmail)
        assertNotNull(site.activeOverride())
        assertTrue(controller.reset())
        assertNull(site.activeOverride())
        assertEquals(MailServerConfig.NTUST, site.config())
    }

    // --- the account's own identity -------------------------------------------------------

    @Test
    fun `the login name is only uppercased for the school account`() {
        assertEquals("B10000001", MailCredentials(" b10000001 ", "pw").loginName)
        assertEquals("b10000001@mail.ntust.edu.tw", MailCredentials("B10000001", "pw").address)

        val onGmail = MailCredentials(" tester ", "pw", domain = "gmail.com")
        assertEquals("tester", onGmail.loginName)
        assertEquals("tester@gmail.com", onGmail.address)

        // Most servers want the whole address as the login name, so a typed one is kept as is.
        val whole = MailCredentials("Tester@example.com", "pw", domain = "example.com")
        assertEquals("Tester@example.com", whole.loginName)
        assertEquals("Tester@example.com", whole.address)
        assertTrue("password never printed", "pw" !in whole.toString())
    }
}
