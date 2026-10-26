package org.ntust.app.tigerduck.ui.screen.browser

import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.browser.BrowserAppearance
import org.ntust.app.tigerduck.browser.BrowserDownload
import org.ntust.app.tigerduck.browser.UploadSource
import org.ntust.app.tigerduck.util.replaceIosArg

/**
 * What the browser screen's controls do; the Activity's, a test's stand-in.
 * Every one does nothing unless overridden.
 */
open class BrowserActions {
    open fun close() {}
    open fun back() {}
    open fun forward() {}
    open fun reload() {}
    open fun stop() {}
    open fun startFind() {}
    open fun find(query: String) {}
    open fun findNext(forward: Boolean) {}
    open fun endFind() {}
    open fun share(url: String) {}
    open fun copy(url: String) {}
    open fun openExternal(url: String) {}

    /** The page that did not load, in the phone's browser. */
    open fun openCurrentExternal() {}
    open fun open(url: String) {}
    open fun zoomIn() {}
    open fun zoomOut() {}
    open fun zoomReset() {}
    open fun setDesktopSite(desktop: Boolean) {}
    open fun setAppearance(appearance: BrowserAppearance) {}
    open fun print() {}
    open fun copyCertificate(text: String) {}
    open fun cancelDownload(id: Long) {}
    open fun openDownload(download: BrowserDownload) {}
    open fun retry() {}
    open fun leaveFor(url: String) {}
    open fun stayHere() {}
    open fun closeLinkMenu() {}

    /** The bars back after the page scrolled them away. */
    open fun expandBars() {}
    open fun answerDialog(confirmed: Boolean, value: String?) {}
    open fun pickUpload(source: UploadSource) {}
    open fun cancelUpload() {}
    open fun retrySignIn() {}
}

/**
 * The TigerDuck browser, layout A: a top bar with Close and the address
 * (host and the page's title; a tap shows the whole address), a thin
 * progress line, the page, and a bottom bar with Back, Forward, Reload or
 * Stop, Find and ⋮, whose menu shares. [web] draws the page; the screen
 * draws everything around and over it.
 *
 * As the page scrolls down the bars collapse ([BrowserPageState.barsCollapsed]),
 * as the iPhone's do: the top bar shrinks to the host, whose tap brings them
 * back, and the bottom bar goes; the page grows into the room, and the
 * progress line stays. Collapsed buttons take no taps and are gone from
 * TalkBack. The bars stay shown while TalkBack is on, and while a sheet, the
 * find bar, a dialog, an error page or the sign-in screen is up. With
 * animations removed in the phone's settings they change at once.
 */
@Composable
fun InAppBrowserScreen(
    state: BrowserPageState,
    appearance: BrowserAppearance,
    downloads: List<BrowserDownload>,
    actions: BrowserActions,
    web: @Composable (Modifier) -> Unit,
) {
    // The browser's own sheet, as its pages: the one it opened at, then each opened from the one before.
    var pages by rememberSaveable(stateSaver = BrowserSheetPagesSaver) { mutableStateOf(emptyList<BrowserSheet>()) }
    val colors = MaterialTheme.colorScheme

    val talkBack = rememberTouchExploration()
    val barsHeld = talkBack || pages.isNotEmpty() || state.find != null || state.dialog != null || state.upload != null ||
        state.linkMenuUrl != null || state.outsideUrl != null || state.error != null || state.signIn != null
    val collapsed = state.barsCollapsed && !barsHeld
    // Held, they come back for good, till the page scrolls down again.
    val heldCollapsed = barsHeld && state.barsCollapsed
    LaunchedEffect(heldCollapsed) { if (heldCollapsed) actions.expandBars() }

    // Text and icons that name no color take the bars' own: a background alone leaves them
    // black, which dark bars hide.
    CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
        Column(
            Modifier
                .fillMaxSize()
                .background(colors.surface)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            val find = state.find
            if (find != null) {
                FindBar(find, actions)
            } else {
                TopBar(
                    state,
                    collapsed = collapsed,
                    onAddress = { if (collapsed) actions.expandBars() else pages = listOf(BrowserSheet.ADDRESS) },
                    onClose = actions::close,
                )
            }
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                if (state.loading) {
                    LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
                } else {
                    HorizontalDivider(Modifier.fillMaxWidth(), color = colors.outlineVariant)
                }
            }
            // Clipped: a WebView with nothing drawn yet fills its whole canvas with its background,
            // which, unclipped, painted over the bars until the page's first frame.
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                web(Modifier.fillMaxSize())
                state.error?.let { BrowserErrorPage(it, actions, Modifier.fillMaxSize()) }
                state.signIn?.let { BrowserSignInPage(it, actions, Modifier.fillMaxSize()) }
                DownloadCards(
                    downloads = downloads,
                    actions = actions,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                )
            }
            AnimatedVisibility(
                visible = !collapsed,
                enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
            ) {
                Column {
                    HorizontalDivider(color = colors.outlineVariant)
                    // Off the moment the bars collapse, while they still fade.
                    BottomBar(state, actions, usable = !collapsed, onMenu = { pages = listOf(BrowserSheet.MENU) })
                }
            }
        }
    }

    val url = state.url
    if (pages.isNotEmpty()) {
        fun open(page: BrowserSheet) {
            pages = pages + page
        }
        val close = { pages = emptyList() }
        BrowserPagesSheet(pages, onBack = { pages = pages.dropLast(1) }, onDismiss = close) { page, onBack ->
            when (page) {
                BrowserSheet.MENU -> BrowserMenuPage(
                    state = state,
                    appearance = appearance,
                    actions = actions,
                    onAddress = { open(BrowserSheet.ADDRESS) },
                    onCertificate = { open(BrowserSheet.CERTIFICATE) },
                    onDownloads = { open(BrowserSheet.DOWNLOADS) },
                    onDismiss = close,
                )
                BrowserSheet.ADDRESS -> if (url != null) {
                    BrowserAddressPage(state, url, actions, onCertificate = { open(BrowserSheet.CERTIFICATE) }, onBack = onBack, onDismiss = close)
                } else {
                    LaunchedEffect(Unit) { close() }
                }
                BrowserSheet.CERTIFICATE -> BrowserCertificatePage(state, actions, onBack)
                BrowserSheet.DOWNLOADS -> BrowserDownloadsPage(downloads, actions, onBack)
            }
        }
    }
    state.upload?.let { BrowserUploadSheet(it, actions) }
    state.linkMenuUrl?.let { BrowserLinkSheet(it, actions) }
    state.outsideUrl?.let { BrowserOutsideDialog(it, actions) }
    state.dialog?.let { BrowserPageDialog(it, actions) }
}

/** The pages of the browser's own sheet. */
internal enum class BrowserSheet { MENU, ADDRESS, CERTIFICATE, DOWNLOADS }

/** Keeps the sheet's pages through a rotation, by name. */
private val BrowserSheetPagesSaver = listSaver<List<BrowserSheet>, String>(
    save = { pages -> pages.map { it.name } },
    restore = { names -> names.map(BrowserSheet::valueOf) },
)

/** The top bar; [collapsed], the host alone in a shorter bar, whose tap ([onAddress]) brings the bars back. */
@Composable
private fun TopBar(state: BrowserPageState, collapsed: Boolean, onAddress: () -> Unit, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val host = state.host.orEmpty()
    val addressLabel = stringResource(R.string.browser_address).replaceIosArg(1, host)
    val height by animateDpAsState(
        (if (collapsed) BrowserChromeCollapse.TOP_BAR_COLLAPSED_DP else BrowserChromeCollapse.TOP_BAR_DP).dp,
        label = "browser top bar",
    )
    Row(
        Modifier.fillMaxWidth().height(height).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(visible = !collapsed, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
            // Off the moment the bars collapse, while it still fades.
            IconButton(onClick = onClose, enabled = !collapsed, modifier = if (collapsed) Modifier.clearAndSetSemantics {} else Modifier) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.browser_close))
            }
        }
        Column(
            Modifier
                .weight(1f)
                .clickable(onClickLabel = if (collapsed) null else stringResource(R.string.browser_show_address), role = Role.Button, onClick = onAddress)
                .semantics(mergeDescendants = true) { contentDescription = addressLabel }
                .padding(horizontal = 4.dp, vertical = if (collapsed) 2.dp else 6.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = if (collapsed) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.isHttps) {
                    Icon(
                        if (state.certificateRefused) Icons.Filled.GppBad else Icons.Filled.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = colors.onSurface,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    host,
                    style = if (collapsed) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val title = state.title?.takeIf { it.isNotBlank() && it != state.url && it != host }
            if (title != null && !collapsed) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
    }
}

/** Takes every touch for itself, before anything under it sees one. */
private fun Modifier.noTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}

/** Whether TalkBack (touch exploration) is on, as it changes. */
@Composable
private fun rememberTouchExploration(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var on by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { on = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return on
}

@Composable
private fun FindBar(find: FindState, actions: BrowserActions) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = actions::endFind) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.browser_find_done))
        }
        val field = stringResource(R.string.browser_find_field)
        TextField(
            value = find.query,
            onValueChange = actions::find,
            modifier = Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = field },
            placeholder = { Text(field) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { actions.findNext(true) }),
            trailingIcon = {
                if (find.query.isNotEmpty() && find.done) {
                    Text(
                        if (find.count == 0) stringResource(R.string.browser_find_none) else "${find.ordinal}/${find.count}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
            },
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = MaterialTheme.colorScheme.surface,
                unfocusedIndicatorColor = MaterialTheme.colorScheme.surface,
            ),
            shape = MaterialTheme.shapes.extraLarge,
        )
        IconButton(onClick = { actions.findNext(false) }, enabled = find.count > 0) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.browser_find_previous))
        }
        IconButton(onClick = { actions.findNext(true) }, enabled = find.count > 0) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.browser_find_next))
        }
    }
}

@Composable
private fun BottomBar(state: BrowserPageState, actions: BrowserActions, usable: Boolean, onMenu: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(BrowserChromeCollapse.BOTTOM_BAR_DP.dp)
            // Going away: gone from TalkBack, and no touch or key reaches its buttons.
            .then(if (usable) Modifier else Modifier.clearAndSetSemantics {}.noTouches().onPreviewKeyEvent { true }),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = actions::back, enabled = state.canGoBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.browser_back))
        }
        IconButton(onClick = actions::forward, enabled = state.canGoForward) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.browser_forward))
        }
        if (state.loading) {
            IconButton(onClick = actions::stop) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.browser_stop))
            }
        } else {
            IconButton(onClick = actions::reload) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.browser_reload))
            }
        }
        IconButton(onClick = actions::startFind, enabled = state.error == null && state.signIn == null) {
            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.browser_find))
        }
        // Share is in its menu.
        IconButton(onClick = onMenu) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.browser_more))
        }
    }
}
