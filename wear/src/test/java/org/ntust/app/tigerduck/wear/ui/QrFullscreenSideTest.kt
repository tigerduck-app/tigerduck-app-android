package org.ntust.app.tigerduck.wear.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The fullscreen library QR's side, and the QR padding screen's preview of it. */
class QrFullscreenSideTest {

    private val inscribed = 227 / 1.41421356f

    @Test
    fun `on a round watch the padding pulls the QR in from the largest square that fits`() {
        assertEquals(inscribed, qrFullscreenSideDp(227, qrPaddingDp = 0, isScreenRound = true), 0.01f)
        assertEquals(inscribed - 24f, qrFullscreenSideDp(227, qrPaddingDp = 12, isScreenRound = true), 0.01f)
        assertEquals(inscribed - 48f, qrFullscreenSideDp(227, qrPaddingDp = 24, isScreenRound = true), 0.01f)
    }

    @Test
    fun `on a square watch the padding pulls the QR in from the screen's edge`() {
        assertEquals(200f, qrFullscreenSideDp(200, qrPaddingDp = 0, isScreenRound = false), 0.01f)
        assertEquals(176f, qrFullscreenSideDp(200, qrPaddingDp = 12, isScreenRound = false), 0.01f)
    }

    @Test
    fun `the QR never shrinks under what a scanner reads`() {
        assertEquals(96f, qrFullscreenSideDp(227, qrPaddingDp = 40, isScreenRound = true), 0.01f)
    }
}
