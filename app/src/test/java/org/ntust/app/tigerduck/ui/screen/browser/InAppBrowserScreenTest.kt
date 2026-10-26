package org.ntust.app.tigerduck.ui.screen.browser

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.browser.BrowserAppearance
import org.ntust.app.tigerduck.browser.BrowserDownload
import org.ntust.app.tigerduck.browser.CertificateInfo
import org.ntust.app.tigerduck.browser.UploadSource
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.util.replaceIosArg
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** The TigerDuck browser's screen, layout A, around a stand-in page. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class InAppBrowserScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val calls = mutableListOf<String>()

    private val actions = object : BrowserActions() {
        override fun close() { calls += "close" }
        override fun back() { calls += "back" }
        override fun reload() { calls += "reload" }
        override fun stop() { calls += "stop" }
        override fun share(url: String) { calls += "share $url" }
        override fun copy(url: String) { calls += "copy $url" }
        override fun zoomIn() { calls += "zoomIn" }
        override fun setAppearance(appearance: BrowserAppearance) { calls += "appearance $appearance" }
        override fun setDesktopSite(desktop: Boolean) { calls += "desktop $desktop" }
        override fun leaveFor(url: String) { calls += "leave $url" }
        override fun stayHere() { calls += "stay" }
        override fun retry() { calls += "retry" }
        override fun openCurrentExternal() { calls += "external" }
        override fun signInAgain() { calls += "signIn" }
        override fun signInOnWeb() { calls += "signInWeb" }
        override fun pickUpload(source: UploadSource) { calls += "upload $source" }
        override fun cancelUpload() { calls += "uploadCancel" }
        override fun answerDialog(confirmed: Boolean, value: String?) { calls += "dialog $confirmed" }
        override fun openDownload(download: BrowserDownload) { calls += "open ${download.fileName}" }
    }

    private fun string(id: Int) = composeRule.activity.getString(id)

    private val page = "https://moodle2.ntust.edu.tw/user/edit.php"

    private fun show(state: BrowserPageState, downloads: List<BrowserDownload> = emptyList(), appearance: BrowserAppearance = BrowserAppearance.AUTO) {
        composeRule.setContent {
            MaterialTheme {
                InAppBrowserScreen(state, appearance, downloads, actions) { Box(it) }
            }
        }
    }

    private fun loaded() = BrowserPageState().apply {
        url = page
        title = "Edit profile"
    }

    @Test
    fun `the top bar shows the host and the title, and its address opens the whole address`() {
        show(loaded())

        composeRule.onNodeWithText("moodle2.ntust.edu.tw").assertExists()
        composeRule.onNodeWithText("Edit profile").assertExists()
        composeRule.onNodeWithContentDescription(string(R.string.browser_address).replaceIosArg(1, "moodle2.ntust.edu.tw")).performClick()

        composeRule.onNodeWithText(string(R.string.browser_address_title)).assertExists()
        composeRule.onNodeWithText(page).assertExists()
        composeRule.onNodeWithText(string(R.string.browser_connection_secure)).assertExists()
    }

    @Test
    fun `close, share and the history buttons do what they say`() {
        show(loaded())

        composeRule.onNodeWithContentDescription(string(R.string.browser_back)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(string(R.string.browser_forward)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(string(R.string.browser_more)).performClick()
        composeRule.onNodeWithText(string(R.string.browser_share)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.browser_close)).performClick()

        assertEquals(listOf("share $page", "close"), calls)
    }

    @Test
    fun `more options is last in the bottom bar, in Share's place, and the top bar has none`() {
        show(loaded())

        val more = composeRule.onNodeWithContentDescription(string(R.string.browser_more)).getBoundsInRoot()
        val back = composeRule.onNodeWithContentDescription(string(R.string.browser_back)).getBoundsInRoot()
        val find = composeRule.onNodeWithContentDescription(string(R.string.browser_find)).getBoundsInRoot()
        val close = composeRule.onNodeWithContentDescription(string(R.string.browser_close)).getBoundsInRoot()
        assertEquals("in the bottom bar", back.top.value, more.top.value, 0.5f)
        assertTrue("after Find", more.left > find.right)
        assertEquals("the same size as the bar's other buttons", (back.right - back.left).value, (more.right - more.left).value, 0.5f)
        assertTrue("not in the top bar", more.top > close.bottom)
        composeRule.onNodeWithContentDescription(string(R.string.browser_share)).assertDoesNotExist()
    }

    @Test
    fun `the zoom percentage is the color of the text around it, not the accent`() {
        show(loaded())
        composeRule.onNodeWithContentDescription(string(R.string.browser_more)).performClick()

        assertEquals(layoutOf(string(R.string.browser_zoom)).layoutInput.style.color, layoutOf("100%").layoutInput.style.color)
    }

    @Test
    fun `the zoom percentage sits midway between zoom out and zoom in`() {
        show(loaded())
        composeRule.onNodeWithContentDescription(string(R.string.browser_more)).performClick()

        val minus = composeRule.onNodeWithContentDescription(string(R.string.browser_zoom_out)).getBoundsInRoot()
        val plus = composeRule.onNodeWithContentDescription(string(R.string.browser_zoom_in)).getBoundsInRoot()
        val text = composeRule.onNodeWithText("100%", useUnmergedTree = true).getBoundsInRoot()
        val layout = layoutOf("100%")
        val density = composeRule.density.density
        val inkCenter = text.left.value + (layout.getLineLeft(0) + layout.getLineRight(0)) / 2 / density

        assertEquals((minus.right.value + plus.left.value) / 2, inkCenter, 1f)
    }

    @Test
    fun `the lock in the menu opens the certificate`() {
        show(loaded().apply { certificate = goodCertificate })
        composeRule.onNodeWithContentDescription(string(R.string.browser_more)).performClick()

        composeRule.onNodeWithContentDescription(string(R.string.browser_certificate)).performClick()

        composeRule.onNodeWithText(string(R.string.browser_certificate_title)).assertExists()
        composeRule.onNodeWithText("Good CA").assertExists()
    }

    @Test
    fun `View certificate lines up with the connection row above it`() {
        show(loaded().apply { certificate = goodCertificate })
        composeRule.onNodeWithContentDescription(string(R.string.browser_address).replaceIosArg(1, "moodle2.ntust.edu.tw")).performClick()

        val connection = composeRule.onNodeWithText(string(R.string.browser_connection_secure), useUnmergedTree = true).getBoundsInRoot()
        val view = composeRule.onNodeWithText(string(R.string.browser_certificate), useUnmergedTree = true).getBoundsInRoot()

        assertEquals(connection.left.value, view.left.value, 0.5f)
    }

    private fun sheetId() = composeRule.onNodeWithTag(BROWSER_SHEET_TAG).fetchSemanticsNode().id

    /** The phone's Back, which goes to the sheet's own window while it is up. */
    @Suppress("DEPRECATION")
    private fun pressBackInSheet() {
        composeRule.runOnIdle { ShadowDialog.getLatestDialog().onBackPressed() }
        composeRule.waitForIdle()
    }

    @Test
    fun `a page the menu opens comes up in the same sheet`() {
        show(loaded())
        composeRule.onNodeWithContentDescription(string(R.string.browser_more)).performClick()
        val sheet = sheetId()

        composeRule.onNodeWithText(string(R.string.browser_security)).performScrollTo().performClick()

        composeRule.onNodeWithText(string(R.string.browser_address_title)).assertExists()
        composeRule.onAllNodesWithTag(BROWSER_SHEET_TAG).assertCountEquals(1)
        assertEquals("the sheet stayed; only its page changed", sheet, sheetId())
        // TalkBack hears a new pane, and lands on its title.
        composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, string(R.string.browser_address_title))).assertExists()
        composeRule.onNodeWithText(string(R.string.browser_address_title)).assertIsFocused()
    }

    @Test
    fun `back on the menu, it is scrolled where it was`() {
        show(loaded())
        composeRule.onNodeWithContentDescription(string(R.string.browser_more)).performClick()
        val security = composeRule.onNodeWithText(string(R.string.browser_security)).performScrollTo()
        val where = security.getBoundsInRoot().top

        security.performClick()
        composeRule.onNodeWithContentDescription(string(R.string.action_back)).performClick()

        assertEquals(where.value, composeRule.onNodeWithText(string(R.string.browser_security)).getBoundsInRoot().top.value, 1f)
    }

    @Test
    fun `a rotation keeps the sheet at its page`() {
        val restoration = StateRestorationTester(composeRule)
        val state = loaded()
        restoration.setContent {
            MaterialTheme {
                InAppBrowserScreen(state, BrowserAppearance.AUTO, emptyList(), actions) { Box(it) }
            }
        }
        composeRule.onNodeWithContentDescription(string(R.string.browser_more)).performClick()
        composeRule.onNodeWithText(string(R.string.browser_security)).performScrollTo().performClick()

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText(string(R.string.browser_address_title)).assertExists()
        // And Back still goes to the menu it came from.
        composeRule.onNodeWithContentDescription(string(R.string.action_back)).performClick()
        composeRule.onNodeWithText(string(R.string.browser_zoom)).assertExists()
    }

    @Test
    fun `the sheet's back arrow returns to the menu, in the same sheet`() {
        show(loaded())
        composeRule.onNodeWithContentDescription(string(R.string.browser_more)).performClick()
        val sheet = sheetId()
        composeRule.onNodeWithText(string(R.string.browser_security)).performScrollTo().performClick()

        composeRule.onNodeWithContentDescription(string(R.string.action_back)).performClick()

        composeRule.onNodeWithText(string(R.string.browser_zoom)).assertExists()
        composeRule.onNodeWithText(string(R.string.browser_address_title)).assertDoesNotExist()
        assertEquals(sheet, sheetId())
    }

    @Test
    fun `Back in a page returns to the one before it, and Back at the first page closes the sheet`() {
        show(loaded().apply { certificate = goodCertificate })
        composeRule.onNodeWithContentDescription(string(R.string.browser_more)).performClick()
        val sheet = sheetId()
        composeRule.onNodeWithText(string(R.string.browser_security)).performScrollTo().performClick()
        composeRule.onNodeWithText(string(R.string.browser_certificate)).performClick()
        composeRule.onNodeWithText(string(R.string.browser_certificate_title)).assertExists()

        pressBackInSheet()
        composeRule.onNodeWithText(string(R.string.browser_address_title)).assertExists()
        pressBackInSheet()
        composeRule.onNodeWithText(string(R.string.browser_zoom)).assertExists()
        assertEquals(sheet, sheetId())
        pressBackInSheet()

        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag(BROWSER_SHEET_TAG).assertCountEquals(0)
    }

    @Test
    fun `View certificate in the address sheet opens the certificate in the same sheet`() {
        show(loaded().apply { certificate = goodCertificate })
        composeRule.onNodeWithContentDescription(string(R.string.browser_address).replaceIosArg(1, "moodle2.ntust.edu.tw")).performClick()
        val sheet = sheetId()

        composeRule.onNodeWithText(string(R.string.browser_certificate)).performClick()

        composeRule.onNodeWithText("Good CA").assertExists()
        assertEquals(sheet, sheetId())
        // Opened from the address, the address is the first page: Back goes there, then closes.
        pressBackInSheet()
        composeRule.onNodeWithText(string(R.string.browser_address_title)).assertExists()
        pressBackInSheet()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag(BROWSER_SHEET_TAG).assertCountEquals(0)
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.first()
    }

    @Test
    fun `on dark bars the host reads in the dark scheme's text color, not black`() {
        val dark = darkColorScheme()
        composeRule.setContent {
            MaterialTheme(colorScheme = dark) {
                InAppBrowserScreen(loaded(), BrowserAppearance.DARK, emptyList(), actions) { Box(it) }
            }
        }

        assertEquals(dark.onSurface, layoutOf("moodle2.ntust.edu.tw").layoutInput.style.color)
    }

    @Test
    fun `while loading, reload is stop`() {
        show(loaded().apply { loading = true; progress = 40; canGoBack = true })

        composeRule.onNodeWithContentDescription(string(R.string.browser_back)).assertIsEnabled()
        composeRule.onNodeWithContentDescription(string(R.string.browser_reload)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(string(R.string.browser_stop)).performClick()

        assertEquals(listOf("stop"), calls)
    }

    @Test
    fun `the menu has zoom, desktop site and appearance with no note under it`() {
        show(loaded())
        composeRule.onNodeWithContentDescription(string(R.string.browser_more)).performClick()

        composeRule.onNodeWithText("100%").assertExists()
        composeRule.onNodeWithText(string(R.string.browser_desktop_site)).performClick()
        composeRule.onNodeWithText(string(R.string.browser_appearance_dark)).performClick()
        // Last: zooming steps the rest of the menu aside for a second (BrowserZoomPeekTest).
        composeRule.onNodeWithContentDescription(string(R.string.browser_zoom_in)).performClick()
        composeRule.onNodeWithText(
            "Auto matches the browser's colors to the page. Pages that support dark mode switch right away, without reloading.",
        ).assertDoesNotExist()
        composeRule.mainClock.advanceTimeBy(1_500)
        composeRule.onNodeWithText(string(R.string.browser_print)).performScrollTo().assertExists()
        composeRule.onNodeWithText(string(R.string.browser_downloads)).performScrollTo().assertExists()

        assertEquals(listOf("desktop true", "appearance DARK", "zoomIn"), calls)
    }

    @Test
    fun `find in page counts the matches, and says when there are none`() {
        show(loaded().apply { find = FindState(query = "due", ordinal = 3, count = 7, done = true) })
        composeRule.onNodeWithText("3/7").assertExists()
    }

    @Test
    fun `find in page with nothing found says no matches`() {
        show(loaded().apply { find = FindState(query = "zzz", ordinal = 0, count = 0, done = true) })
        composeRule.onNodeWithText(string(R.string.browser_find_none)).assertExists()
        composeRule.onNodeWithContentDescription(string(R.string.browser_find_next)).assertIsNotEnabled()
    }

    @Test
    fun `a link off the allowlist asks before the phone's browser takes it`() {
        show(loaded().apply { outsideUrl = "https://example.com/x" })

        composeRule.onNodeWithText(string(R.string.browser_outside_title)).assertExists()
        composeRule.onNodeWithText("https://example.com/x").assertExists()
        composeRule.onNodeWithText(string(R.string.browser_open_external)).performClick()

        assertEquals(listOf("leave https://example.com/x"), calls)
    }

    @Test
    fun `a page that did not load offers a retry and the phone's browser`() {
        show(loaded().apply { error = BrowserError.Unreachable("moodle2.ntust.edu.tw") })

        // Two lines: who is not answering, then what to do.
        composeRule.onNodeWithText("moodle2.ntust.edu.tw isn't responding.\nCheck your connection and try again.").assertExists()
        composeRule.onNodeWithText(string(R.string.action_retry)).performClick()
        composeRule.onNodeWithText(string(R.string.browser_open_external)).performClick()

        assertEquals(listOf("retry", "external"), calls)
    }

    @Test
    fun `a bad certificate offers no way past it`() {
        show(loaded().apply { error = BrowserError.BadCertificate("moodle2.ntust.edu.tw") })

        composeRule.onNodeWithText(string(R.string.browser_error_certificate).replaceIosArg(1, "moodle2.ntust.edu.tw")).assertExists()
        composeRule.onNodeWithText(string(R.string.action_retry)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.browser_open_external)).assertDoesNotExist()
    }

    private val goodCertificate = CertificateInfo("moodle2.ntust.edu.tw", "NTUST", "Good CA", null, null, null, "AA:BB")
    private val refusedCertificate = CertificateInfo("moodle2.ntust.edu.tw", null, "Expired CA", null, null, null, "CC:DD")

    private fun refused(certificate: CertificateInfo?) = loaded().apply {
        // The page before, whose certificate was fine.
        this.certificate = goodCertificate
        error = BrowserError.BadCertificate("moodle2.ntust.edu.tw", certificate)
    }

    @Test
    fun `a refused certificate is not called valid in the address sheet`() {
        show(refused(refusedCertificate))
        composeRule.onNodeWithContentDescription(string(R.string.browser_address).replaceIosArg(1, "moodle2.ntust.edu.tw")).performClick()

        composeRule.onNodeWithText(string(R.string.browser_connection_secure)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.browser_connection_secure_detail)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.browser_connection_insecure)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.browser_certificate_invalid)).assertExists()
    }

    @Test
    fun `View certificate shows the refused certificate, as not valid`() {
        show(refused(refusedCertificate))
        composeRule.onNodeWithContentDescription(string(R.string.browser_address).replaceIosArg(1, "moodle2.ntust.edu.tw")).performClick()
        composeRule.onNodeWithText(string(R.string.browser_certificate)).performClick()

        composeRule.onNodeWithText("Expired CA").assertExists()
        composeRule.onNodeWithText("Good CA").assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.browser_certificate_valid)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.browser_certificate_invalid)).assertExists()
    }

    @Test
    fun `with no refused certificate to show, View certificate is off rather than the page before's`() {
        show(refused(null))
        composeRule.onNodeWithContentDescription(string(R.string.browser_address).replaceIosArg(1, "moodle2.ntust.edu.tw")).performClick()

        composeRule.onNodeWithText(string(R.string.browser_certificate)).assertIsNotEnabled()
    }

    @Test
    fun `the sign-in screen counts down to the next try and offers the web sign-in`() {
        show(loaded().apply { signIn = SignInState("Moodle", page, msLeft = 65_000) })

        composeRule.onNodeWithText(string(R.string.browser_sign_in_title).replaceIosArg(1, "Moodle")).assertExists()
        composeRule.onNodeWithText(string(R.string.browser_sign_in_wait).replaceIosArg(1, "1:05")).assertExists()
        composeRule.onNodeWithText(string(R.string.action_sign_in_again)).assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.browser_sign_in_web)).performClick()

        assertEquals(listOf("signInWeb"), calls)
    }

    @Test
    fun `when a try may go, sign in again tries`() {
        show(loaded().apply { signIn = SignInState("Moodle", page, msLeft = 0, failed = true) })

        composeRule.onNodeWithText(string(R.string.browser_sign_in_failed)).assertExists()
        composeRule.onNodeWithText(string(R.string.action_sign_in_again)).performClick()

        assertEquals(listOf("signIn"), calls)
    }

    @Test
    fun `an image field offers photos, the camera and files`() {
        show(loaded().apply {
            upload = UploadRequest("moodle2.ntust.edu.tw", listOf(UploadSource.PHOTOS, UploadSource.CAMERA, UploadSource.FILES), imagesOnly = true, acceptTypes = listOf("image/*"), multiple = false) {}
        })

        // The sheet names neither the site nor the type it asks for.
        composeRule.onNodeWithText("moodle2.ntust.edu.tw asks for an image.").assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.browser_upload_photos)).assertExists()
        composeRule.onNodeWithText(string(R.string.browser_upload_files)).assertExists()
        composeRule.onNodeWithText(string(R.string.browser_upload_camera)).performClick()

        assertEquals(listOf("upload CAMERA"), calls)
    }

    @Test
    fun `a page's confirm has cancel and its alert has not`() {
        show(loaded().apply { dialog = PageDialog(PageDialog.Kind.CONFIRM, "moodle2.ntust.edu.tw", "Delete this?") { _, _ -> } })
        composeRule.onNodeWithText(string(R.string.browser_dialog_title).replaceIosArg(1, "moodle2.ntust.edu.tw")).assertExists()
        composeRule.onNodeWithText(string(R.string.action_cancel)).assertExists()
        composeRule.onNodeWithText(string(R.string.browser_dialog_ok)).performClick()
        assertEquals(listOf("dialog true"), calls)
    }

    @Test
    fun `an alert has only OK`() {
        show(loaded().apply { dialog = PageDialog(PageDialog.Kind.ALERT, "moodle2.ntust.edu.tw", "Saved") { _, _ -> } })
        composeRule.onNodeWithText("Saved").assertExists()
        composeRule.onNodeWithText(string(R.string.action_cancel)).assertDoesNotExist()
    }

    @Test
    fun `a finished download's card opens its file`() {
        val done = BrowserDownload(1, "week3.pdf", "application/pdf", bytes = 2048, total = 2048, state = BrowserDownload.State.DONE)
        show(loaded(), downloads = listOf(done))

        composeRule.onNodeWithText("week3.pdf").assertExists()
        composeRule.onNodeWithText(string(R.string.browser_download_open)).performClick()

        assertEquals(listOf("open week3.pdf"), calls)
    }

    @Test
    fun `the countdown shows minutes and seconds, rounded up`() {
        assertEquals("6:00", countdown(360_000))
        assertEquals("1:05", countdown(64_001))
        assertEquals("0:01", countdown(1))
    }

    @Test
    fun `the signed-in row in the address sheet is a title alone`() {
        show(loaded().apply { signedIn = true })
        composeRule.onNodeWithContentDescription(string(R.string.browser_address).replaceIosArg(1, "moodle2.ntust.edu.tw")).performClick()

        composeRule.onNodeWithText(string(R.string.browser_signed_in)).assertExists()
        composeRule.onNodeWithText("Signed in automatically through the site's one-time link. Your password is never given to the page.")
            .assertDoesNotExist()
    }

    @Test
    fun `the outside-link dialog has cancel alone on top, then copy on the left and the browser on the right`() {
        show(loaded().apply { outsideUrl = "https://example.com/x" })

        val cancel = composeRule.onNodeWithText(string(R.string.action_cancel)).getBoundsInRoot()
        val copy = composeRule.onNodeWithText(string(R.string.browser_copy_link)).getBoundsInRoot()
        val open = composeRule.onNodeWithText(string(R.string.browser_open_external)).getBoundsInRoot()

        // Cancel has a row of its own, above the other two.
        assertTrue("cancel above copy", cancel.bottom <= copy.top)
        assertTrue("cancel above open", cancel.bottom <= open.top)
        // Copy and open share the second row, copy on the left.
        assertEquals(copy.top.value, open.top.value, 1f)
        assertTrue("copy left of open", copy.right <= open.left)
        // Cancel and open both sit at the right edge.
        assertEquals(cancel.right.value, open.right.value, 2f)
    }
}
