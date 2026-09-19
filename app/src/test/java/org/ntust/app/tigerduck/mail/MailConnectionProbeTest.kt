package org.ntust.app.tigerduck.mail

import com.icegreen.greenmail.util.ServerSetupTest
import jakarta.mail.MessagingException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID

/**
 * Developer -> Email's "Test connection": that each stage is reported on its own, that the
 * raw exception survives to the screen, and what the button refuses to do.
 *
 * Every host and password here is invented. The GreenMail server is plain IMAP/SMTP on
 * loopback, which is what lets "no TLS", "TLS against a server that has none" and a real
 * LOGIN all be exercised without a network.
 */
class MailConnectionProbeTest {
    @get:Rule val server = MailTestServer()

    private val store = InMemoryMailDevServerStore()
    private val site = MailSite(store)
    private val credentials = InMemoryCredentialStore()
    private val probe = SocketMailConnectionProbe(credentials, site)

    /** The invented third-party account the override points at: not the school's, by construction. */
    private val local = MailDevServerSettings(
        enabled = true,
        domain = "probe.example",
        imap = MailEndpoint("127.0.0.1", ServerSetupTest.IMAP.port, MailTransportSecurity.NONE),
        smtp = MailEndpoint("127.0.0.1", ServerSetupTest.SMTP.port, MailTransportSecurity.NONE),
    )

    /** GreenMail's login for the mailbox [MailTestServer] creates, and its password. */
    private fun signedInHere() {
        store.settings = local
        credentials.mailStudentId = server.credentials.loginName
        credentials.mailPassword = server.credentials.password
    }

    private fun String.leg(label: String): String =
        split("\n\n").firstOrNull { it.startsWith(label) } ?: fail("no $label block in:\n$this")

    private fun fail(message: String): Nothing = throw AssertionError(message)

    // --- what it refuses to do ------------------------------------------------------------

    @Test
    fun `an override that is switched off is refused rather than tested`() = runTest {
        val report = probe.test(local.copy(enabled = false))
        assertTrue(report, "switch is off" in report)
        assertFalse("nothing may be probed", "DNS" in report)
    }

    @Test
    fun `a half-typed draft is refused rather than tested`() = runTest {
        val report = probe.test(local.copy(imap = local.imap.copy(host = "  ")))
        assertTrue(report, "Fill in" in report)
        assertFalse("DNS" in report)
    }

    @Test
    fun `the school server is never tested, however the draft spells it`() = runTest {
        val school = local.copy(
            domain = MailServerConfig.DOMAIN,
            imap = MailEndpoint("MAIL.NTUST.EDU.TW.", 143, MailTransportSecurity.NONE),
        )
        val report = probe.test(school)
        assertTrue(report, "Refusing to test ${MailServerConfig.DOMAIN}" in report)
        assertFalse("no socket may be opened towards the school", "DNS" in report)

        // A school SMTP host alone is enough to refuse the whole run, not just that leg.
        val halfSchool = local.copy(smtp = MailEndpoint("mail.ntust.edu.tw", 465, MailTransportSecurity.IMPLICIT_TLS))
        assertTrue("Refusing to test" in probe.test(halfSchool))
    }

    // --- the stages -----------------------------------------------------------------------

    @Test
    fun `a server that answers reports every stage separately, up to an accepted login`() = runTest {
        signedInHere()
        val report = probe.test(local)

        listOf("IMAP", "SMTP").forEach { label ->
            val leg = report.leg(label)
            assertTrue(leg, Regex("""DNS\s+ok\s+127\.0\.0\.1""").containsMatchIn(leg))
            assertTrue(leg, Regex("""TCP\s+ok\s+connected in \d+ ms""").containsMatchIn(leg))
            assertTrue("no TLS was asked for, so it is skipped and said so", Regex("""TLS\s+skipped""").containsMatchIn(leg))
            assertTrue("the greeting proves a mail server answered", "greeting:" in leg)
            assertTrue(leg, Regex("""AUTH\s+ok\s+accepted""").containsMatchIn(leg))
        }
        // The greeting is the server's own, read off the wire rather than assumed.
        assertTrue(report, "* OK" in report.leg("IMAP"))
        assertTrue(report, "220" in report.leg("SMTP"))
    }

    @Test
    fun `a rejected password is an AUTH failure carrying the jakarta exception, not a network one`() = runTest {
        signedInHere()
        credentials.mailPassword = "not-the-password"
        val leg = probe.test(local).leg("IMAP")

        assertTrue(leg, Regex("""AUTH\s+AUTH failed""").containsMatchIn(leg))
        assertTrue("the exception class is the answer", "jakarta.mail.AuthenticationFailedException" in leg)
        // The stages before it must still read as healthy, or the report points nowhere.
        assertTrue(leg, Regex("""TCP\s+ok""").containsMatchIn(leg))
    }

    @Test
    fun `a port with nothing behind it is TCP refused, with the platform exception`() = runTest {
        val closed = ServerSocket(0).use { it.localPort }
        val report = probe.test(local.copy(imap = local.imap.copy(port = closed)))
        val leg = report.leg("IMAP")

        assertTrue(leg, Regex("""TCP\s+TCP refused""").containsMatchIn(leg))
        assertTrue(leg, "java.net.ConnectException" in leg)
        assertFalse("a refused port never reaches TLS or AUTH", "AUTH" in leg)
        // The other leg is probed regardless: one broken port must not hide the other.
        assertTrue(report, Regex("""TCP\s+ok""").containsMatchIn(report.leg("SMTP")))
    }

    @Test
    fun `a name that does not resolve is DNS failed and stops there`() = runTest {
        val nowhere = "probe-${UUID.randomUUID()}.invalid"
        val leg = probe.test(local.copy(imap = local.imap.copy(host = nowhere))).leg("IMAP")

        assertTrue(leg, Regex("""DNS\s+DNS failed""").containsMatchIn(leg))
        assertTrue(leg, "java.net.UnknownHostException" in leg)
        assertFalse("nothing may be dialled once the name is dead", "TCP" in leg)
    }

    @Test
    fun `TLS asked of a server that has none fails at TLS and never tries the password`() = runTest {
        signedInHere()
        val leg = probe.test(local.copy(imap = local.imap.copy(security = MailTransportSecurity.IMPLICIT_TLS)))
            .leg("IMAP")

        assertTrue(leg, Regex("""TCP\s+ok""").containsMatchIn(leg))
        assertTrue(leg, Regex("""TLS\s+TLS failed""").containsMatchIn(leg))
        assertTrue("the handshake exception is shown as it was thrown", "javax.net.ssl.SSL" in leg)
        assertFalse("a password is never offered over a connection that is not secured", "AUTH" in leg)
    }

    // --- what it will not send, and where ---------------------------------------------------

    @Test
    fun `with no password stored the connection is still tested and AUTH says it was not tried`() = runTest {
        store.settings = local
        val leg = probe.test(local).leg("IMAP")

        assertTrue(leg, Regex("""TCP\s+ok""").containsMatchIn(leg))
        assertTrue(leg, "no password is stored" in leg)
        assertFalse("not attempted is not a failure", "AUTH failed" in leg)
    }

    @Test
    fun `the stored password is never offered to a host it was not typed for`() = runTest {
        // Signed in to the school, testing a third-party draft: the school password may not
        // travel to someone else's server for the sake of a diagnostic.
        credentials.mailStudentId = "b10000001"
        credentials.mailPassword = "school-password"
        val leg = probe.test(local).leg("IMAP")

        assertTrue(leg, Regex("""TCP\s+ok""").containsMatchIn(leg))
        assertTrue(leg, "the stored password was typed for ${MailServerConfig.DOMAIN}" in leg)
        assertFalse("nothing was sent, so nothing can have been accepted", "accepted" in leg)
    }

    @Test
    fun `nothing in the report has been through MailError`() = runTest {
        signedInHere()
        credentials.mailPassword = "not-the-password"
        val report = probe.test(local)

        assertFalse(report, "MailError" in report)
        assertFalse(report, "authentication failed" in report)
        assertFalse(report, "network error" in report)
    }

    // --- a release build ----------------------------------------------------------------------

    @Test
    fun `the probe a release build is given tests nothing`() = runTest {
        // MailModule hands this one out when BuildConfig.DEBUG is false, so the real probe
        // and every stage name in it are never built into the APK.
        val report = MailConnectionProbe.Unavailable.test(local)
        assertEquals("Debug builds only.", report)
    }

    // --- telling a refusal from a silence ------------------------------------------------------

    @Test
    fun `refused and timed out are told apart by what the platform threw`() {
        assertEquals(MailProbeVerdict.TCP_TIMED_OUT, probeTcpVerdict(SocketTimeoutException("connect timed out")))
        assertEquals(
            "the kernel giving up first arrives as ConnectException, not SocketTimeoutException",
            MailProbeVerdict.TCP_TIMED_OUT,
            probeTcpVerdict(ConnectException("failed to connect to /10.0.0.1 (port 993): ETIMEDOUT (Connection timed out)")),
        )
        assertEquals(
            MailProbeVerdict.TCP_REFUSED,
            probeTcpVerdict(ConnectException("failed to connect to /10.0.0.1 (port 993): ECONNREFUSED (Connection refused)")),
        )
        assertEquals(MailProbeVerdict.TCP_REFUSED, probeTcpVerdict(ConnectException("Connection refused")))
        // Anything unrecognised still prints its own exception rather than being guessed at.
        assertEquals(MailProbeVerdict.FAILED, probeTcpVerdict(UnknownHostException("nope")))
    }

    @Test
    fun `the chain printed is the one closest to the wire, including Angus's next exception`() {
        val wire = ConnectException("ECONNREFUSED (Connection refused)")
        val chain = probeRawChain(MessagingException("Connect failed", wire))

        assertTrue(chain.toString(), chain.first().startsWith("jakarta.mail.MessagingException: Connect failed"))
        assertTrue(chain.toString(), chain.any { it == "caused by java.net.ConnectException: ECONNREFUSED (Connection refused)" })
        // Angus splices the nested exception into its own message over three lines; a stage's
        // block stays one line per link regardless.
        assertTrue(chain.toString(), chain.none { "\n" in it })
    }

    @Test
    fun `a cause that points back at itself does not loop`() {
        val outer = IOException("outer")
        val inner = object : IOException("inner") {
            override val cause: Throwable? get() = outer
        }
        outer.initCause(inner)
        assertEquals(2, probeRawChain(outer).size)
    }
}
