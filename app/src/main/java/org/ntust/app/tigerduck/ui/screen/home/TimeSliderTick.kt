package org.ntust.app.tigerduck.ui.screen.home

/** How long from [nowMillis] to the next whole second, so the slider's ticks land on the second. */
internal fun millisToNextSecond(nowMillis: Long): Long = 1_000L - Math.floorMod(nowMillis, 1_000L)
