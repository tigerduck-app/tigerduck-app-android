package org.ntust.app.tigerduck.ui.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R

/**
 * Height of a sub-screen's top bar, below M3's 64dp default so a settings
 * page leads with its content rather than its chrome.
 *
 * It was 48dp while every one of these bars was also paying the status-bar
 * inset a second time (see [NoTopBarInsets]), which padded the title out to
 * something taller than the number said. With the double inset gone, 48 read
 * as cramped.
 */
internal val SubPageBarHeight = 56.dp

/**
 * The shell of a page opened from a list: a back arrow, a title, optional
 * actions, and the page below on the theme's background.
 *
 * The bar zeroes its window insets ([NoTopBarInsets]) because whatever hosts
 * the page already pads it clear of the system bars. A new host has to do the
 * same, or the back button lands under the status bar.
 *
 * [content] receives the Scaffold's padding, which is the bar's height; apply
 * it to the page's first child. [snackbarHost] is the Scaffold's own slot,
 * empty by default, for a page that reports through a `SnackbarHostState`.
 * [contentWindowInsets] is the Scaffold's own
 * default unless a page lays out edge to edge; pass `WindowInsets(0, 0, 0, 0)`
 * for a page whose host has already padded the whole screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubPageScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = NoTopBarInsets,
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = actions,
                expandedHeight = SubPageBarHeight,
            )
        },
        snackbarHost = snackbarHost,
        contentWindowInsets = contentWindowInsets,
        content = content,
    )
}
