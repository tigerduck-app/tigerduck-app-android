package org.ntust.app.tigerduck.ui.screen.mail

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToNode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.auth.session.mailSignOutPlans
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
import org.ntust.app.tigerduck.mail.imap.FolderSelection
import org.ntust.app.tigerduck.mail.index.MailIndexProgress
import org.ntust.app.tigerduck.mail.mailSummary
import org.ntust.app.tigerduck.mail.schoolMailSite
import org.ntust.app.tigerduck.mail.signInNow
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.mail.sync.ExactAlarmAccess
import org.ntust.app.tigerduck.mail.sync.MailChecker
import org.ntust.app.tigerduck.mail.testApplicationScope
import org.ntust.app.tigerduck.notification.SystemPermissions
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * School Mail's list ending in the download's count after a search that may have missed mail, as
 * the iPhone's (tigerduck-app/tigerduck-app#996, #752): not a line above the list, and not under
 * an empty list still loading.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class, qualifiers = "en-w400dp-h800dp")
class SchoolMailSearchFooterTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val appScope = testApplicationScope()

    @After
    fun stopTheAppScope() = appScope.cancelAndJoinBlocking()

    private val repo = FakeSchoolMailRepository()
    private lateinit var list: SchoolMailListViewModel
    private val sentence get() = composeRule.activity.getString(R.string.school_mail_search_index_partial)

    @Before
    fun setUp() {
        val state = InMemoryMailStateStore()
        val server = FakeMailServer().apply { passwords["B00015999"] = "pw" }
        val cache = MailCache(tmp.newFolder("cache"))
        val account = MailAccount(InMemoryCredentialStore(), state, server.factory(), cache,
            FakeDemoGate(), schoolMailSite(), RecordingScheduler(), RecordingNotifier(), appScope)
        assertNull(runBlocking { account.signIn("B00015999", "pw") })
        repo.currentSignIn = { account.signInNow }
        val checker = MailChecker(account, state, server.factory(), RecordingNotifier(), cache) { 0L }
        list = SchoolMailListViewModel(
            repo, account, checker, schoolMailSite(),
            { main.dispatcher.scheduler.currentTime }, SentCopyNotices(account, appScope),
        )
        val noNtustAccount = object : NtustAccountSource {
            override val studentId: String? = null
            override val password: String? = null
        }
        val settings = SchoolMailSettingsViewModel(
            state, RecordingScheduler(), ExactAlarmAccess { false }, account, SystemPermissions(composeRule.activity.application),
        )
        repo.add("INBOX", mailSummary(1, subject = "期中考"), mailSummary(2, subject = "other"))
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = false) {
                SchoolMailScreen(
                    browserPreference = "in_app",
                    onOpenMessage = { _, _ -> },
                    onEditDraft = { _, _ -> },
                    onCompose = {},
                    viewModel = list,
                    accountViewModel = MailAccountViewModel(account, schoolMailSite(), noNtustAccount, mailSignOutPlans(account)),
                    settingsViewModel = settings,
                )
            }
        }
        composeRule.waitForIdle()
        list.selectFolder(FolderSelection.Real("INBOX"))
        composeRule.waitForIdle()
    }

    private fun search(query: String) {
        repo.searchComplete = false
        list.setSearchText(query)
        list.submitSearch()
        composeRule.waitForIdle()
    }

    private fun footer() = composeRule.onNodeWithContentDescription(sentence)

    private fun scrollToFooter() {
        // The list, not the folder chips' row, which scrolls the other way.
        composeRule.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasContentDescription(sentence))
    }

    @Test
    fun `a search that may have missed mail ends in the download's count, not a line above the list`() {
        repo.searchIndexProgress.value = MailIndexProgress(20, 2_000)
        search("期中")

        composeRule.onNodeWithText("期中考").assertExists()
        scrollToFooter()
        footer().assertExists()
        composeRule.onNodeWithText(sentence).assertDoesNotExist()
    }

    @Test
    fun `a search with no matches yet ends in the count too, after its empty state`() {
        repo.searchIndexProgress.value = MailIndexProgress(20, 2_000)
        search("補考")

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.school_mail_empty_title)).assertExists()
        scrollToFooter()
        footer().assertExists()
    }

    @Test
    fun `the count stays away while the empty list loads, under its own spinner`() {
        repo.searchIndexProgress.value = MailIndexProgress(20, 2_000)
        search("補考")
        scrollToFooter()
        footer().assertExists()

        // A write on another screen left the inbox for a fetch, which the way back from it makes.
        repo.loadGate = CompletableDeferred()
        repo.dropCache("INBOX")
        composeRule.waitForIdle()
        list.loadIfNeeded()
        composeRule.waitForIdle()
        assertTrue(list.state.value.loadState is SchoolMailListViewModel.LoadState.Loading)

        footer().assertDoesNotExist()
        repo.loadGate?.complete(Unit)
    }

    @Test
    fun `the count goes once the folders searched are whole`() {
        repo.searchIndexProgress.value = MailIndexProgress(20, 2_000)
        search("期中")
        scrollToFooter()
        footer().assertExists()

        repo.searchIndexWhole.value = setOf("INBOX")
        repo.searchIndexProgress.value = MailIndexProgress(40, 2_000)
        composeRule.waitForIdle()

        footer().assertDoesNotExist()
    }
}
