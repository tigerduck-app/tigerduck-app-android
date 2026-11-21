package org.ntust.app.tigerduck.ui.screen.mail

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.mail.index.MailIndexProgress
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The end of the mail list: how far the download for search has come, after a search that may have
 * missed mail, as the iPhone's (tigerduck-app/tigerduck-app#996, #752).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en")
class MailListFooterTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val sentence get() = composeRule.activity.getString(R.string.school_mail_search_index_partial)
    private val progress = MutableStateFlow<MailIndexProgress?>(null)

    /** Whether the search on screen may have missed mail; a test changes it while the footer stays composed. */
    private var partial by mutableStateOf(true)

    private fun show() {
        composeRule.setContent { MailListFooter(progress, partial, busy = false) }
        composeRule.waitForIdle()
    }

    @Test
    fun `while mail downloads it says how far, which TalkBack reads as the sentence`() {
        progress.value = MailIndexProgress(20, 2_000)
        show()

        composeRule.onNodeWithContentDescription(sentence).assertExists()
        // The count is drawn, not read: TalkBack hears the sentence it stands for.
        composeRule.onNodeWithText("20/2,000").assertDoesNotExist()
    }

    @Test
    fun `it goes once the search no longer may have missed mail, though the download goes on`() {
        progress.value = MailIndexProgress(20, 2_000)
        show()
        composeRule.onNodeWithContentDescription(sentence).assertExists()

        partial = false
        progress.value = MailIndexProgress(30, 2_000)
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(sentence).assertDoesNotExist()
    }

    @Test
    fun `nothing after a search that missed nothing, nor with no download to tell of`() {
        progress.value = MailIndexProgress(20, 2_000)
        partial = false
        show()
        composeRule.onNodeWithContentDescription(sentence).assertDoesNotExist()

        partial = true
        progress.value = null
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(sentence).assertDoesNotExist()
    }

    @Test
    fun `the count, while the download is past counting, stopped or not`() {
        assertEquals("20/2,000", footerText(MailIndexProgress(20, 2_000), spins = true, sentence = "partial"))
        assertEquals("20/2,000", footerText(MailIndexProgress(20, 2_000, running = false), spins = false, sentence = "partial"))
    }

    @Test
    fun `while it counts only the spinner, and stopped before a count the sentence`() {
        assertNull(footerText(MailIndexProgress.COUNTING, spins = true, sentence = "partial"))
        assertEquals("partial", footerText(MailIndexProgress.COUNTING.copy(running = false), spins = false, sentence = "partial"))
    }

    @Test
    fun `it spins while a page is on its way or the download runs, and not once the download stopped`() {
        assertTrue(footerSpins(null, busy = true))
        assertTrue(footerSpins(MailIndexProgress.COUNTING, busy = false))
        assertTrue(footerSpins(MailIndexProgress(20, 2_000), busy = false))
        assertFalse(footerSpins(MailIndexProgress(20, 2_000, running = false), busy = false))
        assertFalse(footerSpins(null, busy = false))
    }
}
