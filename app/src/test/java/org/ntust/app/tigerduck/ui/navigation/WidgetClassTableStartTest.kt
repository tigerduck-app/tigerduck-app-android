package org.ntust.app.tigerduck.ui.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The class table widget opens the user's own class table, whoever's the page showed last. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WidgetClassTableStartTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var startRoute by mutableStateOf<String?>(null)
    private var shownOwn = 0

    private fun start(route: String?) {
        composeRule.runOnIdle { startRoute = route }
        composeRule.waitForIdle()
    }

    @Test
    fun `each start from the class table widget shows the user's own class table, and no other start does`() {
        composeRule.setContent { ShowOwnClassTableFromWidget(startRoute) { shownOwn++ } }
        composeRule.waitForIdle()
        assertEquals(0, shownOwn)

        start(Screen.ClassTable.route)
        assertEquals(1, shownOwn)

        // The start route is consumed, and the library widget's start leaves the class table alone.
        start(null)
        start(Screen.Library.route)
        assertEquals(1, shownOwn)

        start(Screen.ClassTable.route)
        assertEquals(2, shownOwn)
    }
}
