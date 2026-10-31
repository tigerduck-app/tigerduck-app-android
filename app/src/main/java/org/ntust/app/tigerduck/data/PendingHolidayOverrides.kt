package org.ntust.app.tigerduck.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.util.logSummary
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** Sends one holiday's choice as sign-in [signIn], and says whether it went; see [PushApiClient.putHolidayOverride]. */
internal fun interface HolidayOverrideSender {
    suspend fun send(holidayId: Int, notify: Boolean, signIn: Int, madeAtMs: Long?): Boolean
}

/**
 * Holiday choices ([AppPreferences.holidayNotifyOverrides]) whose PUT the
 * backend has not acknowledged, by holiday id
 * ([AppPreferences.holidayOverridesAwaitingUpload]).
 *
 * A PUT went once, and a failure, as offline, was only logged: the choice
 * stayed on this phone alone, and the next full sync, which took the server's
 * set over this phone's, took it back. Now a choice is marked as waiting
 * before its PUT and let go only once the backend holds the value the user
 * still wants. Every full sync sends what waits first, and its snapshot keeps
 * the waiting choices as this phone has them ([merged]).
 *
 * PUTs go one at a time, in the order asked, and each reads the choice as it
 * is sent, so a retry sends today's value and the server keeps the user's
 * last. Each is pinned to the sign-in it was asked under: the sign-out clears
 * both sets ([AppPreferences.forgetHolidayOverrides]), a PUT still waiting for
 * its turn then sends nothing, and one that lands is let go under the lock the
 * sign-out moves the generation under, so it never writes the departing
 * account's set back after the clear. With TigerSync off a choice is this
 * phone's own and goes nowhere. As the iPhone's `enqueueHolidayUpload` and
 * `unacknowledgedHolidayIDs`.
 */
@Singleton
class PendingHolidayOverrides internal constructor(
    private val prefs: AppPreferences,
    private val sender: HolidayOverrideSender,
    private val syncs: () -> Boolean,
    private val signInGeneration: () -> Int,
    private val runIfSignedIn: (Int, () -> Unit) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Inject constructor(
        prefs: AppPreferences,
        pushApiClient: PushApiClient,
        authTokenManager: AuthTokenManager,
    ) : this(
        prefs = prefs,
        sender = HolidayOverrideSender { id, notify, signIn, madeAtMs ->
            pushApiClient.putHolidayOverride(id, notify, signIn, madeAtMs)
        },
        syncs = { prefs.cloudSyncEnabled },
        signInGeneration = { authTokenManager.signInGeneration },
        runIfSignedIn = authTokenManager::runIfSignedIn,
    )

    // The waiting set's read-modify-writes.
    private val lock = Any()

    // One PUT at a time: the backend keeps whichever request lands last.
    private val sendMutex = Mutex()

    // PUTs asked and not finished: a snapshot fetched meanwhile may predate one.
    private val inFlight = AtomicInteger(0)

    // The last choice made or PUT landed: a snapshot fetched before either may not hold it.
    @Volatile
    private var lastChangeAtMs = 0L

    /** The holidays whose last choice has not reached the server. */
    val waitingIds: Set<Int> get() = prefs.holidayOverridesAwaitingUpload

    /**
     * The user just wrote [holidayId]'s choice into the local set: kept as
     * waiting and sent. Marked before this suspends, so a sync running
     * meanwhile keeps the choice.
     */
    suspend fun send(holidayId: Int) {
        lastChangeAtMs = clock()
        // Before the sync check: a choice still waiting from before is this one now, and goes up
        // with this time whenever sync is on again.
        synchronized(lock) { prefs.holidayOverrideEditTimes = prefs.holidayOverrideEditTimes + (holidayId to lastChangeAtMs) }
        if (!syncs()) return
        val signIn = signInGeneration()
        synchronized(lock) { prefs.holidayOverridesAwaitingUpload = waitingIds + holidayId }
        trySend(holidayId, signIn)
    }

    /** Sends every choice still waiting; each full sync starts with this. */
    suspend fun retry() {
        if (!syncs()) return
        val signIn = signInGeneration()
        for (id in waitingIds) trySend(id, signIn)
    }

    /**
     * The set a full sync fetched at [fetchedAtMs] ([snapshot]), with the
     * choices still waiting kept as this phone has them ([local]); null when
     * the snapshot must not be applied at all: fetched before the last choice
     * or the last PUT that landed, or while a PUT is under way, it may not hold
     * that choice yet, and the next sync settles it.
     */
    fun merged(snapshot: Set<Int>, fetchedAtMs: Long, local: Set<Int>): Set<Int>? {
        if (inFlight.get() > 0 || fetchedAtMs <= lastChangeAtMs) return null
        val waiting = waitingIds
        return snapshot - waiting + (local intersect waiting)
    }

    private suspend fun trySend(holidayId: Int, signIn: Int) {
        inFlight.incrementAndGet()
        try {
            sendMutex.withLock {
                // Stale once the account changed, or once the choice landed while this waited.
                if (signIn != signInGeneration() || holidayId !in waitingIds) return
                val sent = edit(holidayId)
                val landed = try {
                    sender.send(holidayId, sent.first, signIn, sent.second)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "holiday override upload failed: ${e.logSummary()}")
                    return
                }
                // Not sent, as with TigerSync turned off before its turn: it still waits.
                if (!landed) return
                lastChangeAtMs = clock()
                runIfSignedIn(signIn) {
                    synchronized(lock) {
                        // Let go only if the server now holds what the user still wants: a
                        // choice made again during the request, even to the same setting, has
                        // its own PUT and its own time.
                        if (edit(holidayId) == sent) {
                            prefs.holidayOverridesAwaitingUpload = waitingIds - holidayId
                        }
                    }
                }
            }
        } finally {
            inFlight.decrementAndGet()
        }
    }

    // The choice as it stands, and when it was made (null for one made before the time was kept).
    // The time is read first: a tap writes the value and then the time, so a tap between these two
    // reads pairs its new value with the old time, which that tap's own PUT then corrects, and
    // never an old value with the new time, which nothing would.
    private fun edit(holidayId: Int): Pair<Boolean, Long?> {
        val madeAtMs = prefs.holidayOverrideEditTimes[holidayId]
        return (holidayId in prefs.holidayNotifyOverrides) to madeAtMs
    }

    /**
     * Forgets the edit times of [holidayIds], whose choice a pull just replaced: the value is the
     * server's now, and a time kept for it would stamp a PUT of someone else's choice.
     */
    fun forgetEditTimes(holidayIds: Set<Int>) {
        if (holidayIds.isEmpty()) return
        synchronized(lock) { prefs.holidayOverrideEditTimes = prefs.holidayOverrideEditTimes - holidayIds }
    }

    private companion object {
        const val TAG = "HolidayOverrides"
    }
}
