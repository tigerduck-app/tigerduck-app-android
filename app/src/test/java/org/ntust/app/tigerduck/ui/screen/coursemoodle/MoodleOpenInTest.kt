package org.ntust.app.tigerduck.ui.screen.coursemoodle

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.debug.OutsideApp
import org.ntust.app.tigerduck.debug.PretendMissingApps
import org.ntust.app.tigerduck.ui.screen.settings.MoodleOpenInSetting
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * The Open Moodle in setting (tigerduck-app-android#542): a Moodle page goes to the Moodle app or
 * to the TigerDuck browser as it says, and Settings offers it only while the Moodle app is there.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class MoodleOpenInTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val app = RuntimeEnvironment.getApplication()
    private val moodle = ComponentName("com.moodle.moodlemobile", "com.moodle.moodlemobile.MainActivity")

    private val started = mutableListOf<Intent>()
    private val context = object : ContextWrapper(app) {
        override fun startActivity(intent: Intent) {
            started += intent
        }
    }
    private val routed = mutableListOf<String>()
    private val recording = MoodleWebPages { _, url, _ -> routed += url }

    @Before
    fun setUp() {
        app.getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        PretendMissingApps.setMissing(OutsideApp.MOODLE, false)
    }

    private fun installMoodle(defaultCategory: Boolean = true) {
        val packages = shadowOf(app.packageManager)
        packages.addActivityIfNotPresent(moodle)
        packages.addIntentFilterForActivity(
            moodle,
            IntentFilter(Intent.ACTION_VIEW).apply {
                if (defaultCategory) addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("moodlemobile")
            },
        )
    }

    private fun uninstallMoodle() {
        shadowOf(app.packageManager).run {
            clearIntentFilterForActivity(moodle)
            removeActivity(moodle)
        }
    }

    private fun packageBroadcast(action: String) {
        app.sendBroadcast(Intent(action, "package:${moodle.packageName}".toUri()))
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun String.toUri() = android.net.Uri.parse(this)

    @Test
    fun `set to the in-app browser, a Moodle page opens on the website though the Moodle app takes it`() {
        openMoodlePage(context, "/mod/assign/view.php?id=890", inMoodleApp = { false }) { recording }

        assertEquals(listOf("https://moodle2.ntust.edu.tw/mod/assign/view.php?id=890"), routed)
        assertTrue("the Moodle app is not asked", started.isEmpty())
    }

    @Test
    fun `set to the Moodle app, a Moodle page opens there`() {
        openMoodlePage(context, "/mod/assign/view.php?id=890", inMoodleApp = { true }) { recording }

        assertEquals("moodlemobile", started.single().data?.scheme)
        assertTrue(routed.isEmpty())
    }

    @Test
    fun `Open in Moodle follows the stored choice`() {
        openMoodlePage(context, "/course/view.php?id=5") { recording }
        assertEquals("moodlemobile", started.single().data?.scheme)

        AppPreferences(app).moodleOpenIn = AppPreferences.MOODLE_OPEN_IN_BROWSER
        openMoodlePage(context, "/course/view.php?id=5") { recording }

        assertEquals(1, started.size)
        assertEquals(listOf("https://moodle2.ntust.edu.tw/course/view.php?id=5"), routed)
    }

    @Test
    fun `an install that never chose opens Moodle in the Moodle app, and the choice is kept`() {
        val prefs = AppPreferences(app)
        assertEquals(AppPreferences.MOODLE_OPEN_IN_APP, prefs.moodleOpenIn)

        prefs.moodleOpenIn = AppPreferences.MOODLE_OPEN_IN_BROWSER

        assertEquals(AppPreferences.MOODLE_OPEN_IN_BROWSER, AppPreferences(app).moodleOpenIn)
    }

    @Test
    fun `the Moodle app counts as installed only while an app takes its links`() {
        assertFalse(isMoodleAppInstalled(app.packageManager))

        installMoodle()
        assertTrue(isMoodleAppInstalled(app.packageManager))

        // Debug builds can pretend it is missing, as Open in Moodle then does.
        PretendMissingApps.setMissing(OutsideApp.MOODLE, true)
        assertFalse(isMoodleAppInstalled(app.packageManager))
    }

    @Test
    fun `an app that takes the links only when named does not count as the Moodle app`() {
        // Without the default category, the link "Open in Moodle" sends would never reach it.
        installMoodle(defaultCategory = false)

        assertFalse(isMoodleAppInstalled(app.packageManager))
    }

    @Test
    fun `Settings sees the Moodle app come and go while it is up`() {
        var installed: Boolean? = null
        composeRule.setContent { installed = rememberMoodleAppInstalled() }
        composeRule.waitForIdle()
        assertEquals(false, installed)

        installMoodle()
        packageBroadcast(Intent.ACTION_PACKAGE_ADDED)
        composeRule.waitForIdle()
        assertEquals(true, installed)

        uninstallMoodle()
        packageBroadcast(Intent.ACTION_PACKAGE_REMOVED)
        composeRule.waitForIdle()
        assertEquals(false, installed)
    }

    @Test
    fun `Settings looks again for the Moodle app each time it comes back`() {
        var installed: Boolean? = null
        composeRule.setContent { installed = rememberMoodleAppInstalled() }
        composeRule.waitForIdle()
        assertEquals(false, installed)

        // Installed while TigerDuck was in the background, whose broadcast Settings was not up for.
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        installMoodle()
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()

        assertEquals(true, installed)
    }

    @Test
    fun `Settings offers Open Moodle in only while the Moodle app is installed`() {
        var installed by mutableStateOf(false)
        var selected by mutableStateOf(AppPreferences.MOODLE_OPEN_IN_APP)
        composeRule.setContent {
            MaterialTheme { MoodleOpenInSetting(installed, selected) { selected = it } }
        }
        val label = app.getString(R.string.settings_moodle_open_in)
        composeRule.onAllNodesWithText(label).assertCountEquals(0)

        installed = true
        composeRule.onNodeWithText(label).assertExists()
        composeRule.onNodeWithText(app.getString(R.string.settings_moodle_open_in_app)).performClick()
        composeRule.onNodeWithText(app.getString(R.string.settings_browser_in_app)).performClick()

        assertEquals(AppPreferences.MOODLE_OPEN_IN_BROWSER, selected)
    }

    @Test
    fun `the manifest makes the Moodle app visible to the check`() {
        val manifest = File(appDir(), "src/main/AndroidManifest.xml").readText()
        val queries = Regex("""<queries>[\s\S]*?</queries>""").find(manifest)?.value
            ?: throw AssertionError("the manifest declares no package queries")
        val intents = Regex("""<intent>[\s\S]*?</intent>""").findAll(queries).map { it.value }

        // One query for the very link the check probes: a view of a moodlemobile:// address.
        assertTrue(
            intents.any {
                """<action android:name="android.intent.action.VIEW" />""" in it &&
                    """<data android:scheme="moodlemobile" />""" in it
            },
        )
    }

    private fun appDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val here = dir
            if (File(here, "app/src/main/java").isDirectory) return File(here, "app")
            if (File(here, "src/main/java").isDirectory) return here
            dir = here.parentFile
        }
        throw AssertionError("Could not locate the :app module from ${File("").absolutePath}")
    }
}
