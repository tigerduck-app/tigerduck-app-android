package org.ntust.app.tigerduck.shared.clock

/**
 * Snapshot of a debug-time override. [savedAtRealMillis] is the real
 * (System.currentTimeMillis) instant at which this override was saved; it
 * anchors ticking-mode math.
 */
data class ClockOverride(
    val instantMillis: Long,
    val frozen: Boolean,
    val savedAtRealMillis: Long,
)
