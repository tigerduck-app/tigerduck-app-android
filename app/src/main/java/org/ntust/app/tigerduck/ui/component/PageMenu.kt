package org.ntust.app.tigerduck.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Shape

/**
 * A page's three-dot menu: the same button and popup in the same place on every page, last on the
 * header's row beside the sync dot. iOS's `HeaderMenuButton` is the same control.
 *
 * Having one of these is having items: a page with nothing to offer passes no menu to
 * [PageScaffold], so an empty menu can't be opened.
 *
 * @param label what TalkBack says for the button, naming what the menu holds.
 * @param shape the popup's corners.
 * @param onOpen runs as the menu opens, for what its items need fetched.
 * @param items the menu's `DropdownMenuItem`s. Composed only while it is open. Each calls `close`
 *   when picked, as the menu doesn't assume a pick dismisses it.
 */
class PageMenu(
    val label: String,
    val enabled: Boolean = true,
    val shape: Shape = PageDefaults.MenuShape,
    val onOpen: () -> Unit = {},
    val items: @Composable ColumnScope.(close: () -> Unit) -> Unit,
)

/** The button [PageScaffold] draws for [menu], public for a page's `header` slot. */
@Composable
fun PageMenuButton(menu: PageMenu) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = {
                expanded = true
                menu.onOpen()
            },
            enabled = menu.enabled,
            // Through the button's colors rather than a tint on the glyph, which would hold the
            // color when the menu is disabled instead of dimming it.
            colors = PageDefaults.menuButtonColors(),
        ) {
            Icon(Icons.Filled.MoreVert, contentDescription = menu.label)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = menu.shape,
        ) {
            menu.items(this) { expanded = false }
        }
    }
}
