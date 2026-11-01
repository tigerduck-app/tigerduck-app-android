package org.ntust.app.tigerduck.ui.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R

/** Where a page's header sits: see [PageScaffold]. */
enum class PageHeaderPosition {
    /** A bar above the content that stays put. Every page opened from a list has one. */
    Pinned,

    /**
     * Inside the content, wherever the page calls [PageScope.Header]: as a list's first item it
     * scrolls away with the list, which is what the tab pages' headers do.
     */
    InContent,
}

/** What [PageScaffold] offers its content. */
interface PageScope {
    /**
     * The header of a [PageHeaderPosition.InContent] page, for the page to place. A pinned header
     * is already on screen, so here it draws nothing rather than a second copy.
     */
    @Composable
    fun Header()
}

/** What [PageScaffold] uses where a page doesn't say, in the shape of Material's `TopAppBarDefaults`. */
object PageDefaults {
    /**
     * Whether a tab page's header scrolls with its content or stays put, the one place that says.
     * Tab pages apply the padding [PageScaffold] gives them and place [PageScope.Header], which
     * draws nothing when pinned, so changing this pins every one of them.
     */
    val TabHeaderPosition: PageHeaderPosition = PageHeaderPosition.InContent

    /** Below M3's 64dp so a page leads with its content rather than its chrome (D7). */
    val BarHeight: Dp = 56.dp

    val HeaderPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 16.dp)

    /** A header with no actions stands as tall as one with them, so the title doesn't move between pages. */
    val HeaderMinHeight: Dp = 48.dp

    val MenuShape: Shape = RoundedCornerShape(12.dp)

    /** Pinned or in the content, a title is this. Sizes stay on the theme's typography (D9). */
    val titleStyle: TextStyle
        @Composable get() = MaterialTheme.typography.titleLarge

    val containerColor: Color
        @Composable get() = MaterialTheme.colorScheme.background

    /** The bar's own insets. Nothing, because a page's host already pads it: see [NoTopBarInsets]. */
    val barWindowInsets: WindowInsets get() = NoTopBarInsets

    /** For a bar on a page no host pads, such as one of its own window or a full-screen dialog. */
    val systemBarWindowInsets: WindowInsets
        @Composable get() = TopAppBarDefaults.windowInsets

    /**
     * The Scaffold's own default for a page under a pinned bar. A header in the content means a
     * tab page, whose host has padded it whole, so a stray inset can't push the header down.
     */
    @Composable
    fun contentWindowInsets(headerPosition: PageHeaderPosition): WindowInsets =
        if (headerPosition == PageHeaderPosition.InContent) NoTopBarInsets else ScaffoldDefaults.contentWindowInsets

    /** Material's bar colors. [containerColor], unspecified, stays Material's. */
    @Composable
    fun barColors(containerColor: Color = Color.Unspecified): TopAppBarColors =
        TopAppBarDefaults.topAppBarColors(containerColor = containerColor)
}

/**
 * The one shell of a page. A new page needs a title and its content, and a page opened from a list
 * adds the way back:
 *
 * ```
 * PageScaffold(title = stringResource(R.string.feature_x), onBack = onBack) { padding ->
 *     LazyColumn(Modifier.padding(padding)) { ... }
 * }
 * ```
 *
 * The header's parts sit in one order on every page, on one row: [navigationIcon] or the arrow of
 * [onBack], the title, [syncDot], [decoration], [actions], [menu]. A part a page doesn't give takes
 * no room. [header] replaces all of them.
 *
 * A tab page's header goes by [PageDefaults.TabHeaderPosition], and the page places it, here as
 * its list's first item, so that it scrolls with the list:
 *
 * ```
 * PageScaffold(
 *     title = stringResource(R.string.feature_x),
 *     headerPosition = PageDefaults.TabHeaderPosition,
 *     syncDot = { SyncStatusDot(servers, isLoading) },
 * ) { padding ->
 *     LazyColumn(Modifier.padding(padding)) { item { Header() } ... }
 * }
 * ```
 *
 * [content] receives the Scaffold's padding, which is the bar's height; apply it to the page's
 * first child. The page sits on [containerColor], with the Scaffold's [bottomBar], [snackbarHost]
 * and [floatingActionButton] slots, all empty by default. [contentWindowInsets] goes by
 * [PageDefaults.contentWindowInsets]; pass `WindowInsets(0, 0, 0, 0)` for a page whose host has
 * padded the whole screen.
 *
 * The bar is always [PageDefaults.BarHeight] tall, and zeroes its window insets because whatever
 * hosts a page already pads it clear of the system bars. A new host has to do the same, or pass
 * [PageDefaults.systemBarWindowInsets] as [barWindowInsets]; otherwise the back button lands under
 * the status bar.
 *
 * @param onBack draws the back arrow; null hides it.
 * @param navigationIcon takes the arrow's place, for a page whose start edge isn't a way back.
 * @param oneLineTitle ends a long title with an ellipsis rather than wrapping it.
 * @param syncDot the page's `SyncStatusDot`, first of what sits after the title.
 * @param decoration a mark that isn't an action, between the dot and the actions.
 * @param menu the three-dot menu, last on the row. Null, for a page with nothing to put in one,
 *   draws no button: a menu is never empty.
 * @param barColors the colors of a pinned bar; a header in the content takes the page's.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    titleStyle: TextStyle = PageDefaults.titleStyle,
    oneLineTitle: Boolean = false,
    syncDot: @Composable () -> Unit = {},
    decoration: @Composable RowScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    menu: PageMenu? = null,
    header: (@Composable () -> Unit)? = null,
    headerPosition: PageHeaderPosition = PageHeaderPosition.Pinned,
    barColors: TopAppBarColors = PageDefaults.barColors(),
    barWindowInsets: WindowInsets = PageDefaults.barWindowInsets,
    containerColor: Color = PageDefaults.containerColor,
    contentColor: Color = contentColorFor(containerColor),
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    contentWindowInsets: WindowInsets = PageDefaults.contentWindowInsets(headerPosition),
    content: @Composable PageScope.(PaddingValues) -> Unit,
) {
    val navigation: @Composable () -> Unit = navigationIcon ?: { onBack?.let { PageBackButton(it) } }
    val afterTitle: @Composable RowScope.() -> Unit = {
        syncDot()
        decoration()
        actions()
        menu?.let { PageMenuButton(it) }
    }
    val pinned = headerPosition == PageHeaderPosition.Pinned
    val placedHeader: @Composable () -> Unit = header ?: {
        if (pinned) {
            TopAppBar(
                windowInsets = barWindowInsets,
                title = { if (title.isNotEmpty()) PageTitle(title, titleStyle, oneLineTitle) },
                navigationIcon = navigation,
                actions = afterTitle,
                expandedHeight = PageDefaults.BarHeight,
                colors = barColors,
            )
        } else {
            PageHeader(
                title = title,
                navigationIcon = navigation,
                titleStyle = titleStyle,
                oneLineTitle = oneLineTitle,
                actions = afterTitle,
            )
        }
    }
    Scaffold(
        topBar = { if (pinned) placedHeader() },
        bottomBar = bottomBar,
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
        containerColor = containerColor,
        contentColor = contentColor,
        contentWindowInsets = contentWindowInsets,
    ) { padding ->
        PageContentScope(if (pinned) ({}) else placedHeader).content(padding)
    }
}

private class PageContentScope(private val header: @Composable () -> Unit) : PageScope {
    @Composable
    override fun Header() = header()
}

@Composable
private fun PageBackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
    }
}

@Composable
internal fun PageTitle(title: String, style: TextStyle, oneLine: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = title,
        modifier = modifier,
        style = style,
        maxLines = if (oneLine) 1 else Int.MAX_VALUE,
        overflow = if (oneLine) TextOverflow.Ellipsis else TextOverflow.Clip,
    )
}
