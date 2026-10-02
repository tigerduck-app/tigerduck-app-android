package org.ntust.app.tigerduck.ui.screen.informationsystem

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.component.EmptyStateView
import org.ntust.app.tigerduck.ui.component.NoTopBarInsets
import org.ntust.app.tigerduck.ui.component.SecureScreen
import org.ntust.app.tigerduck.ui.screen.settings.SubSettingsBarHeight
import org.ntust.app.tigerduck.ui.util.openExternalLink

/**
 * The in-app browser a tapped [org.ntust.app.tigerduck.network.model.PortalLink] opens into.
 * [SecureScreen] is on unconditionally: these are NTUST's own student-record / grades /
 * financial-aid pages, the same "personal data on screen" rationale the library QR screen
 * applies `FLAG_SECURE` for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortalBrowserScreen(
    title: String,
    url: String,
    browserPreference: String,
    onBack: () -> Unit,
    viewModel: PortalBrowserViewModel = hiltViewModel(),
) {
    SecureScreen(secure = true)
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme

    var failed by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var showMenu by remember { mutableStateOf(false) }
    val webViewState = rememberPortalWebViewState()

    // The page actually on screen, not the tapped link — an SSO redirect leaves this pointed
    // at ssoam2 for as long as the login bounce is in progress, and the address bar should
    // say so rather than keep showing where the user tapped from.
    val displayUrl = webViewState.currentUrl ?: url
    val displayTitle = webViewState.pageTitle?.takeIf { it.isNotBlank() } ?: title

    // A page-internal back (webViewState.goBack()) takes priority over leaving the screen,
    // both for the system back gesture and for the toolbar's own back icon.
    BackHandler(enabled = webViewState.canGoBack) { webViewState.goBack() }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    windowInsets = NoTopBarInsets,
                    title = { PortalAddressBar(url = displayUrl, title = displayTitle) },
                    navigationIcon = {
                        IconButton(onClick = { if (!webViewState.goBack()) onBack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = { reloadKey++ }) {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_reload))
                        }
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.feature_more))
                        }
                    },
                    expandedHeight = SubSettingsBarHeight,
                )
                // A thin line rather than a bar spanning the content — it reports load progress
                // without reading as a second, heavier toolbar stacked under the first.
                if (!failed && webViewState.isLoading) {
                    LinearProgressIndicator(
                        progress = { webViewState.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        drawStopIndicator = {},
                    )
                }
            }
        },
        containerColor = cs.background,
    ) { padding ->
        if (failed) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                EmptyStateView(
                    icon = Icons.Filled.Public,
                    title = stringResource(R.string.information_system_load_failed),
                    message = null,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { failed = false; reloadKey++ }) {
                        Text(stringResource(R.string.action_retry))
                    }
                    TextButton(onClick = { openExternalLink(context, url, browserPreference) }) {
                        Text(stringResource(R.string.action_open_in_browser))
                    }
                }
            }
        } else {
            // Keyed on reloadKey so the reload/retry actions tear down and recreate the
            // WebView rather than trying to coax a failed one back to life in place.
            key(reloadKey) {
                PortalWebView(
                    url = url,
                    state = webViewState,
                    backgroundColor = cs.background.toArgb(),
                    onError = { failed = true },
                    modifier = Modifier.fillMaxSize().padding(padding),
                    studentId = viewModel.studentId,
                    password = viewModel.password,
                )
            }
        }
    }

    if (showMenu) {
        PortalBrowserMenuSheet(
            url = displayUrl,
            title = displayTitle,
            onDismiss = { showMenu = false },
            onCopyLink = { copyLinkToClipboard(context, displayUrl) },
            onShare = { shareLink(context, displayUrl, displayTitle) },
            onOpenExternal = { openExternalLink(context, displayUrl, browserPreference) },
        )
    }
}

/**
 * Two-line browser chrome: the host — what a user actually needs to know to answer "where am
 * I" — on top with a lock/warning glyph, the page's own title underneath as secondary detail.
 * Mirrors TAT's `BrowserAddressBar`, which TigerDuck's previous single-line page title was
 * visibly plainer than.
 */
@Composable
private fun PortalAddressBar(url: String, title: String) {
    val scheme = MaterialTheme.colorScheme
    val parsed = remember(url) { runCatching { Uri.parse(url) }.getOrNull() }
    val secure = parsed?.scheme?.equals("https", ignoreCase = true) != false
    val host = parsed?.host?.takeIf { it.isNotEmpty() } ?: url

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (secure) Icons.Filled.Lock else Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = if (secure) scheme.onSurfaceVariant else scheme.error,
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = host,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = scheme.onSurface,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        Text(
            text = title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
    }
}

/** The "⋮" sheet: header repeats host + title (every row below acts on this exact page), then
 *  the actions TAT's own browser exposes there — copy, share, open externally. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortalBrowserMenuSheet(
    url: String,
    title: String,
    onDismiss: () -> Unit,
    onCopyLink: () -> Unit,
    onShare: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scheme = MaterialTheme.colorScheme
    val parsed = remember(url) { runCatching { Uri.parse(url) }.getOrNull() }
    val host = parsed?.host?.takeIf { it.isNotEmpty() } ?: url

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = scheme.surface,
    ) {
        Column(modifier = Modifier.padding(bottom = 8.dp)) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                Text(
                    text = host,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurface,
                )
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            BrowserMenuRow(
                icon = Icons.Filled.ContentCopy,
                label = stringResource(R.string.action_copy_link),
                onClick = { onDismiss(); onCopyLink() },
            )
            BrowserMenuRow(
                icon = Icons.Filled.Share,
                label = stringResource(R.string.action_share),
                onClick = { onDismiss(); onShare() },
            )
            BrowserMenuRow(
                icon = Icons.AutoMirrored.Filled.OpenInNew,
                label = stringResource(R.string.action_open_in_browser),
                onClick = { onDismiss(); onOpenExternal() },
            )
        }
    }
}

@Composable
private fun BrowserMenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

private fun copyLinkToClipboard(context: Context, url: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("url", url))
    // Android 13+ shows its own system copy confirmation; below that nothing does, so this
    // screen needs to say so itself — same gap SchoolMailMessageScreen's copy actions cover.
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.information_system_link_copied, Toast.LENGTH_SHORT).show()
    }
}

private fun shareLink(context: Context, url: String, title: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
        putExtra(Intent.EXTRA_SUBJECT, title)
    }
    context.startActivity(Intent.createChooser(intent, null))
}
