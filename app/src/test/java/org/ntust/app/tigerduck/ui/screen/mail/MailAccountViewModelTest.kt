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
import org.ntust.app.tigerduck.mail.InMemoryMailStateStore
import org.ntust.app.tigerduck.mail.MailAccount
import org.ntust.app.tigerduck.mail.MailError
import org.ntust.app.tigerduck.mail.MainDispatcherRule
import org.ntust.app.tigerduck.mail.RecordingNotifier
import org.ntust.app.tigerduck.mail.RecordingScheduler
import org.ntust.app.tigerduck.mail.store.MailCache

class MailAccountViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val server = FakeMailServer()
    private fun viewModel() = MailAccountViewModel(
        MailAccount(InMemoryCredentialStore(), InMemoryMailStateStore(), server.factory(), MailCache(tmp.root),
            FakeDemoGate(), RecordingScheduler(), RecordingNotifier()),
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

    @Test
    fun `sign-out`() {
        val vm = viewModel()
        vm.signIn("b10000001", "pw")
        vm.await()
        vm.signOut()
        assertFalse(vm.signedIn.value)
    }
}
