package org.ntust.app.tigerduck.ui.screen.mail

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MailWebViewLoadingTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val loading = hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)

    /**
     * A WebView has no height until its page is drawn, which left nothing at all under the
     * header while a mail's page loaded: the space shows progress until then.
     */
    @Test
    fun `shows progress until the mail's page is drawn`() {
        composeRule.setContent { MailWebView("<p>hi</p>", emptySet(), linkCount = 0, backgroundColor = 0, onLink = {}) }
        composeRule.onNode(loading).assertExists()

        val webView = composeRule.activity.window.decorView.webView()!!
        composeRule.runOnUiThread { shadowOf(webView).webViewClient.onPageCommitVisible(webView, "about:blank") }
        composeRule.onNode(loading).assertDoesNotExist()
    }

    /** Letting a mail's remote images in, or changing its theme, loads the WebView again. */
    @Test
    fun `shows progress again while a changed document loads`() {
        val document = mutableStateOf("<p>hi</p>")
        composeRule.setContent { MailWebView(document.value, emptySet(), linkCount = 0, backgroundColor = 0, onLink = {}) }
        val webView = composeRule.activity.window.decorView.webView()!!
        composeRule.runOnUiThread { shadowOf(webView).webViewClient.onPageCommitVisible(webView, "about:blank") }
        composeRule.onNode(loading).assertDoesNotExist()

        composeRule.runOnUiThread { document.value = "<p>hi</p><img src=\"https://example.invalid/a.png\">" }
        composeRule.onNode(loading).assertExists()

        composeRule.runOnUiThread { shadowOf(webView).webViewClient.onPageCommitVisible(webView, "about:blank") }
        composeRule.onNode(loading).assertDoesNotExist()
    }

    private fun View.webView(): WebView? = when (this) {
        is WebView -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).webView() }
        else -> null
    }
}
