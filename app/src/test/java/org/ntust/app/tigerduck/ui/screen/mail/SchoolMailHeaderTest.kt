package org.ntust.app.tigerduck.ui.screen.mail

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
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
import org.ntust.app.tigerduck.mail.schoolMailSite
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.mail.sync.MailChecker
import org.ntust.app.tigerduck.mail.testApplicationScope
import org.ntust.app.tigerduck.ui.screen.TabHeaderChecks.assertHeaderAtTop
import org.ntust.app.tigerduck.ui.screen.TabHeaderChecks.assertHeaderScrollsAway
import org.ntust.app.tigerduck.ui.screen.TabHeaderChecks.assertRowEndsWith
import org.ntust.app.tigerduck.ui.screen.TabHeaderChecks.assertSitsLeftOf
import org.ntust.app.tigerduck.ui.screen.TabHeaderChecks.title
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The header in a chrome overlay, which Announcements shares: above the list rather than in it,
 * hiding as the list scrolls. Signed out, School Mail's page is a plain column with a bare title.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class, qualifiers = "en-w400dp-h600dp")
class SchoolMailHeaderTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val appScope = testApplicationScope()

    @After
    fun stopTheAppScope() = appScope.cancelAndJoinBlocking()

    private val repo = FakeSchoolMailRepository()
    private val density get() = composeRule.activity.resources.displayMetrics.density
    private fun text(id: Int) = composeRule.activity.getString(id)

    private fun show(signedIn: Boolean) {
        val server = FakeMailServer().apply { passwords["B00015999"] = "pw" }
        val state = InMemoryMailStateStore()
        val cache = MailCache(tmp.newFolder("cache"))
        val account = MailAccount(InMemoryCredentialStore(), state, server.factory(), cache,
            FakeDemoGate(), schoolMailSite(), RecordingScheduler(), RecordingNotifier(), appScope)
        if (signedIn) assertNull(runBlocking { account.signIn("B00015999", "pw") })
        repo.accountGeneration = { account.accountGeneration }
        val checker = MailChecker(account, state, server.factory(), RecordingNotifier(), cache) { 0L }
        val list = SchoolMailListViewModel(
            repo, account, checker, schoolMailSite(),
            { main.dispatcher.scheduler.currentTime }, SentCopyNotices(account, appScope),
        )
        val noNtustAccount = object : NtustAccountSource {
            override val studentId: String? = null
            override val password: String? = null
        }
        val accountViewModel = MailAccountViewModel(account, schoolMailSite(), noNtustAccount)
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = false) {
                SchoolMailScreen(
                    browserPreference = "in_app",
                    onOpenMessage = { _, _ -> },
                    onEditDraft = { _, _ -> },
                    onCompose = {},
                    onOpenGuide = {},
                    viewModel = list,
                    accountViewModel = accountViewModel,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `signed in, the title, dot, unread filter and guide share the row, in that order`() {
        repo.add("INBOX", *(1L..30L).map { mailSummary(it) }.toTypedArray())
        show(signedIn = true)
        val title = text(R.string.feature_school_mail)
        composeRule.title(title).assertIsDisplayed()
        composeRule.assertHeaderAtTop(title, density)
        val dot = composeRule.onNodeWithContentDescription(text(R.string.sync_status_ok)).assertIsDisplayed()
        val filter = composeRule.onNodeWithContentDescription(text(R.string.school_mail_unread_only)).assertIsDisplayed()
        val guide = composeRule.onNodeWithContentDescription(text(R.string.school_mail_use_other_app)).assertIsDisplayed()
        composeRule.assertSitsLeftOf(composeRule.title(title), dot)
        composeRule.assertSitsLeftOf(dot, filter)
        composeRule.assertSitsLeftOf(filter, guide)
        composeRule.assertRowEndsWith(guide, density, targetMargin = 4f)
    }

    @Test
    fun `signed in, the header hides as the list scrolls`() {
        repo.add("INBOX", *(1L..30L).map { mailSummary(it) }.toTypedArray())
        show(signedIn = true)
        composeRule.assertHeaderScrollsAway(text(R.string.feature_school_mail), density)
    }

    @Test
    fun `signed out, the title sits alone at the top`() {
        show(signedIn = false)
        val title = text(R.string.feature_school_mail)
        composeRule.title(title).assertIsDisplayed()
        composeRule.assertHeaderAtTop(title, density)
        composeRule.onNodeWithContentDescription(text(R.string.school_mail_unread_only)).assertDoesNotExist()
    }
}
