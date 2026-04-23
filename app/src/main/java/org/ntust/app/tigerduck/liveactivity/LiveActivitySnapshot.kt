package org.ntust.app.tigerduck.liveactivity

import java.util.Date

enum class LiveActivityScenario { IN_CLASS, CLASS_PREPARING, ASSIGNMENT_URGENT }

/**
 * Minimum payload the Live Update notification needs to render. Kept narrow on
 * purpose so the resolver stays pure and easy to test.
 */
data class LiveActivitySnapshot(
    val scenario: LiveActivityScenario,
    val title: String,
    val subtitle: String,
    val locationText: String?,
    val instructor: String?,
    /** Target date used as the "chronometer" countdown end. Null means no countdown. */
    val countdownTarget: Date?,
    /** 0.0 ... 1.0 for progress bars (null when N/A). */
    val progress: Double?,
    val accentHex: Int,
    val sourceId: String,
)
