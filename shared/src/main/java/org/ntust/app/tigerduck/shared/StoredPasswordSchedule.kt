package org.ntust.app.tigerduck.shared

/**
 * The waits between automatic posts of a stored password the school or the library refused: 10
 * seconds, 1 minute and 5 minutes after the first three refusals in a row, then 6 hours each time,
 * so a password changed elsewhere goes out four times a day. As on the iPhone
 * (`storedPasswordRetryDelay`). In `:shared`, as the library's sign-in runs on the watch too.
 */
object StoredPasswordSchedule {
    private val QUICK_RETRIES_MS = longArrayOf(10_000L, 60_000L, 5 * 60_000L)
    private const val SLOW_RETRY_MS = 6 * 60 * 60_000L

    /** How many of the quick waits there are, which a device the old latch held has spent. */
    val quickRetries: Int get() = QUICK_RETRIES_MS.size

    /** The wait after the [refusals]th refusal in a row. */
    fun delayMs(refusals: Int): Long =
        if (refusals > QUICK_RETRIES_MS.size) SLOW_RETRY_MS else QUICK_RETRIES_MS[maxOf(refusals, 1) - 1]

    /**
     * When the next post may go after [refusals] in a row, the last at [refusedAt], or null with
     * none. Measured from that refusal, so a clock set back before it lets the post go at [now]
     * rather than stretching the wait.
     */
    fun retryAt(refusals: Int, refusedAt: Long, now: Long): Long? = when {
        refusals <= 0 -> null
        refusedAt > now -> now
        else -> refusedAt + delayMs(refusals)
    }
}
