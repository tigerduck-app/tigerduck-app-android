package org.ntust.app.tigerduck.ui.screen.home

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.model.HomeSection
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Leaving Home while it is arranged ends editing and forgets the place, as iOS does
 * (tigerduck-app/tigerduck-app#852): the page comes back browsed, at its top. Leaving it while
 * browsing keeps the place, which Navigation's saved state brings back.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class, qualifiers = "en")
class HomeEditingLeaveTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val sections = (1..8).map { HomeSection("custom-$it", HomeSection.HomeSectionType.CUSTOM, "Section $it", it, true) }
    // Held outside the pages, as HomeViewModel holds it.
    private val editing = HomeEditing()
    private lateinit var nav: NavHostController

    private fun show() {
        composeRule.setContent {
            nav = rememberNavController()
            TigerDuckAppTheme(darkTheme = false) {
                NavHost(nav, startDestination = "home") {
                    composable("home") {
                        val listState = rememberLazyListState()
                        HomeEditingLeaveEffect(editing, listState)
                        HomePage(
                            sections = sections,
                            isEditing = editing.isEditing,
                            onStartEditing = editing::start,
                            isRefreshing = false,
                            onRefresh = {},
                            onMoveSection = { _, _ -> },
                            onRemoveSection = {},
                            onRemoveWidget = { _, _ -> },
                            onMoveWidget = { _, _, _ -> },
                            onAddQuickFeature = {},
                            listState = listState,
                            header = { Text("Good morning") },
                            banner = null,
                        ) { section ->
                            // As tall as a real section, so eight of them scroll.
                            Text("live ${section.title}", Modifier.height(120.dp))
                        }
                    }
                    composable("other") { Text("Another page") }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun scrollToTheEnd() {
        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("live Section 8"))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Good morning").assertDoesNotExist()
    }

    private fun leaveAndComeBack() {
        composeRule.runOnUiThread { nav.navigate("other") }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Another page").assertIsDisplayed()
        composeRule.runOnUiThread { nav.popBackStack() }
        composeRule.waitForIdle()
    }

    @Test
    fun `leaving Home while editing ends editing and comes back at the top`() {
        show()
        scrollToTheEnd()
        composeRule.runOnUiThread { editing.start() }
        composeRule.waitForIdle()

        leaveAndComeBack()

        assertFalse(editing.isEditing)
        composeRule.onNodeWithText("Good morning").assertIsDisplayed()
        composeRule.onNodeWithText("live Section 1").assertIsDisplayed()
    }

    @Test
    fun `leaving Home while browsing comes back where it was`() {
        show()
        scrollToTheEnd()

        leaveAndComeBack()

        composeRule.onNodeWithText("Good morning").assertDoesNotExist()
        composeRule.onNodeWithText("live Section 8").assertIsDisplayed()
    }

    @Test
    fun `Done ends editing where the page is`() {
        show()
        scrollToTheEnd()
        composeRule.runOnUiThread { editing.start() }
        composeRule.waitForIdle()

        composeRule.runOnUiThread { editing.finish() }
        composeRule.waitForIdle()

        assertFalse(editing.isEditing)
        assertFalse(editing.takeForgottenPlace())
        composeRule.onNodeWithText("Good morning").assertDoesNotExist()
    }

    @Test
    fun `the forgotten place is taken once`() {
        editing.start()
        editing.onPageLeft()

        assertTrue(editing.takeForgottenPlace())
        assertFalse(editing.takeForgottenPlace())
    }
}
