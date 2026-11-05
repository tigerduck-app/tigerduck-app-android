package org.ntust.app.tigerduck.liveactivity

/**
 * When the boundary alarm fires with no Live Update to follow: just after the
 * class reminders next need a refresh ([remindersWakeMs], see
 * armClassReminders), padded and floored as [boundaryTriggerAt] does, or null
 * for none.
 */
internal fun reminderWakeAt(nowMs: Long, remindersWakeMs: Long?): Long? =
    remindersWakeMs?.takeIf { it > nowMs }?.let { (it + 1_000L).coerceAtLeast(nowMs + 30_000L) }

/**
 * When the boundary alarm fires: the earliest of [candidates] still ahead of
 * [nowMs], or the next of [termFlips] if that comes first.
 */
internal fun boundaryTriggerAt(nowMs: Long, candidates: List<Long>, termFlips: List<Long> = emptyList()): Long {
    // Pad by 1s to make sure we land *after* the boundary tick, not on it,
    // so the resolver sees the new state instead of the prior one.
    val futureCandidates = candidates.filter { it > nowMs }.map { it + 1_000L }
    // Floor at 30s so a near-instant boundary doesn't burn battery, and
    // ceil at 30 min so a long-idle stretch still gets a watchdog refresh.
    val nextBoundary = futureCandidates.minOrNull() ?: (nowMs + 30 * 60_000L)
    // A term flip only ever brings the alarm forward, as it does the
    // widgets' (WidgetBoundaryScheduler.chooseTriggerMillis): taken as a
    // candidate, one months away would stand in for the watchdog.
    val nextFlip = termFlips.filter { it > nowMs }.minOrNull()?.plus(1_000L) ?: nextBoundary
    return minOf(nextBoundary, nextFlip).coerceAtLeast(nowMs + 30_000L)
}

/**
 * When the minute alarm fires for a countdown to [targetMs]: just after its
 * minute next changes, padded like the boundaries above. No floor: a post
 * landing just before the change would otherwise leave the island a minute
 * behind for up to half a minute, and a running clock brings the change
 * round only once a minute.
 */
internal fun minuteChangeTriggerAt(targetMs: Long, nowMs: Long): Long =
    StaticCountdown.nextChangeAt(targetMs, nowMs) + 1_000L
