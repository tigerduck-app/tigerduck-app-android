package org.ntust.app.tigerduck.push

import android.util.Log
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.auth.NotSignedInException
import org.ntust.app.tigerduck.clubs.ClubFeeds
import org.ntust.app.tigerduck.clubs.ClubSubscriptions
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.account.SignInBound
import org.ntust.app.tigerduck.sync.SyncOutcome
import org.ntust.app.tigerduck.sync.SyncPolicies
import org.ntust.app.tigerduck.util.logSummary
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the club document's sync ([syncClubSubscriptions]) for a sign-in while TigerSync is on, as
 * iOS's `ClubSubscriptionsSyncer`: after each edit here ([requestSync]), after every full sync
 * ([afterFullSync]), and at a revision poll while a run is owed ([retryIfOwed]). One run goes at a
 * time; calls during a run get one more run after it, which reads the server's copy again.
 *
 * Every request carries the sign-in the run is for, and a write is sent only while that sign-in is
 * current and TigerSync is on, asked once its bearer is on it ([SettingsDocumentApiClient.write]).
 * The club subscriptions have no 同步內容 switch of their own, as on iOS: [SyncPolicies]' master
 * switch alone gates them, which is off on fdroid. Edits are kept all the same, and sent once it is on.
 */
@Singleton
class ClubSubscriptionsSyncer internal constructor(
    /** The document's requests, each carrying the sign-in given here. */
    private val documents: (SignIn) -> SettingsDocumentTransport,
    private val subscriptions: ClubSubscriptions,
    private val tigerSyncOn: () -> Boolean,
    private val scope: CoroutineScope,
    /** Runs when a run adopts the account's first subscribed club, made on another device. */
    private val firstSubscription: suspend (SignIn) -> Unit = {},
) {
    @Inject constructor(
        client: SettingsDocumentApiClient,
        subscriptions: ClubSubscriptions,
        policies: SyncPolicies,
        feeds: ClubFeeds,
        @ApplicationScope scope: CoroutineScope,
    ) : this(
        documents = { signIn ->
            object : SettingsDocumentTransport {
                override suspend fun read(): SettingsDocumentEnvelope<JsonObject>? =
                    client.read(CLUB_SUBSCRIPTIONS_NAMESPACE, signIn, JsonObject::class.java)

                override suspend fun write(
                    document: JsonObject,
                    baseRevision: Long?,
                    isCurrent: () -> Boolean,
                ): SettingsWriteResult<JsonObject> =
                    client.write(CLUB_SUBSCRIPTIONS_NAMESPACE, document, baseRevision, signIn, JsonObject::class.java, isCurrent)
            }
        },
        subscriptions = subscriptions,
        tigerSyncOn = { policies.current.tigerSyncOn },
        scope = scope,
        firstSubscription = feeds::refresh,
    )

    private val runLock = Mutex()

    /** The sign-in the next run is for, set by each call; null once a run took it. */
    @Volatile
    private var wanted: SignIn? = null

    @Volatile
    private var lastOutcome = SyncOutcome.SKIPPED

    /** Whether a run failed, reading or writing, since the last one that landed for this sign-in. */
    private val owed = SignInBound(false)

    /** The full sync's revision at which the last run that landed read the server's copy. */
    private val settledAt = SignInBound<Long?>(null)

    val owesRun: Boolean get() = owed.value

    /** Sends this device's changes and takes the server's, read again. */
    suspend fun sync(signIn: SignIn): SyncOutcome {
        if (!tigerSyncOn()) return SyncOutcome.SKIPPED
        wanted = signIn
        return runLock.withLock {
            // A run that started after this call asked has covered it.
            val next = wanted ?: return@withLock lastOutcome
            wanted = null
            run(next).also { lastOutcome = it }
        }
    }

    /** [sync] on the application scope, for an edit made here. */
    fun requestSync(signIn: SignIn) {
        scope.launch { sync(signIn) }
    }

    /**
     * After a full sync for [signIn] at [revision]: a run, unless nothing waits to go up and the
     * last run read the server's copy at this revision. Any device's write of the document moves
     * the account's revision, so until it moves there is nothing new to read.
     */
    fun afterFullSync(signIn: SignIn, revision: Long) {
        if (!subscriptions.hasUnsentChanges && !owed.value && settledAt.value == revision) return
        scope.launch { if (sync(signIn) == SyncOutcome.SYNCED) settledAt.set(revision, signIn) }
    }

    /** At a revision poll: a run while a change waits to go up or a run is owed, as a failed read leaves nothing unsent. */
    fun retryIfOwed(signIn: SignIn) {
        if (tigerSyncOn() && (subscriptions.hasUnsentChanges || owed.value)) requestSync(signIn)
    }

    private suspend fun run(signIn: SignIn): SyncOutcome {
        val mayWrite = { signIn.isCurrent && tigerSyncOn() }
        if (!mayWrite()) return SyncOutcome.SKIPPED
        return try {
            val synced = syncClubSubscriptions(subscriptions.document.value, documents(signIn), mayWrite)
            if (synced == null || !mayWrite()) return SyncOutcome.SKIPPED
            val first = subscriptions.adopt(synced, signIn)
            owed.set(false, signIn)
            if (first) scope.launch { firstSubscription(signIn) }
            SyncOutcome.SYNCED
        } catch (e: CancellationException) {
            throw e
        } catch (e: NotSignedInException) {
            // No session with the backend to send with: the edits stay unsent, as iOS's run with no client.
            SyncOutcome.SKIPPED
        } catch (e: Exception) {
            if (!mayWrite()) return SyncOutcome.SKIPPED
            owed.set(true, signIn)
            Log.w(TAG, "club subscriptions sync failed: ${e.logSummary()}")
            SyncOutcome.FAILED
        }
    }

    private companion object {
        const val TAG = "ClubSubscriptionsSync"
    }
}
