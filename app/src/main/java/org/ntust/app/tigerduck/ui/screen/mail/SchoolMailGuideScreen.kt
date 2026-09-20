package org.ntust.app.tigerduck.ui.screen.mail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.component.EmptyStateView
import org.ntust.app.tigerduck.ui.component.NoTopBarInsets
import org.ntust.app.tigerduck.ui.screen.settings.SubSettingsBarHeight
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme

/** The Android "use another app" guide: a WebView onto the matching page on the TigerDuck website. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchoolMailGuideScreen(browserPreference: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    // Not isSystemInDarkTheme(): that's the OS setting alone, while cs.background/cs.onBackground
    // below follow the app's own resolved theme (MainActivity's themeMode override -- "dark" or
    // "light" -- can disagree with the OS). TigerDuckTheme.isDarkMode is the same
    // Compose-observable value MainActivity mirrors that resolved theme into, so the embedded
    // page's theme stays in lockstep with the colours passed alongside it instead of just the OS
    // half of the time.
    val isDark = TigerDuckTheme.isDarkMode
    val languageTag = context.resources.configuration.locales[0].toLanguageTag()

    var failed by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }

    val embedUrl = guideUrl(
        platform = "android",
        isDark = isDark,
        languageTag = languageTag,
        // background/onBackground, not surface/onSurface: in this theme those are deliberately
        // different (background is the page -- white or black; surface is cards -- #F2F2F7 or
        // #3A3A3C), the Scaffold below fills with background, and every other sub-settings screen
        // renders its body on background too. Passing surface here would paint the page a slab of
        // mid-grey and leave a seam exactly where these parameters exist to remove one.
        background = cs.background.toCssHex(),
        foreground = cs.onBackground.toCssHex(),
    )
    // No query: the browser fallback should get the full site page, nav and all, not the
    // stripped embed that only makes sense inside this screen.
    val publicGuideUrl = GUIDE_URL_PREFIX + "android"

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = NoTopBarInsets,
                title = { Text(stringResource(R.string.school_mail_use_other_app)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                expandedHeight = SubSettingsBarHeight,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (failed) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                EmptyStateView(
                    icon = Icons.AutoMirrored.Filled.HelpOutline,
                    title = stringResource(R.string.school_mail_guide_load_failed),
                    message = "",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { failed = false; reloadKey++ }) {
                        Text(stringResource(R.string.action_retry))
                    }
                    TextButton(onClick = { openMailLink(context, publicGuideUrl, browserPreference) }) {
                        Text(stringResource(R.string.school_mail_guide_open_in_browser))
                    }
                }
            }
        } else {
            // Keyed on reloadKey so Retry tears down and recreates the WebView rather than
            // trying to coax a WebView that already failed to load back to life in place.
            key(reloadKey) {
                GuideWebView(
                    url = embedUrl,
                    backgroundColor = cs.background.toArgb(),
                    onExternalLink = { link -> openMailLink(context, link, browserPreference) },
                    onError = { failed = true },
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            }
        }
    }
}
