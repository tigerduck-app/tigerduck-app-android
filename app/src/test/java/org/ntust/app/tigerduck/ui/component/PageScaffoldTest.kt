package org.ntust.app.tigerduck.ui.component

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PageScaffoldTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun backLabel() = composeRule.activity.getString(R.string.action_back)

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(results)
        return results.single()
    }

    private fun SemanticsNodeInteraction.left() = fetchSemanticsNode().boundsInRoot.left

    private fun SemanticsNodeInteraction.top() = fetchSemanticsNode().boundsInRoot.top

    private val density get() = composeRule.activity.resources.displayMetrics.density

    private fun menu(onPick: () -> Unit = {}, enabled: Boolean = true, onOpen: () -> Unit = {}) =
        PageMenu(label = "Page actions", enabled = enabled, onOpen = onOpen) { close ->
            DropdownMenuItem(text = { Text("Share") }, onClick = { close(); onPick() })
        }

    @Test
    fun `the title and the page content are shown`() {
        composeRule.setContent {
            PageScaffold(title = "Storage", onBack = {}) { Text("page body") }
        }
        composeRule.onNodeWithText("Storage").assertIsDisplayed()
        composeRule.onNodeWithText("page body").assertIsDisplayed()
    }

    @Test
    fun `the back arrow calls onBack once`() {
        var backs = 0
        composeRule.setContent { PageScaffold(title = "Storage", onBack = { backs++ }) {} }
        composeRule.onNodeWithContentDescription(backLabel()).performClick()
        assertEquals(1, backs)
    }

    @Test
    fun `actions are shown in the bar and are clickable`() {
        var clicks = 0
        composeRule.setContent {
            PageScaffold(
                title = "Tabs",
                onBack = {},
                actions = { androidx.compose.material3.TextButton(onClick = { clicks++ }) { Text("Reset") } },
            ) {}
        }
        composeRule.onNodeWithText("Reset").assertIsDisplayed().performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `without actions the bar has only the back arrow and title`() {
        composeRule.setContent { PageScaffold(title = "Storage", onBack = {}) {} }
        composeRule.onNodeWithText("Storage").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(backLabel()).assertIsDisplayed()
        composeRule.onAllNodes(hasClickAction()).assertCountEquals(1)
    }

    @Test
    fun `an empty title leaves the title slot empty`() {
        composeRule.setContent { PageScaffold(title = "", onBack = {}) {} }
        composeRule.onNodeWithContentDescription(backLabel()).assertIsDisplayed()
        composeRule.onAllNodes(hasText("")).assertCountEquals(0)
    }

    @Test
    fun `the content starts below the compact bar`() {
        var padding: PaddingValues? = null
        composeRule.setContent {
            PageScaffold(title = "Storage", onBack = {}) { padding = it }
        }
        composeRule.runOnIdle {
            assertEquals(56.dp, padding!!.calculateTopPadding())
        }
    }

    @Test
    fun `contentWindowInsets reaches the Scaffold`() {
        var padding: PaddingValues? = null
        composeRule.setContent {
            PageScaffold(
                title = "Storage",
                onBack = {},
                contentWindowInsets = WindowInsets(bottom = 10.dp),
            ) { padding = it }
        }
        composeRule.runOnIdle { assertEquals(10.dp, padding!!.calculateBottomPadding()) }
    }

    @Test
    fun `a snackbar host is shown with the page`() {
        composeRule.setContent {
            PageScaffold(
                title = "Mail",
                onBack = {},
                snackbarHost = { Text("Message sent") },
            ) {}
        }
        composeRule.onNodeWithText("Message sent").assertIsDisplayed()
    }

    @Test
    fun `without a snackbar host nothing extra is shown`() {
        composeRule.setContent { PageScaffold(title = "Mail", onBack = {}) {} }
        composeRule.onNodeWithText("Message sent").assertDoesNotExist()
    }

    @Test
    fun `a floating action button is shown and clickable`() {
        var clicks = 0
        composeRule.setContent {
            PageScaffold(
                title = "Detail",
                onBack = {},
                floatingActionButton = {
                    androidx.compose.material3.TextButton(onClick = { clicks++ }) { Text("Open source") }
                },
            ) {}
        }
        composeRule.onNodeWithText("Open source").assertIsDisplayed().performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `without a floating action button none is shown`() {
        composeRule.setContent { PageScaffold(title = "Detail", onBack = {}) {} }
        composeRule.onNodeWithText("Open source").assertDoesNotExist()
    }

    @Test
    fun `a title and content alone make a page, with no way back`() {
        composeRule.setContent { PageScaffold(title = "Home") { Text("page body") } }
        composeRule.onNodeWithText("Home").assertIsDisplayed()
        composeRule.onNodeWithText("page body").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(backLabel()).assertDoesNotExist()
    }

    @Test
    fun `a navigation slot takes the arrow's place`() {
        composeRule.setContent {
            PageScaffold(title = "Scan", onBack = {}, navigationIcon = { Text("QR") }) {}
        }
        composeRule.onNodeWithText("QR").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(backLabel()).assertDoesNotExist()
    }

    @Test
    fun `no menu draws no button`() {
        composeRule.setContent { PageScaffold(title = "Storage", onBack = {}) {} }
        composeRule.onNodeWithContentDescription("Page actions").assertDoesNotExist()
        composeRule.onAllNodes(hasClickAction()).assertCountEquals(1)
    }

    @Test
    fun `a menu opens on its items, and picking one closes it`() {
        var picks = 0
        var opens = 0
        composeRule.setContent {
            PageScaffold(title = "Tabs", onBack = {}, menu = menu({ picks++ }, onOpen = { opens++ })) {}
        }
        composeRule.onNodeWithText("Share").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Page actions").assertIsDisplayed().performClick()
        assertEquals(1, opens)
        composeRule.onNodeWithText("Share").assertIsDisplayed().performClick()
        assertEquals(1, picks)
        composeRule.onNodeWithText("Share").assertDoesNotExist()
    }

    @Test
    fun `a disabled menu shows and doesn't open`() {
        var opens = 0
        composeRule.setContent {
            PageScaffold(title = "Tabs", menu = menu(enabled = false, onOpen = { opens++ })) {}
        }
        composeRule.onNodeWithContentDescription("Page actions").assertIsDisplayed().assertIsNotEnabled()
        assertEquals(0, opens)
    }

    @Test
    fun `the header's parts sit in one order, the menu last`() {
        composeRule.setContent {
            PageScaffold(
                title = "T",
                syncDot = { Text("dot") },
                decoration = { Text("mark") },
                actions = { Text("act") },
                menu = menu(),
            ) {}
        }
        val lefts = listOf("T", "dot", "mark", "act").map { composeRule.onNodeWithText(it).left() } +
            composeRule.onNodeWithContentDescription("Page actions").left()
        assertEquals(lefts.sorted(), lefts)
        assertEquals(lefts.size, lefts.toSet().size)
    }

    @Test
    fun `without a dot or decoration the actions keep their place`() {
        composeRule.setContent { PageScaffold(title = "T", actions = { Text("act") }) {} }
        composeRule.onNodeWithText("act").assertIsDisplayed()
        composeRule.onNodeWithText("dot").assertDoesNotExist()
    }

    @Test
    fun `the default title is the theme's titleLarge, as Material's bar draws it`() {
        var themed = TextStyle.Default
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = false) {
                themed = MaterialTheme.typography.titleLarge
                Box(Modifier.fillMaxSize()) {
                    PageScaffold(title = "Shell") {}
                    MaterialBar()
                }
            }
        }
        val shell = composeRule.onNodeWithText("Shell").layout().layoutInput.style
        val material = composeRule.onNodeWithText("Material").layout().layoutInput.style
        assertEquals(themed.fontSize, shell.fontSize)
        assertEquals(FontWeight.Bold, shell.fontWeight)
        assertEquals(material.fontSize, shell.fontSize)
        assertEquals(material.fontWeight, shell.fontWeight)
        assertEquals(material.lineHeight, shell.lineHeight)
        assertEquals(material.letterSpacing, shell.letterSpacing)
        assertEquals(material.color, shell.color)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MaterialBar() {
        TopAppBar(title = { Text("Material") })
    }

    @Test
    fun `a title style overrides the default, pinned and in the content`() {
        val small = TextStyle(fontSize = 13.sp)
        composeRule.setContent {
            Box(Modifier.fillMaxSize()) {
                PageScaffold(title = "Pinned", titleStyle = small) {}
                PageScaffold(title = "Listed", titleStyle = small, headerPosition = PageHeaderPosition.InContent) {
                    Header()
                }
            }
        }
        assertEquals(13.sp, composeRule.onNodeWithText("Pinned").layout().layoutInput.style.fontSize)
        assertEquals(13.sp, composeRule.onNodeWithText("Listed").layout().layoutInput.style.fontSize)
    }

    @Test
    fun `a one-line title ends in an ellipsis`() {
        composeRule.setContent { PageScaffold(title = "Long", oneLineTitle = true) {} }
        val input = composeRule.onNodeWithText("Long").layout().layoutInput
        assertEquals(1, input.maxLines)
        assertEquals(TextOverflow.Ellipsis, input.overflow)
    }

    @Test
    fun `a title not asked to fit one line wraps`() {
        composeRule.setContent { PageScaffold(title = "Long") {} }
        val input = composeRule.onNodeWithText("Long").layout().layoutInput
        assertEquals(Int.MAX_VALUE, input.maxLines)
        assertEquals(TextOverflow.Clip, input.overflow)
    }

    @Test
    fun `the header slot replaces the bar's parts`() {
        var padding: PaddingValues? = null
        composeRule.setContent {
            PageScaffold(
                title = "Replaced",
                onBack = {},
                header = { Text("Own header", Modifier.height(100.dp)) },
            ) { padding = it }
        }
        composeRule.onNodeWithText("Own header").assertIsDisplayed()
        composeRule.onNodeWithText("Replaced").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(backLabel()).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(100.dp, padding!!.calculateTopPadding()) }
    }

    @Test
    fun `the header slot goes where the page places it`() {
        composeRule.setContent {
            PageScaffold(
                title = "Replaced",
                header = { Text("Own header") },
                headerPosition = PageHeaderPosition.InContent,
            ) {
                Text("before")
                Header()
            }
        }
        composeRule.onNodeWithText("Own header").assertIsDisplayed()
        composeRule.onNodeWithText("Replaced").assertDoesNotExist()
    }

    @Test
    fun `a header in the content leaves no bar above it`() {
        var padding: PaddingValues? = null
        composeRule.setContent {
            PageScaffold(
                title = "Calendar",
                headerPosition = PageHeaderPosition.InContent,
            ) {
                padding = it
                Header()
            }
        }
        composeRule.runOnIdle { assertEquals(0.dp, padding!!.calculateTopPadding()) }
        val title = composeRule.onNodeWithText("Calendar").fetchSemanticsNode().boundsInRoot
        assertEquals(16f * density, title.left, 1f)
        // 16dp of padding, then a row at least 48dp tall with the title centered in it.
        assertEquals(40f * density, title.center.y, 1f)
    }

    @Test
    fun `a pinned header isn't drawn a second time where the content asks`() {
        composeRule.setContent { PageScaffold(title = "Once") { Header() } }
        composeRule.onAllNodesWithText("Once").assertCountEquals(1)
    }

    @Test
    fun `a header in the content has every part and scrolls away with a list`() {
        lateinit var list: LazyListState
        var scrollBy: suspend (Int) -> Unit = {}
        composeRule.setContent {
            list = rememberLazyListState()
            scrollBy = { list.scrollToItem(it) }
            PageScaffold(
                title = "Listed",
                onBack = {},
                headerPosition = PageHeaderPosition.InContent,
                syncDot = { Text("dot") },
                actions = { Text("act") },
                menu = menu(),
            ) {
                LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                    item { Header() }
                    items(100) { Text("row $it", Modifier.height(100.dp)) }
                }
            }
        }
        composeRule.onNodeWithText("Listed").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(backLabel()).assertIsDisplayed()
        composeRule.onNodeWithText("dot").assertIsDisplayed()
        composeRule.onNodeWithText("act").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Page actions").assertIsDisplayed()

        composeRule.runOnIdle { kotlinx.coroutines.runBlocking { scrollBy(20) } }
        composeRule.onNodeWithText("Listed").assertDoesNotExist()
    }

    @Test
    fun `a tab page changes from scrolling to pinned by the position alone`() {
        var padding: PaddingValues? = null
        composeRule.setContent {
            PageScaffold(title = "Calendar", headerPosition = PageHeaderPosition.Pinned) {
                padding = it
                LazyColumn(Modifier.fillMaxSize().padding(it)) {
                    item { Header() }
                    items(100) { row -> Text("row $row", Modifier.height(100.dp)) }
                }
            }
        }
        composeRule.onAllNodesWithText("Calendar").assertCountEquals(1)
        composeRule.onNodeWithText("Calendar").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(56.dp, padding!!.calculateTopPadding()) }
        assertEquals(56f * density, composeRule.onNodeWithText("row 0").top(), 1f)
    }

    @Test
    fun `tab pages' headers scroll with their content for now`() {
        assertEquals(PageHeaderPosition.InContent, PageDefaults.TabHeaderPosition)
    }

    @Test
    fun `a page with its header in the content takes no insets, as its host has padded it`() {
        composeRule.setContent {
            assertSame(NoTopBarInsets, PageDefaults.contentWindowInsets(PageHeaderPosition.InContent))
            assertSame(ScaffoldDefaults.contentWindowInsets, PageDefaults.contentWindowInsets(PageHeaderPosition.Pinned))
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `the page and bar take the colors a page gives them`() {
        var bar = Color.Unspecified
        var themeSurface = Color.Unspecified
        composeRule.setContent {
            MaterialTheme {
                themeSurface = PageDefaults.barColors().containerColor
                bar = PageDefaults.barColors(containerColor = Color.Black).containerColor
                PageScaffold(
                    title = "Dark",
                    barColors = PageDefaults.barColors(containerColor = Color.Black),
                    containerColor = Color.Black,
                    contentColor = Color.White,
                ) {
                    Text("body")
                }
            }
        }
        composeRule.onNodeWithText("Dark").assertIsDisplayed()
        assertEquals(Color.Black, bar)
        assertEquals(false, themeSurface == Color.Black)
        assertEquals(Color.White, composeRule.onNodeWithText("body").layout().layoutInput.style.color)
    }

    @Test
    fun `a bottom bar is shown and the content ends above it`() {
        var padding: PaddingValues? = null
        composeRule.setContent {
            PageScaffold(title = "Scan", bottomBar = { Text("Sources", Modifier.height(80.dp)) }) { padding = it }
        }
        composeRule.onNodeWithText("Sources").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(80.dp, padding!!.calculateBottomPadding()) }
    }

    @Test
    fun `the bar takes the window insets a page gives it, on top of its 56dp`() {
        var padding: PaddingValues? = null
        composeRule.setContent {
            PageScaffold(title = "Viewer", barWindowInsets = WindowInsets(top = 24.dp)) { padding = it }
        }
        composeRule.runOnIdle { assertEquals(80.dp, padding!!.calculateTopPadding()) }
    }

    @Test
    fun `the shared bar height is the 56dp of D7`() {
        assertEquals(56.dp, PageDefaults.BarHeight)
    }
}
