package org.ntust.app.tigerduck.ui.component

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity

internal const val ListTopAnchorKey = "list-top-anchor"

/**
 * A first item one pixel tall, which keeps a list that rests at its top *at* its top when rows
 * arrive above the ones on screen.
 *
 * `LazyColumn` holds its place by the key of its first visible item: whatever is inserted before
 * that item goes in above the viewport, out of sight. That is right for someone part way down,
 * who should not have the rows they are reading pulled away. For a list at its very top it is
 * wrong, and it is exactly what a cache-first page does: the cached rows paint, the refresh lands
 * with newer ones ahead of them, and the list opens part way down -- as far down as the cache is
 * old. With this item first, a list at rest at its top has this item as its first visible item,
 * so the newer rows land below it, on screen. One pixel of scroll makes the first real row the
 * one held instead, and a reading position stays put as it always did.
 *
 * A pixel, not nothing: the list counts an item that ends exactly at the top of the viewport as
 * already scrolled past, so a zero-height anchor is never the first visible item unless content
 * padding happens to move that line.
 *
 * Emit it first, and outside any branch, so it is there however the list's content changes.
 */
fun LazyListScope.listTopAnchor() {
    item(key = ListTopAnchorKey) {
        Spacer(Modifier.height(with(LocalDensity.current) { 1.toDp() }))
    }
}
