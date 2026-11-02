@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck.ui.screen.mail

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.ntust.app.tigerduck.auth.session.SignOutReason
import org.ntust.app.tigerduck.mail.ComposeMode
import org.ntust.app.tigerduck.mail.FakeDemoGate
import org.ntust.app.tigerduck.mail.FakeMailServer
import org.ntust.app.tigerduck.mail.FakeSchoolMailRepository
import org.ntust.app.tigerduck.mail.InMemoryCredentialStore
import org.ntust.app.tigerduck.mail.InMemoryMailStateStore
import org.ntust.app.tigerduck.mail.MailAccount
import org.ntust.app.tigerduck.mail.MainDispatcherRule
import org.ntust.app.tigerduck.mail.RecordingNotifier
import org.ntust.app.tigerduck.mail.RecordingScheduler
import org.ntust.app.tigerduck.mail.SentCopyNotices
import org.ntust.app.tigerduck.mail.cancelAndJoinBlocking
import org.ntust.app.tigerduck.mail.mailSummary
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.schoolMailSite
import org.ntust.app.tigerduck.mail.signInNow
import org.ntust.app.tigerduck.mail.store.HeldSendStore
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.mail.sync.MailChecker
import org.ntust.app.tigerduck.mail.testApplicationScope
import org.ntust.app.tigerduck.ui.screen.mail.SchoolMailComposeViewModel.RecipientSlot
import java.io.File

/**
 * The mail pages drop what the account that left has on them, however soon the next signs in: on a
 * dispatcher that runs nothing until the test says, as a busy main thread would, so a sign-out and
 * a sign-in land together.
 */
class MailPagesFollowTheAccountTest {
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())
    @get:Rule val tmp = TemporaryFolder()

    private val appScope = testApplicationScope()

    @After
    fun stopTheAppScope() = appScope.cancelAndJoinBlocking()

    private val server = FakeMailServer()
    private val state = InMemoryMailStateStore()
    private val repo = FakeSchoolMailRepository()
    private lateinit var account: MailAccount
    private lateinit var cache: MailCache
    private lateinit var notices: SentCopyNotices

    @Before
    fun setUp() {
        cache = MailCache(tmp.newFolder("cache"))
        account = MailAccount(InMemoryCredentialStore(), state, server.factory(), cache,
            FakeDemoGate(), schoolMailSite(), RecordingScheduler(), RecordingNotifier(), appScope)
        runBlocking { account.signIn("b10000001", "pw") }
        notices = SentCopyNotices(account, CoroutineScope(main.dispatcher))
        repo.currentSignIn = { account.signInNow }
    }

    private fun run() = main.dispatcher.scheduler.runCurrent()

    /** Out and back in, as the same student, before anything on the main thread next runs. */
    private fun signOutAndIn() {
        account.signOut(SignOutReason.MAIL)
        runBlocking { account.signIn("b10000001", "pw") }
    }

    @Test
    fun `the list hears a sign-out and a sign-in landing before it runs`() {
        val checker = MailChecker(account, state, server.factory(), RecordingNotifier(), cache) { 0L }
        val list = SchoolMailListViewModel(repo, account, checker, schoolMailSite(), { 0L }, notices)
        repo.add("INBOX", mailSummary(1))
        list.load()
        run()
        assertEquals(listOf(1L), list.state.value.displayed.map { it.uid })

        signOutAndIn()
        run()

        assertEquals(emptyList<Long>(), list.state.value.displayed.map { it.uid })
    }

    private fun compose() = SchoolMailComposeViewModel(
        SavedStateHandle(mapOf("mode" to ComposeMode.NEW.name, "folder" to "", "uid" to -1L, "mailto" to "")),
        repo, account, cache, PickedAttachmentReader { null }, notices, main.dispatcher,
        HeldSendStore(File(tmp.root, "held")),
    )

    @Test
    fun `compose keeps the contacts across a password typed again`() {
        repo.correspondence = listOf(mailSummary(1, from = MailAddress("Someone", "someone@example.com")))
        val compose = compose()
        run()
        compose.updateRecipients(RecipientSlot.TO) { it.typed(emptyList(), "someone") }

        runBlocking { account.signIn("b10000001", "pw") }
        run()

        assertEquals(1, compose.state.value.suggestions(RecipientSlot.TO).size)
    }

    @Test
    fun `compose hears a sign-out and a sign-in landing before it runs`() {
        repo.correspondence = listOf(mailSummary(1, from = MailAddress("Someone", "someone@example.com")))
        val compose = compose()
        run()
        compose.updateRecipients(RecipientSlot.TO) { it.typed(emptyList(), "someone") }
        assertTrue(compose.state.value.suggestions(RecipientSlot.TO).isNotEmpty())

        signOutAndIn()
        run()

        assertEquals(emptyList<MailAddress>(), compose.state.value.suggestions(RecipientSlot.TO))
    }
}
