package org.ntust.app.tigerduck.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse

/**
 * A page's three-dot menu: the same button and popup in the same place on every page, last on the
 * header's row beside the sync dot. iOS's `HeaderMenuButton` is the same control.
 *
 * Having one of these is having items: a page with nothing to offer passes no menu to
 * [PageScaffold], so an empty menu can't be opened.
 *
 * @param label what TalkBack says for the button, naming what the menu holds.
 * @param iconColor the glyph's color; unspecified takes the header's.
 * @param onOpen runs as the menu opens, for what its items need fetched.
 * @param items the menu's `DropdownMenuItem`s. Composed only while it is open. Each calls `close`
 *   when picked, as the menu doesn't assume a pick dismisses it.
 */
class PageMenu(
    val label: String,
    val enabled: Boolean = true,
    val iconColor: Color = Color.Unspecified,
    val onOpen: () -> Unit = {},
    val items: @Composable ColumnScope.(close: () -> Unit) -> Unit,
)

/** The button [PageScaffold] draws for [menu], public for a page's `header` slot. */
@Composable
fun PageMenuButton(menu: PageMenu, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(
            onClick = {
                expanded = true
                menu.onOpen()
            },
            enabled = menu.enabled,
        ) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = menu.label,
                tint = menu.iconColor.takeOrElse { LocalContentColor.current },
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = PageDefaults.MenuShape,
        ) {
            menu.items(this) { expanded = false }
        }
    }
}
