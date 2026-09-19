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
    private fun viewModel(site: MailSite = schoolMailSite()) = MailAccountViewModel(
        MailAccount(InMemoryCredentialStore(), InMemoryMailStateStore(), server.factory(), MailCache(tmp.root),
            FakeDemoGate(), site, RecordingScheduler(), RecordingNotifier(), testApplicationScope()),
        site,
    )

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

    @Test
    fun `sign-out`() {
        val vm = viewModel()
        vm.signIn("b10000001", "pw")
        vm.await()
        vm.signOut()
        assertFalse(vm.signedIn.value)
    }
}
