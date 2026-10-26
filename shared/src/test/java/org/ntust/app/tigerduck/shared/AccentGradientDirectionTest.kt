package org.ntust.app.tigerduck.shared

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The codes the phone stores and sends the watch for each direction. Both
 * read a code back with [AccentGradientDirection.fromCode], so a code that
 * moved would turn a stored pick, or a gradient sent by a phone of another
 * build, into another direction.
 */
class AccentGradientDirectionTest {

    @Test
    fun `each direction keeps the code it shipped with`() {
        assertEquals(
            mapOf(
                AccentGradientDirection.TopToBottom to 0,
                AccentGradientDirection.BottomToTop to 1,
                AccentGradientDirection.LeftToRight to 2,
                AccentGradientDirection.RightToLeft to 3,
                AccentGradientDirection.TopRightToBottomLeft to 4,
                AccentGradientDirection.TopLeftToBottomRight to 5,
                AccentGradientDirection.BottomLeftToTopRight to 6,
                AccentGradientDirection.BottomRightToTopLeft to 7,
            ),
            AccentGradientDirection.entries.associateWith { it.code },
        )
    }
}
