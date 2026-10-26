package org.ntust.app.tigerduck.ui.screen.mail

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.mail.sanitize.PlainTextLinks
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LinkedPlainTextTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `tapping a link in the selectable plain text reports which one`() {
        val text = "Hours https://www.lib.ntust.edu.tw then ask library@mail.ntust.edu.tw"
        val tapped = mutableListOf<Int>()
        composeRule.setContent {
            SelectionContainer {
                LinkedPlainText(text, PlainTextLinks.find(text), onLink = { tapped += it })
            }
        }
        composeRule.onNodeWithText(text).performFirstLinkClick { (it.item as LinkAnnotation.Clickable).tag == "link-1" }
        composeRule.onNodeWithText(text).performFirstLinkClick()
        assertEquals(listOf(1, 0), tapped)
    }
}
