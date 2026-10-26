package org.ntust.app.tigerduck.ui.screen.debug

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.debug.OutsideApp
import org.ntust.app.tigerduck.debug.PretendMissingApps
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Settings › Developer › Missing apps. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OutsideAppsDebugScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("debug_outside_apps", Context.MODE_PRIVATE).edit().clear().commit()
        PretendMissingApps.init(context)
        composeRule.setContent { MaterialTheme { OutsideAppsDebugScreen(onBack = {}) } }
    }

    @After
    fun tearDown() {
        OutsideApp.entries.forEach { PretendMissingApps.setMissing(it, false) }
    }

    @Test
    fun `each app's row is its switch, so TalkBack says which app it is`() {
        val row = composeRule.onNodeWithText(OutsideApp.FILE_VIEWER.missing).performScrollTo()
        row.assertIsOff()

        row.performClick()

        row.assertIsOn()
        assertTrue(PretendMissingApps.isMissing(OutsideApp.FILE_VIEWER))
    }

    @Test
    fun `the back button has a name`() {
        composeRule.onNodeWithContentDescription("Back").assertExists()
    }
}
