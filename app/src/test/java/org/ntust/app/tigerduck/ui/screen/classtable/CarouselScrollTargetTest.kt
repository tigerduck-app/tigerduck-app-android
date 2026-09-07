package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The class table's carousel scrolls itself to whichever class is happening
 * now. The offset is the part that cannot be eyeballed — it is off-screen by
 * definition, and being wrong by one card looks like the feature simply not
 * working.
 *
 * Geometry it encodes: a 16dp start inset inside the scrollable content, 160dp
 * ordinary cards, 220dp ongoing ones, 12dp between, and a 36dp sliver of the
 * previous card left visible.
 */
class CarouselScrollTargetTest {

    private fun ordinary(count: Int) = List(count) { false }

    @Test
    fun `nothing ongoing leaves the row at the start of the day`() {
        assertEquals(0.dp, carouselScrollTarget(ordinary(4), firstOngoingIndex = -1))
    }

    @Test
    fun `an ongoing first class needs no offset — nothing precedes it`() {
        assertEquals(0.dp, carouselScrollTarget(ordinary(4), firstOngoingIndex = 0))
    }

    @Test
    fun `the second class scrolls past exactly one card, less the peek`() {
        // 16 inset + 160 card + 12 gap - 36 peek
        assertEquals(152.dp, carouselScrollTarget(ordinary(4), firstOngoingIndex = 1))
    }

    @Test
    fun `the third class scrolls past two`() {
        // 16 + (160 + 12) * 2 - 36
        assertEquals(324.dp, carouselScrollTarget(ordinary(4), firstOngoingIndex = 2))
    }

    @Test
    fun `a wider ongoing card before it is counted at its own width`() {
        // Overlapping classes: index 0 is also ongoing, so it is the 220dp
        // card, not the 160dp one. 16 + 220 + 12 - 36.
        assertEquals(212.dp, carouselScrollTarget(listOf(true, true, false), firstOngoingIndex = 1))
    }

    @Test
    fun `the peek never scrolls past the start of the content`() {
        // A hypothetical card narrower than the peek must not produce a
        // negative offset — animateScrollTo would clamp it, but the intent is
        // "show the beginning", not "scroll backwards".
        assertEquals(0.dp, carouselScrollTarget(emptyList(), firstOngoingIndex = 0))
    }
}
