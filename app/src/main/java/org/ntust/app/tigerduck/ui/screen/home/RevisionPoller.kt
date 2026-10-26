// The foreground revision poll: asks the backend's revision counter whether
// anything changed, and when it did, reads the change feed to tell whether a
// full sync is needed (ChangeFeedPlan).
//
// It used to be a fixed 10-second loop for as long as the app was in the
// foreground, whatever the server answered — 360 requests an hour, each of
// which makes the backend commit `last_seen_at` — retrying a server error, a
// 429 or a 401 ten seconds later and never backing off
// (tigerduck-app/tigerduck-app-android#154). This follows the iPhone app's
// `RevisionPoller` (tigerduck-app/tigerduck-app#238): a return to the app
// polls at once, a minute of 10-second polls follows a return, a change or a
// local edit, then the wait doubles up to a minute; failures back off to five
// minutes with jitter; a 401 gets one relogin; one request at a time.
//
// The poll slows down rather than stops because the silent `sync_trigger`
// push covers only assignment marks, course overrides and the server's Moodle
// sync — not notification settings, holiday choices or skipped dates. The
// backend's poll tracker is not read by the push pipeline, so backing off
// loses nothing there (tigerduck-app/tigerduck-backend#32).

package org.ntust.app.tigerduck.ui.screen.home

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.push.PushApiException
import org.ntust.app.tigerduck.push.SyncChangesPage
import org.ntust.app.tigerduck.util.logSummary
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

enum class RevisionPollOutcome {
    /** The server is at the revision this device last synced. */
    UNCHANGED,

    /** The server had moved on, and a sync ran. */
    CHANGED,

    FAILED,

    /** The backend refused the session (HTTP 401). */
    UNAUTHORIZED,

    /** No network to ask on, so no request was made. */
    OFFLINE,
}

/** When the foreground revision poll asks next. All times are milliseconds. */
class RevisionPollSchedule(nowMs: Long) {
    private var activeUntil = nowMs + ACTIVE_WINDOW_MS
    private var idleInterval = ACTIVE_INTERVAL_MS
    private var failures = 0

    /** A return to the app, a change or a local edit: the active cadence for [ACTIVE_WINDOW_MS]. */
    fun noteActivity(nowMs: Long) {
        activeUntil = nowMs + ACTIVE_WINDOW_MS
        idleInterval = ACTIVE_INTERVAL_MS
    }

    /** The wait after an answer: the active cadence, or twice the last idle wait up to the cap. */
    fun delayAfterAnswer(nowMs: Long): Long {
        failures = 0
        if (nowMs < activeUntil) return ACTIVE_INTERVAL_MS
        idleInterval = min(IDLE_CAP_MS, idleInterval * 2)
        return idleInterval
    }

    /**
     * The wait after a failure: the current wait doubled per failure in a
     * row, up to the cap, with [jitter] in 0..1 spreading it over its upper
     * half so devices do not retry in step.
     */
    fun delayAfterFailure(nowMs: Long, jitter: Double): Long {
        failures++
        val doubled = currentInterval(nowMs).toDouble() * (1L shl min(failures, 30))
        val backoff = min(FAILURE_CAP_MS.toDouble(), doubled)
        return (backoff * (0.5 + 0.5 * jitter.coerceIn(0.0, 1.0))).toLong()
    }

    fun currentInterval(nowMs: Long): Long = if (nowMs < activeUntil) ACTIVE_INTERVAL_MS else idleInterval

    companion object {
        const val ACTIVE_INTERVAL_MS = 10_000L
        const val ACTIVE_WINDOW_MS = 60_000L
        const val IDLE_CAP_MS = 60_000L
        const val FAILURE_CAP_MS = 300_000L
    }
}

/**
 * Runs [poll] on [RevisionPollSchedule], one request at a time, in [scope].
 * A 401 gets one [relogin]; another 401, or a failed relogin, stops polling
 * until the next [start].
 *
 * Confined to [scope]'s thread, the main thread in the app: every method and
 * every coroutine it launches runs there.
 */
class RevisionPoller(
    private val scope: CoroutineScope,
    private val poll: suspend () -> RevisionPollOutcome,
    private val relogin: suspend () -> Boolean,
    private val now: () -> Long,
    private val jitter: () -> Double = { Random.nextDouble() },
) {
    private val schedule = RevisionPollSchedule(now())
    private var running = false
    private var reloginTried = false
    private var wait: Job? = null
    private var nextPollAt: Long? = null
    private var lastPollAt: Long? = null

    /** From a poll's start until its next wait is armed, relogin included. */
    var isPolling = false
        private set

    /** Set by a 401 the relogin did not cure; cleared by [start]. */
    var isSuspended = false
        private set

    /**
     * Starts polling, or restarts it as on a return to the app. [pollNow]
     * still waits out the active cadence since the last poll, so quick
     * pauses and resumes add no requests; launch passes false, since its own
     * sync has just pulled. A poll in flight is not doubled: it arms the
     * next wait when it lands.
     */
    fun start(pollNow: Boolean) {
        running = true
        isSuspended = false
        reloginTried = false
        schedule.noteActivity(now())
        if (isPolling) return
        val sinceLastPoll = lastPollAt?.let { max(0L, now() - it) } ?: Long.MAX_VALUE
        val delayMs = if (pollNow) {
            max(0L, RevisionPollSchedule.ACTIVE_INTERVAL_MS - sinceLastPoll)
        } else {
            RevisionPollSchedule.ACTIVE_INTERVAL_MS
        }
        arm(delayMs)
    }

    /** Stops arming polls. A request in flight finishes, so a sync it started is not cut off. */
    fun stop() {
        running = false
        cancelWait()
    }

    /** A local edit: back to the active cadence, sooner than a longer wait already armed. */
    fun noteActivity() {
        schedule.noteActivity(now())
        if (!running || isSuspended || isPolling) return
        val soonest = now() + RevisionPollSchedule.ACTIVE_INTERVAL_MS
        if (nextPollAt?.let { it <= soonest } == true) return
        arm(RevisionPollSchedule.ACTIVE_INTERVAL_MS)
    }

    private fun pollOnce() {
        if (!running || isSuspended || isPolling) return
        isPolling = true
        lastPollAt = now()
        // Its own job, so a stop or a restart, which cancel only the wait,
        // never cancel a request or the sync it runs.
        scope.launch {
            val outcome = try {
                poll()
            } catch (e: CancellationException) {
                isPolling = false
                throw e
            } catch (_: Exception) {
                RevisionPollOutcome.FAILED
            }
            finish(outcome)
        }
    }

    private suspend fun finish(outcome: RevisionPollOutcome) {
        val delayMs = try {
            when (outcome) {
                RevisionPollOutcome.CHANGED -> {
                    schedule.noteActivity(now())
                    schedule.delayAfterAnswer(now())
                }
                RevisionPollOutcome.UNCHANGED -> schedule.delayAfterAnswer(now())
                RevisionPollOutcome.OFFLINE -> schedule.currentInterval(now())
                RevisionPollOutcome.FAILED -> schedule.delayAfterFailure(now(), jitter())
                RevisionPollOutcome.UNAUTHORIZED -> {
                    if (reloginTried || !tryRelogin()) {
                        isSuspended = true
                        null
                    } else {
                        0L
                    }
                }
            }
        } finally {
            // Before arming: a wait of zero polls at once, which a poll still
            // marked in flight would turn away.
            isPolling = false
        }
        if (delayMs == null || !running || isSuspended) return
        arm(delayMs)
    }

    private suspend fun tryRelogin(): Boolean {
        reloginTried = true
        return try {
            relogin()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    private fun arm(delayMs: Long) {
        cancelWait()
        // Not through a wait: on an immediate dispatcher it would run, and
        // finish, before `wait` was assigned.
        if (delayMs <= 0L) {
            pollOnce()
            return
        }
        nextPollAt = now() + delayMs
        wait = scope.launch {
            delay(delayMs)
            nextPollAt = null
            wait = null
            pollOnce()
        }
    }

    private fun cancelWait() {
        wait?.cancel()
        wait = null
        nextPollAt = null
    }
}

/**
 * What a revision poll does with the change feed after its watermark, as the
 * iPhone's `ChangeFeedPlan` (tigerduck-app/tigerduck-app#241,
 * tigerduck-app-android#120). Assignment changes come from the server's own
 * copy of the Moodle list, which this phone fetches from Moodle itself, and
 * bulletins are not part of TigerSync, so changes to those alone only move the
 * watermark. Any other change, or a page that may not hold every change up to
 * the current revision, takes the full sync.
 */
internal sealed interface ChangeFeedPlan {
    data object FullSync : ChangeFeedPlan

    data class Advance(val to: Long) : ChangeFeedPlan

    companion object {
        val PASSED_OVER = setOf("assignment", "bulletin_subscription", "bulletin_state", "bulletin_match")

        /** [page] is null when the read failed or the server no longer keeps the changes after [since]. */
        fun of(page: SyncChangesPage?, since: Long): ChangeFeedPlan {
            if (page == null || page.hasMore ||
                page.returnedUntilRevision != page.currentRevision ||
                page.changes.lastOrNull()?.revision != page.currentRevision ||
                page.changes.any { it.revision <= since }
            ) {
                return FullSync
            }
            return if (page.changes.all { it.entityType in PASSED_OVER }) Advance(page.returnedUntilRevision) else FullSync
        }
    }
}

/**
 * One revision poll: the revision counter, and when the server is ahead, the
 * change feed after this phone's watermark, and a full sync unless the feed
 * holds only changes [ChangeFeedPlan] passes over. Those move the watermark,
 * once what waits to go up has been sent ([retryUnsent]), as a full sync would
 * have sent it. With sync off or no session it asks nothing, so the schedule
 * slows to its idle wait and turning sync on is picked up within it. Until a
 * full sync has set the watermark the feed cannot show what this phone lacks,
 * so the full sync runs instead. A full sync already under way takes the change
 * rather than fetch it a second time, as on a return, when the poll and Home's
 * own sync start together, and so does one, or a sign-out, that overtakes the
 * feed's read. One that did not move the watermark failed, and backs off like
 * any other failure instead of being retried every 10 seconds.
 */
internal suspend fun pollRevisionOnce(
    syncEnabled: () -> Boolean,
    online: () -> Boolean,
    signIn: suspend () -> Boolean,
    fetchRevision: suspend () -> Long,
    fetchChanges: suspend (sinceRevision: Long) -> SyncChangesPage?,
    lastKnownRevision: () -> Long,
    setLastKnownRevision: (Long) -> Unit,
    signInGeneration: () -> Int,
    fullSyncsInFlight: () -> Int,
    retryUnsent: suspend () -> Unit,
    fullSync: suspend () -> Unit,
): RevisionPollOutcome {
    if (!syncEnabled()) return RevisionPollOutcome.UNCHANGED
    if (!online()) return RevisionPollOutcome.OFFLINE
    if (!signIn()) return RevisionPollOutcome.UNCHANGED
    return try {
        val revision = fetchRevision()
        val since = lastKnownRevision()
        if (revision <= since) return RevisionPollOutcome.UNCHANGED
        if (fullSyncsInFlight() > 0) return RevisionPollOutcome.CHANGED
        if (since > 0) {
            val startedIn = signInGeneration()
            // Read on the poll's own thread, with nothing suspending between the
            // check and the move, as a full sync sets the watermark.
            fun overtaken() = lastKnownRevision() != since || signInGeneration() != startedIn || fullSyncsInFlight() > 0
            val page = try {
                fetchChanges(since)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "[feed] read failed: ${e.logSummary()}")
                null
            }
            if (overtaken()) return RevisionPollOutcome.CHANGED
            val plan = ChangeFeedPlan.of(page, since)
            Log.i(TAG, "[feed] since=$since plan=$plan")
            if (plan is ChangeFeedPlan.Advance) {
                retryUnsent()
                if (!overtaken()) setLastKnownRevision(plan.to)
                return RevisionPollOutcome.CHANGED
            }
        }
        fullSync()
        if (lastKnownRevision() >= revision) RevisionPollOutcome.CHANGED else RevisionPollOutcome.FAILED
    } catch (e: CancellationException) {
        throw e
    } catch (e: PushApiException) {
        Log.w(TAG, "[poll] failed: ${e.logSummary()}")
        if (e.statusCode == 401) RevisionPollOutcome.UNAUTHORIZED else RevisionPollOutcome.FAILED
    } catch (e: Exception) {
        Log.w(TAG, "[poll] failed: ${e.logSummary()}")
        RevisionPollOutcome.FAILED
    }
}

private const val TAG = "RevisionPoll"
