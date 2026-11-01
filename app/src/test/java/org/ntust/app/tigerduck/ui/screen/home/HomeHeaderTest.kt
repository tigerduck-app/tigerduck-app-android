package org.ntust.app.tigerduck.ui.screen.home

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dagger.hilt.internal.GeneratedComponent
import dagger.hilt.internal.GeneratedComponentManager
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.HomeSection
import org.ntust.app.tigerduck.data.model.HomeSection.HomeSectionType
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.ui.component.PageDefaults
import org.ntust.app.tigerduck.ui.component.PageScaffold
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

    private val sections = (1..10).map { HomeSection("s$it", HomeSectionType.CUSTOM, "Section $it", it, true) }

    private fun show(isEditing: Boolean) {
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = false) {
                PageScaffold(
                    title = "Good morning",
                    headerPosition = PageDefaults.TabHeaderPosition,
                    syncDot = { if (!isEditing) Text("dot") },
                    actions = { if (isEditing) HomeEditActions(onAdd = {}, onDone = {}) },
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
        composeRule.waitForIdle()
    }

    @Test
    fun `browsing, the greeting and dot sit at the head of the sections`() {
        show(isEditing = false)
        composeRule.onNodeWithText("Good morning").assertIsDisplayed()
        composeRule.assertHeaderAtTop("Good morning", density)
        composeRule.assertRowEndsWith(composeRule.onNodeWithText("dot"), density)
    }

    @Test
    fun `arranging, Add and Done take the dot's place`() {
        show(isEditing = true)
        composeRule.onNodeWithText("dot").assertDoesNotExist()
        // Done stands 4dp taller than the row's minimum, so the row does too while arranging.
        composeRule.assertHeaderAtTop("Good morning", density, rowHeight = 52f)
        val add = composeRule.onNodeWithContentDescription(text(R.string.home_add_section_title)).assertIsDisplayed()
        val done = composeRule.onNodeWithText(text(R.string.action_done)).assertIsDisplayed()
        composeRule.assertSitsLeftOf(add, done)
    }

    @Test
    fun `the header scrolls away with the sections`() {
        show(isEditing = false)
        composeRule.assertHeaderScrollsAway("Good morning", density)
    }
}
