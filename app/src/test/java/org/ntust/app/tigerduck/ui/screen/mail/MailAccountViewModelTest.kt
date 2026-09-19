package org.ntust.app.tigerduck.ui.screen.mail

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.ntust.app.tigerduck.mail.FakeDemoGate
import org.ntust.app.tigerduck.mail.FakeMailServer
import org.ntust.app.tigerduck.mail.InMemoryCredentialStore
import org.ntust.app.tigerduck.mail.InMemoryMailDevServerStore
import org.ntust.app.tigerduck.mail.InMemoryMailStateStore
import org.ntust.app.tigerduck.mail.MailAccount
import org.ntust.app.tigerduck.mail.MailDevServerSettings
import org.ntust.app.tigerduck.mail.MailEndpoint
import org.ntust.app.tigerduck.mail.MailError
import org.ntust.app.tigerduck.mail.MailSite
import org.ntust.app.tigerduck.mail.MailTransportSecurity
import org.ntust.app.tigerduck.mail.MainDispatcherRule
import org.ntust.app.tigerduck.mail.RecordingNotifier
import org.ntust.app.tigerduck.mail.RecordingScheduler
import org.ntust.app.tigerduck.mail.schoolMailSite
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.mail.testApplicationScope

class MailAccountViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val server = FakeMailServer()

    /**
     * The stored NTUST account. Invented values throughout -- no real password belongs in a
     * fixture any more than in a log line.
     */
    private class FakeNtustAccount(
        override val studentId: String? = null,
        override val password: String? = null,
    ) : NtustAccountSource

    private fun viewModel(
        site: MailSite = schoolMailSite(),
        ntust: NtustAccountSource = FakeNtustAccount(),
    ) = MailAccountViewModel(
        MailAccount(InMemoryCredentialStore(), InMemoryMailStateStore(), server.factory(), MailCache(tmp.root),
            FakeDemoGate(), site, RecordingScheduler(), RecordingNotifier(), testApplicationScope()),
        site,
        ntust,
    )

    /** A device signed in to the NTUST system, which is the case the prefill is for. */
    private fun withNtustAccount() = FakeNtustAccount("b10000001", "sso-secret")

    /** An invented third-party server, as the Developer -> Email override would leave it. */
    private fun overriddenSite(domain: String) = MailSite(
        InMemoryMailDevServerStore(
            MailDevServerSettings(
                enabled = true,
                domain = domain,
                imap = MailEndpoint("imap.probe.example", 143, MailTransportSecurity.NONE),
                smtp = MailEndpoint("smtp.probe.example", 587, MailTransportSecurity.NONE),
            ),
        ),
    )

    private fun MailAccountViewModel.await() = runBlocking { withTimeout(5_000) { signingIn.first { !it } } }

    @Test
    fun `a good sign-in signs in with no error`() {
        val vm = viewModel()
        vm.signIn("b10000001", "pw")
        vm.await()
        assertTrue(vm.signedIn.value)
        assertNull(vm.error.value)
        assertEquals("B10000001", vm.studentId)
    }

    @Test
    fun `a bad password surfaces AuthFailed and stays signed out`() {
        val vm = viewModel()
        vm.signIn("b10000001", "nope")
        vm.await()
        assertFalse(vm.signedIn.value)
        assertTrue(vm.error.value is MailError.AuthFailed)
        vm.clearError()
        assertNull(vm.error.value)
    }

    /**
     * The sign-in field says "student ID" because that is what the school takes -- the app
     * knows the domain and supplies it. Under the override the server wants the whole
     * address, and a bare local part comes back as "Wrong student ID or password", so the
     * field has to say which domain it is about.
     */
    @Test
    fun `the sign-in field starts at the overridden domain, and nowhere else`() {
        assertNull("the school takes a bare ID; nothing may be prefilled there", viewModel().signInAddressSuffix)

        assertEquals("@probe.example", viewModel(overriddenSite("probe.example")).signInAddressSuffix)
        // Whatever was typed on the override screen is normalized the way the address is.
        assertEquals("@probe.example", viewModel(overriddenSite("  PROBE.Example  ")).signInAddressSuffix)
    }

    /**
     * The two logins stay separate (spec §7.1) -- this only saves the typing. The stored ID
     * arrives uppercased, because that is the Mail2000 login name.
     */
    @Test
    fun `the sign-in starts from the stored NTUST account`() {
        val prefill = viewModel(ntust = withNtustAccount()).signInPrefill
        assertEquals("B10000001", prefill.username)
        assertEquals("sso-secret", prefill.password)
    }

    /** Nothing stored, nothing offered -- and certainly no password beside an empty ID. */
    @Test
    fun `with no NTUST account stored the fields start empty`() {
        val prefill = viewModel().signInPrefill
        assertEquals("", prefill.username)
        assertEquals("", prefill.password)
    }

    /**
     * Carve-out 1, the condition the whole prefill exists under: the fields are filled and
     * that is all. No socket opens, no sign-in starts, nothing is saved. A *manual* sign-in
     * the server rejects costs one error message; spec §7.4's stop-everything lockout is
     * about a saved password failing on its own, and auto-submitting would put this
     * straight into that territory.
     */
    @Test
    fun `reading the prefill signs nothing in`() {
        val vm = viewModel(ntust = withNtustAccount())
        repeat(3) { assertEquals("B10000001", vm.signInPrefill.username) }
        assertEquals("no connection may be opened by offering a prefill", 0, server.opens)
        assertFalse(vm.signingIn.value)
        assertFalse(vm.signedIn.value)
        assertNull(vm.error.value)
        assertNull("nothing may be saved as the mail account either", vm.studentId)
    }

    /**
     * Carve-out 2: the stored password belongs to the school and must not be typed into a
     * form pointed at a third-party server -- the rule the Test connection probe already
     * applies to itself. All the override leaves is the address suffix seed it had before.
     */
    @Test
    fun `nothing from the NTUST account is prefilled while the mail server is overridden`() {
        val prefill = viewModel(overriddenSite("probe.example"), withNtustAccount()).signInPrefill
        assertEquals("@probe.example", prefill.username)
        assertEquals("the school's password may not be offered to another server", "", prefill.password)
    }

    /**
     * Carve-out 3 at the account level: the mail account's own saved ID is what re-auth is
     * about, so the NTUST one never replaces it -- and the NTUST password is not offered
     * beside somebody else's login name.
     */
    @Test
    fun `a saved mail ID is never replaced by the NTUST one`() {
        val vm = viewModel(ntust = FakeNtustAccount("b10000002", "sso-secret"))
        vm.signIn("b10000001", "pw")
        vm.await()

        val prefill = vm.signInPrefill
        assertEquals("B10000001", prefill.username)
        assertEquals("a password may only be offered next to the ID it belongs to", "", prefill.password)
    }

    /** Same account on both: the password is the one thing left to fill in, so it is. */
    @Test
    fun `re-auth on the NTUST student's own mailbox offers the stored password`() {
        val vm = viewModel(ntust = withNtustAccount())
        vm.signIn("b10000001", "pw")
        vm.await()

        val prefill = vm.signInPrefill
        assertEquals("B10000001", prefill.username)
        assertEquals("sso-secret", prefill.password)
    }

    /** A password must not be able to reach a log line through a stray string interpolation. */
    @Test
    fun `the prefill does not print its password`() {
        val text = viewModel(ntust = withNtustAccount()).signInPrefill.toString()
        assertTrue(text.contains("B10000001"))
        assertFalse("the password must never be printable", text.contains("sso-secret"))
    }

    @Test
    fun `sign-out`() {
        val vm = viewModel()
        vm.signIn("b10000001", "pw")
        vm.await()
        vm.signOut()
        assertFalse(vm.signedIn.value)
    }
}
