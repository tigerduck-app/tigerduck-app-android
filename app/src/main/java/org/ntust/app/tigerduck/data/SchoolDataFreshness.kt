package org.ntust.app.tigerduck.data

/**
 * How old the school data on screen may get before something automatic
 * fetches it again.
 *
 * The age is [org.ntust.app.tigerduck.data.preferences.AppPreferences.schoolDataSyncedAtMs]:
 * when Moodle last answered a whole round of assignments, from any screen or
 * the worker. Without it every trigger fetched: a launch a minute after the
 * last one ran the whole pipeline again, while a phone left in the
 * background all morning showed breakfast's data until the user pulled.
 *
 * Only automatic fetches ask. A pull, a sign-in, a switch of term or
 * language always fetch.
 */
object SchoolDataFreshness {

    /** Launch, and coming back to the app, fetch data older than this. */
    const val FOREGROUND_MAX_AGE_MS = 15 * 60_000L

    /**
     * The periodic worker leaves the school servers alone while the data is
     * younger than this: someone had the app open and it fetched.
     */
    const val BACKGROUND_MAX_AGE_MS = 30 * 60_000L

    /**
     * After an automatic fetch that did not land, how long until the next
     * one may try. Without it, a school server that is down was asked again
     * every time the app came back to the foreground.
     */
    const val AUTO_RETRY_AFTER_MS = 5 * 60_000L

    /**
     * Whether data stamped at [syncedAtMs] is older than [maxAgeMs] at
     * [nowMs]. Never fetched is stale, and so is a stamp from the future: a
     * clock set back must not freeze the data until it catches up.
     */
    fun isStale(syncedAtMs: Long, nowMs: Long, maxAgeMs: Long): Boolean =
        syncedAtMs <= 0L || nowMs < syncedAtMs || nowMs - syncedAtMs >= maxAgeMs

    /**
     * Whether launch or a return to the foreground should fetch: the data is
     * past [FOREGROUND_MAX_AGE_MS], and this screen has not tried on its own
     * within [AUTO_RETRY_AFTER_MS] ([lastAttemptMs], 0 for never).
     */
    fun shouldAutoRefresh(syncedAtMs: Long, lastAttemptMs: Long, nowMs: Long): Boolean =
        isStale(syncedAtMs, nowMs, FOREGROUND_MAX_AGE_MS) &&
            isStale(lastAttemptMs, nowMs, AUTO_RETRY_AFTER_MS)
}
