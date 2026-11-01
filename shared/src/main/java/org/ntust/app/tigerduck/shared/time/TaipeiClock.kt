package org.ntust.app.tigerduck.shared.time

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.withTimeoutOrNull
import org.ntust.app.tigerduck.shared.clock.AppClock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * The one home of Taipei time. The school's day, its class periods and every
 * "today" in the app run on Taipei's clock, whatever zone the phone is set to
 * (a student abroad still sees Taipei's day). No other file writes the zone's
 * name; they read it from here. "Now" is [AppClock], so the debug override
 * moves every screen together.
 */
object TaipeiClock {

    val zone: ZoneId = ZoneId.of("Asia/Taipei")

    val timeZone: TimeZone = TimeZone.getTimeZone("Asia/Taipei")

    fun now(): ZonedDateTime = AppClock.instant().atZone(zone)

    fun today(): LocalDate = now().toLocalDate()

    fun minuteOfDay(at: Instant = AppClock.instant()): Int {
        val t = at.atZone(zone)
        return t.hour * 60 + t.minute
    }

    /**
     * Emits at once, then at each minute boundary of [AppClock] time. When the
     * override changes (the debug clock is set, moved or cleared) it emits at
     * once and re-aligns to the new time's next boundary.
     */
    fun minuteTicks(): Flow<ZonedDateTime> = minuteTicks(AppClock::nowMillis, AppClock::isFrozen)

    /**
     * [minuteTicks] that starts over each time [restarts] emits: the new run emits at once and
     * waits for the next boundary afresh. A coroutine wait does not count the time a phone spends
     * in deep sleep, so a screen sends a restart when it comes back to the front, and its state is
     * right at once instead of up to a minute late. [restarts] should emit when collected, as a
     * `StateFlow` does, which starts the first run.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun minuteTicksRestartedBy(
        restarts: Flow<*>,
        nowMillis: () -> Long = AppClock::nowMillis,
        isFrozen: () -> Boolean = AppClock::isFrozen,
    ): Flow<ZonedDateTime> = restarts.flatMapLatest { minuteTicks(nowMillis, isFrozen) }

    /** The clock and its frozen state are passed in, so a test can tie them to its own scheduler. */
    fun minuteTicks(nowMillis: () -> Long, isFrozen: () -> Boolean): Flow<ZonedDateTime> = channelFlow {
        val changed = Channel<Unit>(Channel.CONFLATED)
        val listener: (Long) -> Unit = { changed.trySend(Unit) }
        // Registered before the first emit, so a change right after it is not lost.
        AppClock.addOverrideListener(listener)
        try {
            while (true) {
                // One reading per tick: the emitted time and the deadline come from it, so a
                // boundary crossed meanwhile is caught by the next pass instead of skipped.
                val at = nowMillis()
                send(Instant.ofEpochMilli(at).atZone(zone))
                val deadline = (at.floorDiv(MINUTE_MS) + 1) * MINUTE_MS
                if (isFrozen()) {
                    // A frozen clock never reaches the deadline; only a new override moves it.
                    changed.receive()
                } else {
                    val remaining = deadline - nowMillis()
                    if (remaining > 0) withTimeoutOrNull(remaining) { changed.receive() }
                }
            }
        } finally {
            AppClock.removeOverrideListener(listener)
        }
    }

    private const val MINUTE_MS = 60_000L
}
