package org.ntust.app.tigerduck.ui.screen.home

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dagger.hilt.internal.GeneratedComponent
import dagger.hilt.internal.GeneratedComponentManager
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.HomeSection
import org.ntust.app.tigerduck.data.model.HomeSection.HomeSectionType
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.ui.component.LocalOpenPageSettings
import org.ntust.app.tigerduck.ui.component.PageSettings
import org.ntust.app.tigerduck.ui.component.ServerStatusTracker
import org.ntust.app.tigerduck.ui.haptics.Haptics
import org.ntust.app.tigerduck.ui.screen.TabHeaderChecks.assertHeaderAtTop
import org.ntust.app.tigerduck.ui.screen.TabHeaderChecks.assertHeaderScrollsAway
import org.ntust.app.tigerduck.ui.screen.TabHeaderChecks.assertRowEndsWith
import org.ntust.app.tigerduck.ui.screen.TabHeaderChecks.assertSitsLeftOf
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Home's header: a greeting at the head of the sections, its dot giving way to Add and Done while arranging. */
@RunWith(RobolectricTestRunner::class)
@Config(application = HomeHeaderTest.App::class, qualifiers = "en-w400dp-h600dp")
class HomeHeaderTest {
    /** A plain Application, but for the haptic a swipe across the page asks its preferences for. */
    class App : Application(), GeneratedComponentManager<Any> {
        private val component by lazy {
            object : Haptics.PrefsEntryPoint, GeneratedComponent {
                override fun appPreferences() = AppPreferences(this@App)
            }
        }

        override fun generatedComponent(): Any = component
    }

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val density get() = composeRule.activity.resources.displayMetrics.density
    private fun text(id: Int) = composeRule.activity.getString(id)

    @Before
    fun signIn() = ServerStatusTracker.setSignedIn(true)

    @After
    fun signOut() = ServerStatusTracker.setSignedIn(false)

    private fun dot() = composeRule.onNodeWithContentDescription(text(R.string.sync_status_syncing))

    private val sections = (1..10).map { HomeSection("s$it", HomeSectionType.CUSTOM, "Section $it", it, true) }

    /** What the menu's items were picked for, in order. */
    private val picked = mutableListOf<String>()

    private fun show(
        isEditing: Boolean,
        onAdd: () -> Unit = {},
        onDone: () -> Unit = {},
        switches: HomeMenuSwitches = HomeMenuSwitches(false, false, false, false),
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalOpenPageSettings provides { picked += "settings of $it" }) {
                TigerDuckAppTheme(darkTheme = false) {
                    HomeShell(
                        greeting = "Good morning",
                        isEditing = isEditing,
                        isLoading = true,
                        syncedAtMs = null,
                        onAdd = onAdd,
                        onDone = onDone,
                        snackbarHost = {},
                        menu = homeMenu(
                            switches = { switches },
                            onEdit = { picked += "edit" },
                            onFullDueDate = { picked += "full due date $it" },
                            onInvertSlider = { picked += "invert slider $it" },
                            onAssignmentReminders = { picked += "assignment reminders $it" },
                            onLiveUpdates = { picked += "live updates $it" },
                        ),
                    ) { padding ->
                        HomePage(
                            sections = sections,
                            isEditing = isEditing,
                            onStartEditing = {},
                            isRefreshing = false,
                            onRefresh = {},
                            onMoveSection = { _, _ -> },
                            onRemoveSection = {},
                            onRemoveWidget = { _, _ -> },
                            onMoveWidget = { _, _, _ -> },
                            onAddQuickFeature = {},
                            header = { Header() },
                            banner = null,
                            modifier = Modifier.padding(padding),
                        ) { section -> Text(section.title, Modifier.height(200.dp)) }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun menuButton() = composeRule.onNodeWithContentDescription(text(R.string.home_more_actions))

    private fun item(id: Int) = composeRule.onNodeWithText(text(id))

    @Test
    fun `browsing, the greeting, dot and menu sit at the head of the sections`() {
        show(isEditing = false)
        composeRule.onNodeWithText("Good morning").assertIsDisplayed()
        composeRule.assertHeaderAtTop("Good morning", density)
        composeRule.assertSitsLeftOf(dot().assertIsDisplayed(), menuButton().assertIsDisplayed())
        composeRule.assertRowEndsWith(menuButton(), density, targetMargin = 4f)
    }

    @Test
    fun `arranging, Add and Done take the dot's place`() {
        show(isEditing = true)
        dot().assertDoesNotExist()
        // Done stands 4dp taller than the row's minimum, so the row does too while arranging.
        composeRule.assertHeaderAtTop("Good morning", density, rowHeight = 52f)
        val add = composeRule.onNodeWithContentDescription(text(R.string.home_add_section_title)).assertIsDisplayed()
        val done = composeRule.onNodeWithText(text(R.string.action_done)).assertIsDisplayed()
        composeRule.assertSitsLeftOf(add, done)
    }

    @Test
    fun `browsing shows no Add or Done, and arranging's buttons reach the page`() {
        show(isEditing = false)
        composeRule.onNodeWithContentDescription(text(R.string.home_add_section_title)).assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.action_done)).assertDoesNotExist()
    }

    @Test
    fun `Add and Done call what the page gives them`() {
        var adds = 0
        var dones = 0
        show(isEditing = true, onAdd = { adds++ }, onDone = { dones++ })
        composeRule.onNodeWithContentDescription(text(R.string.home_add_section_title)).performClick()
        composeRule.onNodeWithText(text(R.string.action_done)).performClick()
        assertEquals(1, adds)
        assertEquals(1, dones)
    }

    @Test
    fun `the menu runs as on the iPhone, Edit Home, four of Home's settings, then Settings`() {
        show(isEditing = false)
        menuButton().performClick()
        val order = listOf(
            R.string.home_edit,
            R.string.settings_home_full_due_date,
            R.string.settings_invert_slider_direction,
            R.string.settings_assignment_due_reminder,
            R.string.live_activity_channel_name,
            R.string.action_settings,
        )
        val tops = order.map { item(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top }
        assertEquals(tops.sorted(), tops)
    }

    @Test
    fun `each switch reads its setting and turns it, closing the menu`() {
        show(isEditing = false, switches = HomeMenuSwitches(true, false, true, false))
        val rows = listOf(
            R.string.settings_home_full_due_date to "full due date false",
            R.string.settings_invert_slider_direction to "invert slider true",
            R.string.settings_assignment_due_reminder to "assignment reminders false",
            R.string.live_activity_channel_name to "live updates true",
        )
        rows.forEachIndexed { index, (label, pick) ->
            menuButton().performClick()
            composeRule.onNode(hasText(text(label)) and isToggleable())
                .apply { if (index % 2 == 0) assertIsOn() else assertIsOff() }
                .performClick()
            composeRule.waitForIdle()
            item(label).assertDoesNotExist()
            assertEquals(pick, picked.last())
        }
    }

    @Test
    fun `Edit Home starts arranging, and Settings opens Home's settings`() {
        show(isEditing = false)
        menuButton().performClick()
        item(R.string.home_edit).performClick()
        menuButton().performClick()
        item(R.string.action_settings).performClick()
        assertEquals(listOf("edit", "settings of ${PageSettings.HOME}"), picked)
    }

    @Test
    fun `arranging, there is no menu`() {
        show(isEditing = true)
        menuButton().assertDoesNotExist()
    }

    @Test
    fun `the header scrolls away with the sections`() {
        show(isEditing = false)
        composeRule.assertHeaderScrollsAway("Good morning", density)
    }
}
