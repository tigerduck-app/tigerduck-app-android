package org.ntust.app.tigerduck.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle

/**
 * The header row a page's own content holds, which [PageScaffold] draws for
 * [PageHeaderPosition.InContent]. Public for a page's `header` slot to build on.
 */
@Composable
fun PageHeader(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    titleStyle: TextStyle = PageDefaults.titleStyle,
    oneLineTitle: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(PageDefaults.HeaderPadding)
            .defaultMinSize(minHeight = PageDefaults.HeaderMinHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        navigationIcon()
        PageTitle(title, titleStyle, oneLineTitle, Modifier.weight(1f))
        actions()
    }
}
