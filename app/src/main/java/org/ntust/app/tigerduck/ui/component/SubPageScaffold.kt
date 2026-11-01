package org.ntust.app.tigerduck.ui.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable

/** The old name of [PageScaffold], until its pages have moved to the new one. */
internal val SubPageBarHeight = PageDefaults.BarHeight

/** [PageScaffold] under its old name, until its pages have moved to the new one. */
@Composable
fun SubPageScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    content: @Composable (PaddingValues) -> Unit,
) {
    PageScaffold(
        title = title,
        onBack = onBack,
        actions = actions,
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
        contentWindowInsets = contentWindowInsets,
    ) { padding -> content(padding) }
}
