package org.ntust.app.tigerduck.ui.screen.debug

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.browser.BrowserAllowlistStore
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Settings › Developer › Browser allowlist. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BrowserAllowlistDebugScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var store: BrowserAllowlistStore

    /** What "Open a page" asked the browser to open, and what the browser answers. */
    private val opened = mutableListOf<String>()
    private var opens = true

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(BrowserAllowlistStore.PREFS_FILE, 0).edit().clear().commit()
        store = BrowserAllowlistStore(context, debug = true)
        composeRule.setContent {
            MaterialTheme {
                BrowserAllowlistDebugScreen(onBack = {}, store = store, openPage = { _, url -> opened += url; opens })
            }
        }
    }

    private fun add(text: String) {
        composeRule.onNode(hasSetTextAction() and hasTestTag(ADD_DOMAIN_FIELD_TAG)).performTextInput(text)
        composeRule.onNodeWithText("Add").performClick()
    }

    private fun open(text: String) {
        composeRule.onNode(hasSetTextAction() and hasTestTag(OPEN_PAGE_FIELD_TAG)).performTextInput(text)
        composeRule.onNodeWithText("Open").performClick()
    }

    @Test
    fun `a host typed in Open a page opens as an https page`() {
        open("example.com")
        assertEquals(listOf("https://example.com/"), opened)
    }

    @Test
    fun `Open a page takes a full address as it is, http and a port included`() {
        open("http://10.0.2.2:8080/test?a=1")
        assertEquals(listOf("http://10.0.2.2:8080/test?a=1"), opened)
    }

    @Test
    fun `Open a page reads a scheme as one, and a host and port as a host`() {
        assertEquals(null, debugPageUrl("mailto:alice@example.com"))
        assertEquals(null, debugPageUrl("file:/sdcard/a"))
        assertEquals(null, debugPageUrl("javascript:alert(1)"))
        assertEquals("https://example.com/", debugPageUrl("https:/example.com"))
        assertEquals("https://localhost:8080/", debugPageUrl("localhost:8080"))
        assertEquals("https://example.com:8443/x", debugPageUrl("example.com:8443/x"))
        assertEquals("https://localhost:8080/?x=1", debugPageUrl("localhost:8080?x=1"))
        assertEquals("https://example.com:8443/#top", debugPageUrl("example.com:8443#top"))
        assertEquals("http://10.0.2.2:8080/", debugPageUrl(" http://10.0.2.2:8080 "))
        assertEquals(null, debugPageUrl("   "))
    }

    @Test
    fun `Open a page says so for a line that is not a web address`() {
        open("not a page")
        assertEquals(emptyList<String>(), opened)
        composeRule.onNodeWithText("Not a web address.").assertExists()
    }

    @Test
    fun `Open a page says so when the browser cannot open now`() {
        opens = false
        open("example.com")
        composeRule.onNodeWithText("The browser can't open now: this phone's WebView can't clear its data, or a clearing is owed.").assertExists()
    }

    @Test
    fun `the default shows and cannot be removed`() {
        composeRule.onNodeWithText("*.ntust.edu.tw").assertExists()
        composeRule.onNodeWithText("Default").assertExists()
        composeRule.onNodeWithContentDescription("Remove *.ntust.edu.tw").assertDoesNotExist()
    }

    @Test
    fun `an added domain is listed as custom, and removed again`() {
        add("example.com")

        composeRule.onNodeWithText("example.com").assertExists()
        composeRule.onNodeWithText("Custom").assertExists()
        assertTrue(store.allowlist.value.allows("https://example.com/"))

        composeRule.onNodeWithContentDescription("Remove example.com").performClick()

        composeRule.onNodeWithText("example.com").assertDoesNotExist()
        assertFalse(store.allowlist.value.allows("https://example.com/"))
    }

    @Test
    fun `an exact host under the wildcard is marked redundant`() {
        add("moodle2.ntust.edu.tw")
        composeRule.onNodeWithText("Custom · Redundant: a wildcard already covers it").assertExists()
    }

    @Test
    fun `a line that is not a domain says so`() {
        add("https://example.com/path")
        composeRule.onNodeWithText("Not a domain. Write a host (example.com) or a wildcard (*.example.com), with no scheme, port or path.").assertExists()
        assertEquals(1, store.allowlist.value.entries.size)
    }

    @Test
    fun `allow every site and reset`() {
        add("example.com")
        composeRule.onNodeWithText("Allow every site").performClick()
        assertTrue(store.allowlist.value.allowEverySite)

        composeRule.onNodeWithText("Reset to default").performScrollTo().performClick()

        assertFalse(store.allowlist.value.allowEverySite)
        composeRule.onNodeWithText("example.com").assertDoesNotExist()
    }

    @Test
    fun `allow every site and reset carry no line under them`() {
        composeRule.onNodeWithText("For testing. Every https link opens in the in-app browser, without asking.").assertDoesNotExist()
        composeRule.onNodeWithText("Removes the custom domains and turns off Allow every site.").assertDoesNotExist()
    }
}
