package org.ntust.app.tigerduck.data

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * When a page fetches its data without being asked to. Each page declares
 * its own at the top of its view model's file, so how often a screen talks
 * to the school servers is read and changed in one place.
 *
 * A pull to refresh always fetches, on every page: it is the user asking.
 *
 * The defaults are the cheap end, a fetch on launch and nothing else. A page
 * whose data goes stale in minutes, as Moodle assignments do, turns on more.
 */
data class RefreshPolicy(
    /** On the page's first load after the app starts. */
    val onLaunch: Boolean = true,
    /** When the app comes back to the foreground without having been killed. */
    val onForeground: Boolean = false,
    /** When the user comes back to the page from another one. */
    val onRevisit: Boolean = false,
    /**
     * How often the background worker refreshes the page's data, or null for
     * never. Each step towards [BACKGROUND_FASTEST] costs battery and load on
     * the school servers, so a page gets a short period only when its data
     * needs it; RefreshPolicyUsageTest holds the list of pages that may.
     */
    val background: Duration? = null,
    /**
     * A return to the foreground, or to the page, this soon after the page
     * last started a fetch does not fetch again: flipping between tabs must
     * not send a request per flip.
     */
    val minInterval: Duration = 1.minutes,
) {
    init {
        require(background == null || background >= BACKGROUND_FASTEST) {
            "WorkManager runs periodic work at most every $BACKGROUND_FASTEST, not $background"
        }
        require(!minInterval.isNegative()) { "minInterval must not be negative" }
    }

    companion object {
        /** The shortest period WorkManager runs periodic work at. */
        val BACKGROUND_FASTEST = 15.minutes

        /**
         * Whether [every] has passed since [lastMs] at [nowMs]. Never (0) has
         * always passed, and so has a time in the future: a clock set back
         * must not hold a refresh off until it catches up.
         */
        fun hasPassed(lastMs: Long, nowMs: Long, every: Duration): Boolean =
            lastMs <= 0L || nowMs < lastMs || nowMs - lastMs >= every.inWholeMilliseconds
    }
}

/**
 * One page's [RefreshPolicy], applied to the events that reach the page.
 *
 * Held by the page's view model and touched only from its main-thread
 * scope, like the rest of its state. [nowMs] is a monotonic clock.
 */
class RefreshTriggers(
    private val policy: RefreshPolicy,
    private val nowMs: () -> Long,
) {
    private var lastFetchMs = 0L
    private var shownBefore = false

    /** Whether the page's first load should fetch. */
    fun onLaunch(): Boolean = policy.onLaunch

    /** Whether the app coming back to the foreground should fetch. */
    fun onForeground(): Boolean = policy.onForeground && intervalPassed()

    /**
     * Whether the page being shown should fetch: on a return to it, which is
     * any showing after the page has been shown or has fetched. The pages
     * live as long as the app and load at launch, so the first time a page
     * other than the start page is opened, its launch fetch may be long
     * past. The start page's first showing is the launch itself, and quiet.
     */
    fun onShown(): Boolean {
        val returning = shownBefore || lastFetchMs != 0L
        shownBefore = true
        return returning && policy.onRevisit && intervalPassed()
    }

    /** A fetch started, whatever asked for it. */
    fun fetchStarted() {
        lastFetchMs = nowMs()
    }

    private fun intervalPassed(): Boolean =
        RefreshPolicy.hasPassed(lastFetchMs, nowMs(), policy.minInterval)
}
