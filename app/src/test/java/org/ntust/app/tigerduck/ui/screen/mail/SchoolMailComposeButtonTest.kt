package org.ntust.app.tigerduck.ui.screen.mail

import android.app.Application
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.requestFocus
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
import org.ntust.app.tigerduck.mail.mailSummary
import org.ntust.app.tigerduck.mail.schoolMailSite
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.mail.sync.MailChecker
import org.ntust.app.tigerduck.mail.testApplicationScope
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * School Mail's compose button: a round floating button at the bottom end of the page, which in
 * the app is just above the tab bar, as iOS floats it (tigerduck-app/tigerduck-app#864).
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class, qualifiers = "en")
class SchoolMailComposeButtonTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val appScope = testApplicationScope()

    @After
    fun stopTheAppScope() = appScope.cancelAndJoinBlocking()

    private val repo = FakeSchoolMailRepository()
    private var composes = 0

    private val density get() = RuntimeEnvironment.getApplication().resources.displayMetrics.density

    private fun show(signedIn: Boolean = true) {
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
                    onCompose = { composes++ },
                    onOpenGuide = {},
                    viewModel = list,
                    accountViewModel = accountViewModel,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private val composeLabel get() = RuntimeEnvironment.getApplication().getString(org.ntust.app.tigerduck.R.string.school_mail_compose)

    private fun composeButton(): SemanticsNodeInteraction = composeRule.onNodeWithContentDescription(composeLabel)

    private fun bounds(node: SemanticsNodeInteraction): Rect = node.fetchSemanticsNode().boundsInRoot

    /** The keyboard up, or down again, as the system reports it to the page. */
    private fun keyboard(shown: Boolean) {
        val height = (300 * density).toInt()
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), if (shown) Insets.of(0, 0, 0, height) else Insets.NONE)
            .setVisible(WindowInsetsCompat.Type.ime(), shown)
            .build()
        val content = composeRule.activity.findViewById<View>(android.R.id.content)
        composeRule.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(content, insets) }
        composeRule.waitForIdle()
    }

    @Test
    fun `compose floats at the bottom end of the page and writes a new mail`() {
        repo.add("INBOX", mailSummary(1), mailSummary(2))
        show()

        val page = bounds(composeRule.onRoot())
        val button = bounds(composeButton())
        // 16dp in from the end and from the bottom, which in the app is the top of the tab bar.
        assertEquals(page.right - 16 * density, button.right, 1f)
        assertEquals(page.bottom - 16 * density, button.bottom, 1f)
        // A floating button's own 56dp each way, not an icon button's 48dp.
        assertEquals(56 * density, button.width, 1f)
        assertEquals(56 * density, button.height, 1f)

        composeButton().assertHasClickAction().performClick()
        assertEquals(1, composes)
    }

    @Test
    fun `the last row scrolls clear of the button`() {
        repo.add("INBOX", *(1L..20L).map { mailSummary(it) }.toTypedArray())
        show()

        // Newest first, so uid 1 is the last row; the list's own last item is the room after it.
        // The folder chips scroll too, sideways.
        val list = composeRule.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText("subject 20")))
        list.performScrollToIndex(list.fetchSemanticsNode().config[SemanticsProperties.CollectionInfo].rowCount - 1)
        composeRule.waitForIdle()

        val lastRow = bounds(composeRule.onNode(hasText("subject 1")))
        val button = bounds(composeButton())
        assertTrue("the last row ends at ${lastRow.bottom}, under the button from ${button.top}", lastRow.bottom <= button.top)
    }

    @Test
    fun `it steps aside while the search field holds the keyboard and comes back once it closes`() {
        repo.add("INBOX", mailSummary(1))
        show()

        composeRule.onNode(hasSetTextAction()).requestFocus()
        keyboard(shown = true)
        composeButton().assertDoesNotExist()

        // Closing the keyboard leaves the field focused on Android; the button comes back anyway.
        keyboard(shown = false)
        composeRule.onNode(hasSetTextAction()).assertIsFocused()
        composeButton().assertExists()
    }

    @Test
    fun `it stays on an empty list`() {
        show()

        composeButton().assertHasClickAction()
    }

    @Test
    fun `signed out, there is nothing to compose`() {
        show(signedIn = false)

        composeButton().assertDoesNotExist()
    }
}
