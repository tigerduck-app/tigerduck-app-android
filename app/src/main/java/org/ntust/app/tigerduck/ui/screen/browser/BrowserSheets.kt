package org.ntust.app.tigerduck.ui.screen.browser

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.browser.BrowserAppearance
import org.ntust.app.tigerduck.browser.BrowserDownload
import org.ntust.app.tigerduck.browser.BrowserZoom
import org.ntust.app.tigerduck.browser.CertificateInfo
import org.ntust.app.tigerduck.browser.UploadSource
import org.ntust.app.tigerduck.ui.component.DetentSheet
import org.ntust.app.tigerduck.ui.component.DetentSheetMotion
import org.ntust.app.tigerduck.ui.component.rememberDetentSheetState
import org.ntust.app.tigerduck.util.replaceIosArg
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import kotlinx.coroutines.launch

@Composable
private fun BrowserSheetFrame(onDismiss: () -> Unit, skipPartial: Boolean = true, content: @Composable () -> Unit) {
    DetentSheet(onDismissed = onDismiss, halfHeight = !skipPartial) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            content()
        }
    }
}

/**
 * The menu stepping out of the way of the page while its zoom changes, so the change can be
 * watched: [active] from each change ([poke]) until a second after the last, or until a tap where
 * the menu was ([end]). The sheet goes see-through and only the zoom controls stay.
 */
@Stable
internal class ZoomPeek {
    var active by mutableStateOf(false)
        private set

    /** Counts the changes, so each one starts the second again. */
    internal var changes by mutableIntStateOf(0)
        private set

    fun poke() {
        active = true
        changes++
    }

    fun end() {
        active = false
    }

    companion object {
        const val HOLD_MILLIS = 1_000L
    }
}

@Composable
private fun ZoomPeek.EndASecondAfterTheLastChange() {
    LaunchedEffect(changes) {
        if (active) {
            delay(ZoomPeek.HOLD_MILLIS)
            end()
        }
    }
}

internal val LocalZoomPeek = staticCompositionLocalOf<ZoomPeek?> { null }

/**
 * What of the menu steps aside while [peek] holds: faded with the sheet, out of TalkBack's and the
 * keyboard's way, and taking no taps. A tap where it was brings the menu back at once; a drag from
 * there still drags or scrolls the sheet.
 */
private fun Modifier.stepsAside(peek: ZoomPeek?, solid: State<Float>): Modifier {
    if (peek == null) return this
    val faded = graphicsLayer { alpha = solid.value }
    if (!peek.active) return faded
    return faded
        .clearAndSetSemantics {}
        // Focus doesn't go in: a keyboard could otherwise still work what can't be seen.
        .focusProperties { onEnter = { cancelFocusChange() } }
        .focusGroup()
        .pointerInput(peek) {
            awaitEachGesture {
                // Taken here, so the hidden rows don't see it; their parents still do, and drag or
                // scroll as the finger goes.
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                down.consume()
                var moved = false
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes
                        .firstOrNull { it.id == down.id } ?: break
                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                    if (!change.pressed) {
                        if (!moved) {
                            change.consume()
                            peek.end()
                        }
                        break
                    }
                }
            }
        }
}

/**
 * The browser's own sheet: one sheet for the menu and the pages it leads to ([pages], the first
 * page the sheet opened at and those opened from it since). A page opened from another comes up in
 * the same sheet, which grows or shrinks to it, and Back goes to the page before it; Back on the
 * first page, a tap outside or a drag down closes the sheet. [page] draws a page, with a way back
 * when it has one.
 */
@Composable
internal fun BrowserPagesSheet(
    pages: List<BrowserSheet>,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    page: @Composable (sheet: BrowserSheet, onBack: (() -> Unit)?) -> Unit,
) {
    val sheetState = rememberDetentSheetState()
    val scope = rememberCoroutineScope()
    val zoomPeek = remember { ZoomPeek() }
    zoomPeek.EndASecondAfterTheLastChange()
    // Each page's own state, its scroll above all, kept while it is in the stack: back on a page,
    // it is as it was left. A page gone from the stack starts afresh when opened again.
    val pageStates = rememberSaveableStateHolder()
    val keys = pages.mapIndexed { depth, sheet -> pageKey(depth + 1, sheet) }
    var kept by remember { mutableStateOf(keys) }
    LaunchedEffect(keys) {
        (kept - keys.toSet()).forEach(pageStates::removeState)
        kept = keys
    }
    DetentSheet(
        onDismissed = onDismiss,
        state = sheetState,
        // Back is the pages' first: on the first page, it closes the sheet as the sheet's own would.
        dismissOnBack = false,
        seeThrough = zoomPeek.active,
    ) {
        BackHandler {
            if (pages.size > 1) onBack() else scope.launch { sheetState.hide() }
        }
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        CompositionLocalProvider(LocalZoomPeek provides zoomPeek) {
        Box(Modifier.fillMaxWidth().testTag(BROWSER_SHEET_TAG)) {
            AnimatedContent(
                targetState = pages.size to pages.last(),
                transitionSpec = {
                    // Onward from the end, back from the start; a quarter of the way, under a fade.
                    val onward = targetState.first > initialState.first
                    val from = if (onward != rtl) 1 else -1
                    (slideInHorizontally { from * it / 4 } + fadeIn()) togetherWith
                        (slideOutHorizontally { -from * it / 4 } + fadeOut()) using SizeTransform(clip = false)
                },
                label = "browser sheet page",
            ) { (depth, sheet) ->
                val title = stringResource(
                    when (sheet) {
                        BrowserSheet.MENU -> R.string.browser_more
                        BrowserSheet.ADDRESS -> R.string.browser_address_title
                        BrowserSheet.CERTIFICATE -> R.string.browser_certificate_title
                        BrowserSheet.DOWNLOADS -> R.string.browser_downloads
                    },
                )
                pageStates.SaveableStateProvider(pageKey(depth, sheet)) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            // A new pane in the sheet, not the sheet closing and another opening.
                            .semantics { paneTitle = title }
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 16.dp),
                    ) {
                        page(sheet, if (depth > 1) onBack else null)
                    }
                }
            }
        }
        }
    }
}

/** A page's place in the sheet's stack: the same page at another depth is another entry. */
private fun pageKey(depth: Int, sheet: BrowserSheet) = "$depth:${sheet.name}"

/** A page's title, with the way back to the page before it when there is one; TalkBack goes to it. */
@Composable
private fun PageTitle(text: String, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            // Its arrow lines up with the page's text below.
            IconButton(onClick = onBack, modifier = Modifier.offset(x = (-12).dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
        }
        Text(
            text,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() }.focusRequester(focus).focusable(),
        )
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/** The browser's own sheet, for tests to tell one sheet from another. */
internal const val BROWSER_SHEET_TAG = "browserSheet"

/** The page's host and title at the top of a sheet that acts on it. */
@Composable
private fun PageHeader(state: BrowserPageState, onCertificate: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val lock: @Composable () -> Unit = {
            Icon(
                when {
                    state.certificateRefused -> Icons.Filled.GppBad
                    state.isHttps -> Icons.Filled.Lock
                    else -> Icons.Filled.LockOpen
                },
                contentDescription = null,
                modifier = Modifier.padding(9.dp),
            )
        }
        val shape = RoundedCornerShape(18.dp)
        val color = MaterialTheme.colorScheme.surfaceVariant
        if (onCertificate != null) {
            // The lock is the way to the certificate, as it is in a phone's browser.
            val label = stringResource(R.string.browser_certificate)
            Surface(onClick = onCertificate, shape = shape, color = color, modifier = Modifier.size(36.dp).semantics { contentDescription = label }) { lock() }
        } else {
            Surface(shape = shape, color = color, modifier = Modifier.size(36.dp)) { lock() }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(state.host.orEmpty(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            state.title?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(48.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(13.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 2)
    }
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    showArrow: Boolean = false,
    horizontalPadding: Dp = 20.dp,
    iconGap: Dp = 16.dp,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(iconGap))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (showArrow) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The ⋮ menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserMenuPage(
    state: BrowserPageState,
    appearance: BrowserAppearance,
    actions: BrowserActions,
    onAddress: () -> Unit,
    onCertificate: () -> Unit,
    onDownloads: () -> Unit,
    onDismiss: () -> Unit,
) {
    val url = state.url
    fun act(block: () -> Unit) {
        onDismiss()
        block()
    }
    val peek = LocalZoomPeek.current
    // 1 shown, 0 stepped aside, in step with the sheet's own fade.
    val solid = animateFloatAsState(
        if (peek?.active == true) 0f else 1f,
        animationSpec = tween(DetentSheetMotion.SEE_THROUGH_MILLIS),
        label = "zoom peek",
    )
    val aside = Modifier.stepsAside(peek, solid)
    fun zoom(change: () -> Unit) {
        change()
        peek?.poke()
    }
    Column(Modifier.fillMaxWidth()) {
        Box(aside) { PageHeader(state, onCertificate = onCertificate.takeIf { state.shownCertificate != null }) }
        Row(Modifier.fillMaxWidth().then(aside).padding(horizontal = 8.dp)) {
            QuickAction(Icons.Filled.ContentCopy, stringResource(R.string.browser_copy_short), enabled = url != null, onClick = { act { url?.let(actions::copy) } }, modifier = Modifier.weight(1f))
            QuickAction(Icons.Filled.Share, stringResource(R.string.browser_share_short), enabled = url != null, onClick = { act { url?.let(actions::share) } }, modifier = Modifier.weight(1f))
            QuickAction(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.browser_open_external_short), enabled = url != null, onClick = { act { url?.let(actions::openExternal) } }, modifier = Modifier.weight(1f))
            QuickAction(Icons.Filled.Refresh, stringResource(R.string.browser_reload), onClick = { act(actions::reload) }, modifier = Modifier.weight(1f))
        }
        HorizontalDivider(aside.padding(horizontal = 20.dp, vertical = 4.dp))

        // Zoom: − and + only; the percentage puts it back to 100%. While the zoom changes, the rest
        // of the menu steps aside and these keep a pill of their own over the page.
        val locale = LocalConfiguration.current.locales[0]
        val percent = remember(state.zoom, locale) { NumberFormat.getPercentInstance(locale).format(state.zoom / 100.0) }
        val pill = MaterialTheme.colorScheme.surfaceContainerHigh
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(aside.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ZoomIn, contentDescription = null)
                Spacer(Modifier.width(16.dp))
                Text(stringResource(R.string.browser_zoom), style = MaterialTheme.typography.bodyLarge)
            }
            Row(
                Modifier.drawBehind {
                    drawRoundRect(pill, alpha = 1f - solid.value, cornerRadius = CornerRadius(size.height / 2))
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedIconButton(onClick = { zoom(actions::zoomOut) }, enabled = BrowserZoom.canZoomOut(state.zoom)) {
                    Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.browser_zoom_out))
                }
                TextButton(onClick = { zoom(actions::zoomReset) }, colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current)) {
                    // As wide as it is, and no narrower than 48dp, so it stays put from 50% to 200%.
                    Text(percent, modifier = Modifier.widthIn(min = 48.dp).width(IntrinsicSize.Max), maxLines = 1, textAlign = TextAlign.Center)
                }
                OutlinedIconButton(onClick = { zoom(actions::zoomIn) }, enabled = BrowserZoom.canZoomIn(state.zoom)) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.browser_zoom_in))
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .then(aside)
                .heightIn(min = 56.dp)
                .clickable(role = Role.Switch) { actions.setDesktopSite(!state.desktopSite) }
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.DesktopWindows, contentDescription = null)
            Spacer(Modifier.width(16.dp))
            Text(stringResource(R.string.browser_desktop_site), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = state.desktopSite, onCheckedChange = null)
        }

        Column(Modifier.fillMaxWidth().then(aside).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.DarkMode, contentDescription = null)
                Spacer(Modifier.width(16.dp))
                Text(stringResource(R.string.browser_appearance), style = MaterialTheme.typography.bodyLarge)
            }
            val options = listOf(
                BrowserAppearance.AUTO to stringResource(R.string.common_automatic),
                BrowserAppearance.LIGHT to stringResource(R.string.browser_appearance_light),
                BrowserAppearance.DARK to stringResource(R.string.browser_appearance_dark),
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = 40.dp)) {
                options.forEachIndexed { i, (value, label) ->
                    SegmentedButton(
                        selected = appearance == value,
                        onClick = { actions.setAppearance(value) },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    ) { Text(label, maxLines = 1) }
                }
            }
        }

        Column(aside) {
            MenuRow(Icons.Filled.Search, stringResource(R.string.browser_find), enabled = state.error == null && state.signIn == null) { act(actions::startFind) }
            MenuRow(Icons.Filled.Print, stringResource(R.string.browser_print), enabled = url != null && state.error == null && state.signIn == null) { act(actions::print) }
            MenuRow(Icons.Filled.Download, stringResource(R.string.browser_downloads), showArrow = true, onClick = onDownloads)
            MenuRow(
                if (state.certificateRefused) Icons.Filled.GppBad else Icons.Filled.VerifiedUser,
                stringResource(R.string.browser_security),
                enabled = url != null,
                showArrow = true,
                onClick = onAddress,
            )
        }
    }
}

/** The whole address of the page, its connection and its sign-in. */
@Composable
internal fun BrowserAddressPage(
    state: BrowserPageState,
    url: String,
    actions: BrowserActions,
    onCertificate: () -> Unit,
    onBack: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PageTitle(stringResource(R.string.browser_address_title), onBack)
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                SelectionContainer {
                    Text(url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(12.dp))
                }
            }
            SideBySideOrStacked(spacing = 8.dp) {
                // Less padding than a button's own, so the three fit side by side at the usual sizes.
                val padding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                Button(onClick = { actions.copy(url) }, contentPadding = padding) { Text(stringResource(R.string.browser_copy_short), maxLines = 1) }
                OutlinedButton(onClick = { actions.share(url) }, contentPadding = padding) { Text(stringResource(R.string.browser_share_short), maxLines = 1) }
                OutlinedButton(onClick = { onDismiss(); actions.openExternal(url) }, contentPadding = padding) {
                    Text(stringResource(R.string.browser_open_external_short), maxLines = 1)
                }
            }
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column {
                    when {
                        // Encrypted, but to a certificate the browser refused: neither secure nor unencrypted.
                        state.certificateRefused -> InfoRow(Icons.Filled.GppBad, stringResource(R.string.browser_certificate_invalid), detail = null)
                        state.isHttps -> InfoRow(
                            Icons.Filled.Lock,
                            stringResource(R.string.browser_connection_secure),
                            stringResource(R.string.browser_connection_secure_detail),
                        )
                        else -> InfoRow(Icons.Filled.LockOpen, stringResource(R.string.browser_connection_insecure), detail = null)
                    }
                    if (state.signedIn) {
                        HorizontalDivider()
                        InfoRow(icon = Icons.Filled.Person, title = stringResource(R.string.browser_signed_in), detail = null)
                    }
                    HorizontalDivider()
                    MenuRow(
                        Icons.Filled.VerifiedUser,
                        stringResource(R.string.browser_certificate),
                        enabled = state.shownCertificate != null,
                        showArrow = true,
                        // InfoRow's, so its icon and text line up with the rows above.
                        horizontalPadding = 16.dp,
                        iconGap = 12.dp,
                        onClick = onCertificate,
                    )
                }
            }
        }
    }
}

/**
 * [content]'s buttons side by side in equal widths when each one's label fits its share, else one
 * under another at full width: no label is cut, whatever the language or the text size.
 */
@Composable
private fun SideBySideOrStacked(spacing: Dp, content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val width = constraints.maxWidth
        val share = (width - gap * (measurables.size - 1)) / measurables.size
        if (measurables.all { it.maxIntrinsicWidth(Constraints.Infinity) <= share }) {
            val placeables = measurables.map { it.measure(Constraints.fixedWidth(share)) }
            val height = placeables.maxOf { it.height }
            layout(width, height) {
                placeables.forEachIndexed { i, placeable -> placeable.placeRelative(i * (share + gap), (height - placeable.height) / 2) }
            }
        } else {
            val placeables = measurables.map { it.measure(Constraints.fixedWidth(width)) }
            layout(width, placeables.sumOf { it.height } + gap * (placeables.size - 1)) {
                var y = 0
                placeables.forEach { placeable ->
                    placeable.placeRelative(0, y)
                    y += placeable.height + gap
                }
            }
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, detail: String?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** The page certificate's details, and a copy of them as text. */
@Composable
internal fun BrowserCertificatePage(state: BrowserPageState, actions: BrowserActions, onBack: (() -> Unit)?) {
    val info = state.shownCertificate
    val locale = LocalConfiguration.current.locales[0]
    val dates = remember(locale) { DateFormat.getDateInstance(DateFormat.MEDIUM, locale) }
    fun date(d: Date?) = d?.let(dates::format) ?: "—"
    val issuedTo = stringResource(R.string.browser_certificate_issued_to)
    val issuedBy = stringResource(R.string.browser_certificate_issued_by)
    val commonName = stringResource(R.string.browser_certificate_common_name)
    val organization = stringResource(R.string.browser_certificate_organization)
    val validity = stringResource(R.string.browser_certificate_validity)
    val notBefore = stringResource(R.string.browser_certificate_not_before)
    val notAfter = stringResource(R.string.browser_certificate_not_after)
    val fingerprint = stringResource(R.string.browser_certificate_fingerprint)
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PageTitle(stringResource(R.string.browser_certificate_title), onBack)
            if (info == null) {
                Text(stringResource(R.string.browser_certificate_none), style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            if (state.certificateRefused) {
                InfoRow(Icons.Filled.GppBad, state.host.orEmpty(), stringResource(R.string.browser_certificate_invalid))
            } else {
                InfoRow(Icons.Filled.VerifiedUser, state.host.orEmpty(), stringResource(R.string.browser_certificate_valid))
            }
            CertificateGroup(issuedTo, listOf(commonName to info.issuedToName, organization to info.issuedToOrganization))
            CertificateGroup(issuedBy, listOf(commonName to info.issuedByName, organization to info.issuedByOrganization))
            CertificateGroup(validity, listOf(notBefore to date(info.validFrom), notAfter to date(info.validUntil)))
            info.sha256?.let { CertificateGroup(fingerprint, listOf("SHA-256" to it), monospace = true) }
            OutlinedButton(
                onClick = {
                    actions.copyCertificate(
                        certificateText(info, state.host, issuedTo, issuedBy, commonName, organization, validity, notBefore, notAfter, fingerprint, ::date),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.browser_certificate_copy)) }
        }
    }
}

/** The certificate as lines of text, under the same labels as the page shows. */
internal fun certificateText(
    info: CertificateInfo,
    host: String?,
    issuedTo: String,
    issuedBy: String,
    commonName: String,
    organization: String,
    validity: String,
    notBefore: String,
    notAfter: String,
    fingerprint: String,
    date: (Date?) -> String,
): String = buildString {
    host?.let { appendLine(it) }
    appendLine(issuedTo)
    appendLine("  $commonName: ${info.issuedToName ?: "—"}")
    appendLine("  $organization: ${info.issuedToOrganization ?: "—"}")
    appendLine(issuedBy)
    appendLine("  $commonName: ${info.issuedByName ?: "—"}")
    appendLine("  $organization: ${info.issuedByOrganization ?: "—"}")
    appendLine(validity)
    appendLine("  $notBefore: ${date(info.validFrom)}")
    appendLine("  $notAfter: ${date(info.validUntil)}")
    info.sha256?.let {
        appendLine(fingerprint)
        append("  SHA-256: $it")
    }
}.trimEnd()

@Composable
private fun CertificateGroup(title: String, rows: List<Pair<String, String?>>, monospace: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column {
                rows.forEachIndexed { i, (label, value) ->
                    if (i > 0) HorizontalDivider()
                    if (monospace) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SelectionContainer { Text(value ?: "—", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SelectionContainer(Modifier.weight(1f)) {
                                Text(value ?: "—", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
    }
}

/** This run's downloads. */
@Composable
internal fun BrowserDownloadsPage(downloads: List<BrowserDownload>, actions: BrowserActions, onBack: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth()) {
        PageTitle(stringResource(R.string.browser_downloads), onBack, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        if (downloads.isEmpty()) {
            Text(stringResource(R.string.browser_downloads_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        }
        downloads.forEach { DownloadRow(it, actions) }
    }
}

@Composable
private fun DownloadRow(download: BrowserDownload, actions: BrowserActions) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(download.fileName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (download.state == BrowserDownload.State.RUNNING) {
                val total = download.total
                if (total != null && total > 0) {
                    LinearProgressIndicator(progress = { (download.bytes.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            Text(downloadStatus(download), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (download.state) {
            BrowserDownload.State.RUNNING -> IconButton(onClick = { actions.cancelDownload(download.id) }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.browser_download_cancel))
            }
            BrowserDownload.State.DONE -> TextButton(onClick = { actions.openDownload(download) }) { Text(stringResource(R.string.browser_download_open)) }
            else -> Unit
        }
    }
}

/** A download's state line: progress while it runs, its size once done. */
@Composable
internal fun downloadStatus(download: BrowserDownload): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    fun size(bytes: Long) = android.text.format.Formatter.formatShortFileSize(context, bytes)
    return when (download.state) {
        BrowserDownload.State.RUNNING -> {
            val total = download.total
            val amount = if (total != null && total > 0) "${size(download.bytes)} / ${size(total)}" else size(download.bytes)
            stringResource(R.string.browser_download_progress).replaceIosArg(1, amount)
        }
        BrowserDownload.State.DONE -> stringResource(R.string.browser_download_done).replaceIosArg(1, size(download.bytes))
        BrowserDownload.State.FAILED -> stringResource(R.string.browser_download_failed)
        BrowserDownload.State.CANCELLED -> stringResource(R.string.browser_download_canceled)
    }
}

/** Where a file for a page's upload field comes from. Dismissing it answers the page with nothing. */
@Composable
internal fun BrowserUploadSheet(request: UploadRequest, actions: BrowserActions) {
    BrowserSheetFrame(onDismiss = actions::cancelUpload) {
        Text(stringResource(R.string.browser_upload_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        request.sources.forEach { source ->
            val (icon, label) = when (source) {
                UploadSource.PHOTOS -> Icons.Filled.Image to R.string.browser_upload_photos
                UploadSource.CAMERA -> Icons.Filled.CameraAlt to R.string.browser_upload_camera
                UploadSource.FILES -> Icons.Filled.Folder to R.string.browser_upload_files
            }
            MenuRow(icon, stringResource(label)) { actions.pickUpload(source) }
        }
        OutlinedButton(onClick = actions::cancelUpload, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}

/** A long-pressed link's menu. */
@Composable
internal fun BrowserLinkSheet(url: String, actions: BrowserActions) {
    fun act(block: () -> Unit) {
        actions.closeLinkMenu()
        block()
    }
    BrowserSheetFrame(onDismiss = actions::closeLinkMenu) {
        Text(
            url,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        MenuRow(Icons.Filled.Search, stringResource(R.string.browser_link_open)) { act { actions.open(url) } }
        MenuRow(Icons.Filled.ContentCopy, stringResource(R.string.browser_copy_link)) { act { actions.copy(url) } }
        MenuRow(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.browser_open_external)) { act { actions.openExternal(url) } }
        MenuRow(Icons.Filled.Share, stringResource(R.string.browser_share_link)) { act { actions.share(url) } }
    }
}

/**
 * A link off the allowlist: open it in the phone's browser, copy it, or stay. Its buttons in two
 * rows: Cancel alone on the right, then Copy link on the left and the phone's browser on the right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserOutsideDialog(url: String, actions: BrowserActions) {
    BasicAlertDialog(onDismissRequest = actions::stayHere) {
        Surface(
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column(Modifier.padding(24.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    tint = AlertDialogDefaults.iconContentColor,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.browser_outside_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = AlertDialogDefaults.titleContentColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.browser_outside_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AlertDialogDefaults.textContentColor,
                )
                Spacer(Modifier.height(12.dp))
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(10.dp))
                }
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = actions::stayHere) { Text(stringResource(R.string.action_cancel)) }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { actions.copy(url); actions.stayHere() }) { Text(stringResource(R.string.browser_copy_link)) }
                    Button(onClick = { actions.leaveFor(url) }) { Text(stringResource(R.string.browser_open_external)) }
                }
            }
        }
    }
}

/** A page's alert, confirm or prompt, as the app's dialog, naming the site that shows it. */
@Composable
internal fun BrowserPageDialog(dialog: PageDialog, actions: BrowserActions) {
    var value by remember(dialog) { mutableStateOf(dialog.defaultValue.orEmpty()) }
    val cancellable = dialog.kind != PageDialog.Kind.ALERT
    AlertDialog(
        onDismissRequest = { actions.answerDialog(confirmed = false, value = null) },
        title = { Text(stringResource(R.string.browser_dialog_title).replaceIosArg(1, dialog.host.orEmpty())) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (dialog.message.isNotEmpty()) Text(dialog.message)
                if (dialog.kind == PageDialog.Kind.PROMPT) {
                    OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { actions.answerDialog(confirmed = true, value = value.takeIf { dialog.kind == PageDialog.Kind.PROMPT }) }) {
                Text(stringResource(R.string.browser_dialog_ok))
            }
        },
        dismissButton = if (cancellable) {
            { TextButton(onClick = { actions.answerDialog(confirmed = false, value = null) }) { Text(stringResource(R.string.action_cancel)) } }
        } else {
            null
        },
    )
}

/** Cards over the page for the downloads running now and the last one done. */
@Composable
internal fun DownloadCards(downloads: List<BrowserDownload>, actions: BrowserActions, modifier: Modifier = Modifier) {
    var dismissed by remember { mutableStateOf(setOf<Long>()) }
    val shown = downloads.filter { it.id !in dismissed }.let { list ->
        list.filter { it.state == BrowserDownload.State.RUNNING } +
            list.filter { it.state != BrowserDownload.State.RUNNING }.take(1)
    }.take(3)
    if (shown.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEach { download ->
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (download.state == BrowserDownload.State.RUNNING) Icons.Filled.Download else Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(download.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(downloadStatus(download), style = MaterialTheme.typography.bodySmall)
                    }
                    when (download.state) {
                        BrowserDownload.State.RUNNING -> IconButton(onClick = { actions.cancelDownload(download.id) }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.browser_download_cancel))
                        }
                        BrowserDownload.State.DONE -> {
                            TextButton(onClick = { actions.openDownload(download) }) {
                                Text(stringResource(R.string.browser_download_open), color = MaterialTheme.colorScheme.inversePrimary)
                            }
                            IconButton(onClick = { dismissed = dismissed + download.id }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                            }
                        }
                        else -> IconButton(onClick = { dismissed = dismissed + download.id }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                        }
                    }
                }
            }
        }
    }
}
