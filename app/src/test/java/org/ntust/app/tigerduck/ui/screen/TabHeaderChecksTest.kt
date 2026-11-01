package org.ntust.app.tigerduck.ui.screen

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.ui.component.PageHeaderPosition
import org.ntust.app.tigerduck.ui.component.PageScaffold
import org.ntust.app.tigerduck.ui.screen.TabHeaderChecks.assertHeaderScrollsAway
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The check every tab page's test leans on must tell a header that scrolls from a bar that stays. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en-w400dp-h600dp")
class TabHeaderChecksTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val density get() = composeRule.activity.resources.displayMetrics.density

    private fun show(position: PageHeaderPosition) {
        composeRule.setContent {
            PageScaffold(title = "Calendar", headerPosition = position) { padding ->
                LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                    item { Header() }
                    items(100) { Text("row $it", Modifier.height(100.dp)) }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `a header in the list passes`() {
        show(PageHeaderPosition.InContent)
        composeRule.assertHeaderScrollsAway("Calendar", density)
    }

    @Test
    fun `a pinned bar fails`() {
        show(PageHeaderPosition.Pinned)
        assertThrows(AssertionError::class.java) { composeRule.assertHeaderScrollsAway("Calendar", density) }
    }
}
