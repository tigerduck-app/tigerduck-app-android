// Where a shortcut card being dragged across its section's grid has got to.
// Pure arithmetic, kept out of the composable so the thresholds can be
// asserted on.

package org.ntust.app.tigerduck.ui.screen.home

import kotlin.math.roundToInt

internal object ShortcutGridGeometry {

    /**
     * The slot the card dragged from [from] by ([dx], [dy]) pixels now lies
     * over, in a [columns]-wide grid of [count] cards whose cells repeat every
     * [pitchX] pixels across and [pitchY] down (cell size plus gap).
     *
     * Rounded, so a card changes slot once it has crossed half a cell — the
     * same threshold section reorder uses — and clamped to the grid on both
     * axes, so a finger past the edge lands on the edge. A slot in the last
     * row that has no card (the empty cell of a half row) resolves to the
     * last card, so dragging there still does something.
     *
     * Hands [from] back unchanged when it is not a slot of this grid, so a
     * drag that outlives its card (removed mid-drag) cannot index out of it.
     */
    fun targetIndex(
        from: Int,
        count: Int,
        columns: Int,
        dx: Float,
        dy: Float,
        pitchX: Float,
        pitchY: Float,
    ): Int {
        if (count <= 0 || columns <= 0 || from !in 0 until count) return from
        val lastRow = (count - 1) / columns
        val col = (from % columns + (dx / pitchX).roundToInt()).coerceIn(0, columns - 1)
        val row = (from / columns + (dy / pitchY).roundToInt()).coerceIn(0, lastRow)
        return (row * columns + col).coerceAtMost(count - 1)
    }
}
