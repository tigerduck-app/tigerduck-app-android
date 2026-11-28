package org.ntust.app.tigerduck.ui.screen.whatsnew

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp

/**
 * The dots stand in for the page position with TalkBack: one node reading
 * [description] ("Page 2 of 4: <title>"). It stays put while pages slide
 * under it, so as a polite live region its description changing is what
 * announces each new page — focus otherwise stays on the Next button, which
 * sits in the same place on every page.
 *
 * Only the [PageDotWindow] around [current] is drawn, so a long run stays
 * inside the sheet.
 */
@Composable
internal fun PageDots(count: Int, current: Int, description: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = description
            liveRegion = LiveRegionMode.Polite
        },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in PageDotWindow.visibleSteps(count, current)) {
            key(i) {
                Box(
                    Modifier
                        .size(if (i == current) 8.dp else 6.dp)
                        .scale(PageDotWindow.scale(i, count, current))
                        .clip(CircleShape)
                        .background(
                            if (i == current) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                            },
                        ),
                )
            }
        }
    }
}

/**
 * Which dots [PageDots] draws: every step when there are at most
 * [VISIBLE_DOTS], otherwise a window of that many around the current step.
 * The two dots at an edge with more steps past it shrink, as on the iPhone,
 * so the row reads as going on.
 */
internal object PageDotWindow {
    const val VISIBLE_DOTS = 9

    fun visibleSteps(count: Int, current: Int): IntRange {
        if (count <= VISIBLE_DOTS) return 0 until count
        val start = (current - VISIBLE_DOTS / 2).coerceIn(0, count - VISIBLE_DOTS)
        return start until start + VISIBLE_DOTS
    }

    /** 1 for a full-size dot; less for the two at an edge of the window with hidden steps past it. */
    fun scale(step: Int, count: Int, current: Int): Float {
        val window = visibleSteps(count, current)
        val fromStart = if (window.first > 0) step - window.first else Int.MAX_VALUE
        val fromEnd = if (window.last < count - 1) window.last - step else Int.MAX_VALUE
        return when (minOf(fromStart, fromEnd)) {
            0 -> 0.5f
            1 -> 0.75f
            else -> 1f
        }
    }
}
