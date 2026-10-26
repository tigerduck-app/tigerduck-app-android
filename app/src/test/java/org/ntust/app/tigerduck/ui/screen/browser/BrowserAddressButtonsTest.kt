package org.ntust.app.tigerduck.ui.screen.browser

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.browser.BrowserAppearance
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.util.replaceIosArg
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The address sheet's Copy, Share and phone's-browser buttons show their labels whole, on a narrow
 * phone, in Chinese and English, at the default text size and at 130% and 150%.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h780dp")
// Real text measurement: the default graphics mode gives each glyph next to no width.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowserAddressButtonsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int) = composeRule.activity.getString(id)

    private fun showAddressSheet() {
        val state = BrowserPageState().apply {
            url = "https://moodle2.ntust.edu.tw/user/edit.php"
            title = "Edit profile"
        }
        composeRule.setContent {
            MaterialTheme {
                InAppBrowserScreen(state, BrowserAppearance.AUTO, emptyList(), object : BrowserActions() {}) { Box(it) }
            }
        }
        composeRule.onNodeWithContentDescription(string(R.string.browser_address).replaceIosArg(1, "moodle2.ntust.edu.tw")).performClick()
    }

    private fun assertWhole(label: String) {
        val results = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.first()
        // One line held to less than it needs is clipped.
        val needs = layout.multiParagraph.maxIntrinsicWidth
        val given = layout.layoutInput.constraints.maxWidth
        assertTrue("'$label' needs $needs and gets $given", needs <= given)
        assertEquals("'$label' is on one line", 1, layout.lineCount)
    }

    private fun assertLabelsWhole() {
        showAddressSheet()
        assertWhole(string(R.string.browser_copy_short))
        assertWhole(string(R.string.browser_share_short))
        assertWhole(string(R.string.browser_open_external_short))
    }

    @Test
    @Config(qualifiers = "+zh-rTW")
    fun `Chinese at the default size`() = assertLabelsWhole()

    @Test
    @Config(qualifiers = "+zh-rTW", fontScale = 1.3f)
    fun `Chinese at 130 percent`() = assertLabelsWhole()

    @Test
    @Config(qualifiers = "+zh-rTW", fontScale = 1.5f)
    fun `Chinese at 150 percent`() = assertLabelsWhole()

    @Test
    @Config(qualifiers = "+el")
    fun `Greek, the longest labels, at the default size`() = assertLabelsWhole()

    @Test
    fun `English at the default size`() = assertLabelsWhole()

    @Test
    @Config(fontScale = 1.3f)
    fun `English at 130 percent`() = assertLabelsWhole()

    @Test
    @Config(fontScale = 1.5f)
    fun `English at 150 percent`() = assertLabelsWhole()
}
