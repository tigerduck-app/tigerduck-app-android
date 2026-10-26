package org.ntust.app.tigerduck.ui.screen.mail

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
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
import org.ntust.app.tigerduck.mail.schoolMailSite
import org.ntust.app.tigerduck.mail.store.HeldSendStore
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.mail.testApplicationScope
import org.ntust.app.tigerduck.ui.navigation.LeaveGuard
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The compose screen around a held send (tigerduck-app#555): the bar with Undo while it is held,
 * what Undo says, and the hold ending at once when the app leaves the foreground.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class SchoolMailComposeUndoTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val appScope = testApplicationScope()

    @After
    fun stopTheAppScope() = appScope.cancelAndJoinBlocking()

    private val repo = FakeSchoolMailRepository()
    private lateinit var vm: SchoolMailComposeViewModel

    @Before
    fun setUp() {
        val cache = MailCache(tmp.newFolder("cache"))
        val account = MailAccount(InMemoryCredentialStore(), InMemoryMailStateStore(), FakeMailServer().factory(), cache,
            FakeDemoGate(), schoolMailSite(), RecordingScheduler(), RecordingNotifier(), appScope)
        // A new mail to someone, held for the whole test: the test dispatcher's clock never moves.
        vm = SchoolMailComposeViewModel(
            SavedStateHandle(mapOf("mode" to "NEW", "folder" to "", "uid" to -1L, "to" to "amy@mail.ntust.edu.tw")),
            repo, account, cache, PickedAttachmentReader { null }, SentCopyNotices(account, appScope), main.dispatcher,
            HeldSendStore(File(tmp.root, "held")),
        )
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = false) {
                SchoolMailComposeScreen(onDone = {}, leaveGuard = LeaveGuard(), viewModel = vm)
            }
        }
    }

    @Test
    fun `a held send offers Undo, which takes it back and says so`() {
        composeRule.runOnIdle { vm.send() }

        composeRule.onNodeWithText("Sending…").assertExists()
        composeRule.onNodeWithText("Undo").performClick()

        composeRule.onNodeWithText("Sending canceled. The mail is back in the editor.").assertExists()
        assertTrue(repo.sent.isEmpty())
    }

    @Test
    fun `the app leaving the foreground during the hold sends the mail at once`() {
        composeRule.runOnIdle { vm.send() }

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)

        assertEquals(listOf("amy@mail.ntust.edu.tw"), repo.sent.single().first.to.map { it.address })
    }
}
