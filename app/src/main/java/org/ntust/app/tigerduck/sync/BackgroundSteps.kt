package org.ntust.app.tigerduck.sync

import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.auth.retryingAfterRelogin
import org.ntust.app.tigerduck.data.PendingCourseAdds
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.notification.SyncSource
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.push.ClubSubscriptionsSyncer
import org.ntust.app.tigerduck.shared.account.SignIn

/**
 * The background worker's pass over the real stores and clients: narrower than Home's, as the
 * worker's own sync always ran (C2). It signs in again once on a refused session and fetches
 * again, where Home runs its whole pass again; it records no category as taken and moves no
 * watermark, which are Home's; it says where the data came from but shows no server status, as
 * no screen is up; and its steps after the pass are the worker's own, which it hands
 * [Synchronizer.sync]. It opens the first-pull gate as Home's pull does.
 */
internal class BackgroundSteps(
    private val prefs: AppPreferences,
    private val syncPolicies: SyncPolicies,
    private val dataCache: DataCache,
    private val authTokenManager: AuthTokenManager,
    /** The snapshot's fetch, `SyncApiClient.fetchFullSync`, which the [Synchronizer] alone hands out. */
    private val fetchSnapshot: suspend (SignIn) -> BackendSyncResult,
    /** Signs in again after the backend refused the session: `AuthService.attemptRelogin`. */
    private val relogin: suspend (SignIn) -> Boolean,
    private val pendingCourseAdds: PendingCourseAdds,
    private val firstPullGate: FirstPullGate,
    /** The club subscriptions' document, which syncs after each full sync; none in a test that leaves it out. */
    private val clubs: ClubSubscriptionsSyncer? = null,
) : PassSteps {

    override suspend fun mayRun(): Boolean {
        // cloudSyncEnabled already reads false on fdroid at its source (AppPreferences). No
        // session, and signing in got none: NONE, not LOCAL, which would light the "sync from
        // local only" indicator with no sync attempted.
        if (syncPolicies.current.cloudSync && authTokenManager.signInIfSignedOut()) return true
        prefs.setLastSyncSource(SyncSource.NONE)
        return false
    }

    override fun beginCourseSync(): DataCache.CourseSync = dataCache.beginCourseSync()

    override fun endCourseSync(courseSync: DataCache.CourseSync) = dataCache.endCourseSync(courseSync)

    /**
     * Nothing: the background leaves a course reset made elsewhere to Home (C2), so no delete is
     * ranked against its fetch, and the stamps the class table takes are not moved.
     */
    override suspend fun stampDeletes(): Long = 0L

    /**
     * A session the backend ended before its access token ran out draws a 401 until a sign-in,
     * and the uploads after the fetch would draw one too: signed in again once, the same request
     * goes again, under the sign-in the run began with. Each attempt marks the course window's
     * fetch, as the one after a relogin is the snapshot's.
     */
    override suspend fun fetch(signIn: SignIn, courseSync: DataCache.CourseSync): BackendSyncResult =
        retryingAfterRelogin(signIn, relogin) { pinned ->
            courseSync.fetching()
            fetchSnapshot(pinned)
        }

    override suspend fun listed(result: BackendSyncResult, signIn: SignIn) =
        pendingCourseAdds.listed(result.serverCourses, signIn)

    override fun policy(): SyncPolicy = syncPolicies.current

    override fun syncedBefore(): Set<SyncCategory> = categoriesSyncedBefore(prefs)

    /** Nothing: Home's sync alone records the categories it took, its pass being the whole one. */
    override fun recordTaken(pass: SyncPass, took: Set<String>) = Unit

    /** Nothing: the watermark is Home's, which its revision poll compares against. */
    override fun noteRevision(pass: SyncPass) = Unit

    /** A fresh process the worker woke has pulled: what waits for that may go, as after Home's pull. */
    override fun notePull(pass: SyncPass) = firstPullGate.notePull(pass.signIn)

    override fun synced() = prefs.setLastSyncSource(SyncSource.BACKEND)

    /**
     * The club document's sync, as after Home's pass; the worker's settings pull and reminders are
     * its own, which it hands the pass.
     */
    override fun afterSync(pass: SyncPass) {
        clubs?.afterFullSync(pass.signIn, pass.result.currentRevision)
    }

    override fun failed() = prefs.setLastSyncSource(SyncSource.LOCAL)
}
