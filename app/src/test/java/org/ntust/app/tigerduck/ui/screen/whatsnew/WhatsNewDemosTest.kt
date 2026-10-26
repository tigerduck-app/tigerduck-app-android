package org.ntust.app.tigerduck.ui.screen.whatsnew

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.AppFeature
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The 2.4.0 demos: each one draws, animated or still, in both languages, and
 * TalkBack hears it as the one description, not its screens' text or the
 * captions that take turns under it.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class WhatsNewDemosTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** A demo: its page, the start of its description, and text it shows that TalkBack mustn't hear separately. */
    private class Demo(val page: WhatsNewPage, val description: WhatsNewText, val hidden: List<WhatsNewText>)

    /** An app string: the same in both of the demo's languages, which follow the app's locale for these. */
    private fun string(id: Int) = composeRule.activity.getString(id).let { WhatsNewText(en = it, zhHant = it) }

    private val demos: List<Demo> by lazy {
        listOf(
            Demo(
                replayPage(),
                WhatsNewText(en = "Demo: from More, Settings opens", zhHant = "示範：從「更多」打開設定"),
                listOf(string(R.string.settings_whats_new), string(R.string.settings_section_about)),
            ),
            Demo(
                moodleCoursePage(),
                WhatsNewText(en = "Demo: a course opens", zhHant = "示範：從課表點開課程"),
                listOf(WhatsNewText(en = "Without the Moodle app", zhHant = "沒有 Moodle App 時"), string(R.string.course_detail_members_label)),
            ),
            Demo(moodleAssignmentPage(), WhatsNewText(en = "Demo: an assignment opens", zhHant = "示範：點開作業"), listOf(string(R.string.school_mail_attachments))),
            Demo(
                classTableSharePage(),
                WhatsNewText(en = "Demo: on one phone", zhHant = "示範：在一支手機的課表"),
                listOf(
                    WhatsNewText(en = "Scanned in TigerDuck", zhHant = "用 TigerDuck 掃描"),
                    WhatsNewText(en = "Any QR scanner", zhHant = "用任何 QR code 掃描器"),
                    WhatsNewText(en = "TAT works too", zhHant = "也支援臺科 TAT"),
                    string(R.string.class_table_share_tat_compatible),
                ),
            ),
            Demo(
                libraryPage(isSignedIn = { false }),
                WhatsNewText(en = "Demo: the library pass opens", zhHant = "示範：從底部功能列"),
                listOf(WhatsNewText(en = "From More", zhHant = "從「更多」"), string(R.string.library_virtual_pass_title)),
            ),
            Demo(
                bottomBarEditorPage(),
                WhatsNewText(en = "Demo: from Settings, Customization", zhHant = "示範：從設定的「自訂」"),
                listOf(string(R.string.tab_editor_title), string(R.string.tab_editor_section_current_tabs)),
            ),
            Demo(
                mailNotificationsPage(),
                WhatsNewText(en = "Demo: from Settings, Notifications", zhHant = "示範：從設定的「通知」"),
                listOf(string(R.string.school_mail_notification_settings_title), string(R.string.school_mail_filter_preset_bulletin)),
            ),
            Demo(
                emptyClassroomPage(),
                WhatsNewText(en = "Demo: Empty classroom opens from More", zhHant = "示範：從「更多」打開空教室"),
                listOf(
                    WhatsNewText(en = "Along the same floor", zhHant = "本樓平移"),
                    WhatsNewText(en = "Over to another building", zhHant = "去其他棟"),
                    string(R.string.feature_empty_classroom),
                ),
            ),
            Demo(
                displaySizePage(),
                WhatsNewText(en = "Demo: from Settings, Other settings", zhHant = "示範：從設定的「其他設定」"),
                listOf(string(R.string.settings_display_size_title), string(R.string.settings_display_size_ui)),
            ),
        )
    }

    private fun WhatsNewPage.visual(): WhatsNewVisual.Custom = when (this) {
        is WhatsNewPage.Feature -> visual as WhatsNewVisual.Custom
        is WhatsNewPage.OptIn -> visual as WhatsNewVisual.Custom
        else -> error("$id has no demo")
    }

    @Test
    fun `every demo draws through its loop, animated and still, in both languages`() {
        var language by mutableStateOf(WhatsNewLanguage.En)
        var animate by mutableStateOf(true)
        var index by mutableStateOf(0)
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalWhatsNewLanguage provides language) {
                    // As wide as the sheet's page on a small phone.
                    Box(Modifier.width(312.dp)) { demos[index].page.visual().content(animate) }
                }
            }
        }
        composeRule.mainClock.autoAdvance = false
        for (i in demos.indices) for (lang in WhatsNewLanguage.entries) for (moving in listOf(true, false)) {
            index = i
            language = lang
            animate = moving
            // With the clock held, the change reaches the screen once the main thread has run.
            composeRule.waitForIdle()
            // Through two whole loops (the longest, sharing, is 18 s), past the reset, a step at a time.
            repeat(40) { composeRule.mainClock.advanceTimeBy(1_000) }
            val demo = demos[i]
            composeRule.onAllNodesWithContentDescription(demo.description.resolve(lang), substring = true)
                .assertCountEquals(1)
            demo.hidden.forEach { text ->
                composeRule.onAllNodesWithText(text.resolve(lang), substring = true).assertCountEquals(0)
            }
        }
    }

    @Test
    fun `an earlier answer that fills the bar turns the library question into where library is`() {
        var bar = listOf(AppFeature.HOME, AppFeature.CLASS_TABLE, AppFeature.SCORE)
        val fill = WhatsNewPage.OptIn(
            id = "fill",
            visual = WhatsNewVisual.Icon(Icons.Filled.Mail),
            title = WhatsNewText(en = "Fill the bar?", zhHant = "Fill the bar?"),
            body = WhatsNewText(en = "Mail goes last.", zhHant = "Mail goes last."),
            confirmLabel = WhatsNewText(en = "Fill it", zhHant = "Fill it"),
            declineLabel = WhatsNewText(en = "Keep it", zhHant = "Keep it"),
            apply = { bar = bar + AppFeature.SCHOOL_MAIL },
        )
        val library = libraryBottomBarPage(isSignedIn = { false }, tabs = { bar }, setTabs = { bar = it })
        composeRule.setContent { WhatsNewSheet(WhatsNewFlow(listOf(fill, library), summary = null), onDismiss = {}) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Fill it").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Library is in More").assertExists()
        composeRule.onNodeWithText("Add Library").assertDoesNotExist()
    }

    @Test
    fun `confirming the library question doesn't turn it into where library is`() {
        var bar = listOf(AppFeature.HOME, AppFeature.CLASS_TABLE, AppFeature.SCORE)
        val library = libraryBottomBarPage(isSignedIn = { false }, tabs = { bar }, setTabs = { bar = it })
        composeRule.setContent { WhatsNewSheet(WhatsNewFlow(listOf(library), summary = null), onDismiss = {}) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Add Library").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Library is in More").assertDoesNotExist()
    }

    @Test
    // Real text measurement: the default graphics mode measures every line alike.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `mock text keeps its height at any font scale, as its screen does`() {
        composeRule.setContent {
            val density = LocalDensity.current
            MaterialTheme {
                Column {
                    for (fontScale in listOf(1f, 2f)) {
                        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                            MockText("Calculus", Modifier.testTag("mock $fontScale"))
                            // Text that does follow the font scale, to show the scale took.
                            Text("Calculus", Modifier.testTag("plain $fontScale"), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        fun height(tag: String) = composeRule.onNodeWithTag(tag).getBoundsInRoot().height

        assertTrue(
            "plain ${height("plain 1.0")} → ${height("plain 2.0")}, mock ${height("mock 1.0")} → ${height("mock 2.0")}",
            height("plain 2.0") > height("plain 1.0"),
        )
        assertEquals(height("mock 1.0"), height("mock 2.0"))
    }

    @Test
    fun `a tap target is found where it was laid out, from its mock screen's corner`() {
        var targets: TapTargets? = null
        composeRule.setContent {
            Box(Modifier.padding(start = 30.dp, top = 40.dp)) {
                MockScreen {
                    targets = LocalTapTargets.current
                    Box(Modifier.padding(start = 50.dp, top = 100.dp).size(20.dp).tapTarget("switch"))
                }
            }
        }
        composeRule.waitForIdle()

        val density = composeRule.density.density
        val center = targets!!.center("switch")!!
        assertEquals(60f * density, center.x, 1f)
        assertEquals(110f * density, center.y, 1f)
    }

    @Test
    fun `a tap target in a screen that slides in is found where it comes to rest, each loop`() {
        var targets: TapTargets? = null
        // Where a fingertip drawn in the same screen is placed: it slides with it.
        var fingertip: LayoutCoordinates? = null
        // Starts off the screen to the right, as a screen on the way does.
        var away by mutableFloatStateOf(1f)
        composeRule.setContent {
            Box(Modifier.padding(start = 30.dp, top = 40.dp)) {
                MockScreen {
                    targets = LocalTapTargets.current
                    Box(Modifier.fillMaxSize().graphicsLayer { translationX = away * size.width }) {
                        Box(Modifier.padding(start = 50.dp, top = 100.dp).size(20.dp).tapTarget("switch"))
                        Box(Modifier.size(1.dp).onPlaced { fingertip = it })
                    }
                }
            }
        }
        val density = composeRule.density.density
        repeat(2) {
            away = 1f
            composeRule.waitForIdle()
            // From the fingertip's place, the target is where it is in the screen, however far that has slid.
            for (slid in listOf(0.5f, 0f)) {
                away = slid
                composeRule.waitForIdle()

                val fromFingertip = targets!!.center("switch", from = fingertip)!!
                assertEquals(60f * density, fromFingertip.x, 1f)
                assertEquals(110f * density, fromFingertip.y, 1f)
            }

            val fromScreen = targets!!.center("switch")!!
            assertEquals(60f * density, fromScreen.x, 1f)
            assertEquals(110f * density, fromScreen.y, 1f)
        }
    }

    @Test
    fun `the library question's demo draws the bar with library added`() {
        composeRule.setContent {
            MaterialTheme {
                libraryBottomBarPage(
                    isSignedIn = { false },
                    tabs = { listOf(AppFeature.HOME, AppFeature.CLASS_TABLE) },
                    setTabs = {},
                ).visual().content(true)
            }
        }
        composeRule.onAllNodesWithContentDescription("After: Home, Class table, Library, More", substring = true)
            .assertCountEquals(1)
    }
}
