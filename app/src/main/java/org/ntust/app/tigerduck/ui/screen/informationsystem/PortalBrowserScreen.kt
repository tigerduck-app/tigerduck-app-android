package org.ntust.app.tigerduck.ui.screen.informationsystem

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
    val webViewState = rememberPortalWebViewState()

    // A page-internal back (webViewState.goBack()) takes priority over leaving the screen,
    // both for the system back gesture and for the toolbar's own back icon.
    BackHandler(enabled = webViewState.canGoBack) { webViewState.goBack() }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    windowInsets = NoTopBarInsets,
                    title = {
                        Text(
                            text = webViewState.pageTitle?.takeIf { it.isNotBlank() } ?: title,
                            maxLines = 1,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { if (!webViewState.goBack()) onBack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = { reloadKey++ }) {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_reload))
                        }
                        IconButton(onClick = { openExternalLink(context, url, browserPreference) }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(R.string.action_open_in_browser))
                        }
                    },
                    expandedHeight = SubSettingsBarHeight,
                )
                if (!failed && webViewState.isLoading) {
                    LinearProgressIndicator(
                        progress = { webViewState.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
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
}
