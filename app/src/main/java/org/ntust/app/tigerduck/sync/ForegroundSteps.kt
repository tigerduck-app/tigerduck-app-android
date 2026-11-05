package org.ntust.app.tigerduck.sync

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.PendingCourseAdds
import org.ntust.app.tigerduck.data.PendingCourseDeletes
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.notification.SyncSource
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.push.ClubSubscriptionsSyncer
import org.ntust.app.tigerduck.push.NotificationSettingsSync
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.ui.component.ServerKind
import org.ntust.app.tigerduck.ui.component.ServerStatus
import org.ntust.app.tigerduck.ui.component.ServerStatusTracker
import org.ntust.app.tigerduck.ui.screen.settings.ReenableCheck
import org.ntust.app.tigerduck.ui.screen.settings.categoriesSynced
import org.ntust.app.tigerduck.util.logSummary
import org.ntust.app.tigerduck.widget.WidgetUpdater

/** Home's pass over the real stores, clients and status. */
internal class ForegroundSteps(
    private val prefs: AppPreferences,
    private val syncPolicies: SyncPolicies,
    private val dataCache: DataCache,
    private val authTokenManager: AuthTokenManager,
    /** The snapshot's fetch, `SyncApiClient.fetchFullSync`, which the [Synchronizer] alone hands out. */
    private val fetchSnapshot: suspend (SignIn) -> BackendSyncResult,
    private val pendingCourseAdds: PendingCourseAdds,
    private val pendingCourseDeletes: PendingCourseDeletes,
    private val firstPullGate: FirstPullGate,
    private val widgetUpdater: WidgetUpdater,
    private val reenableCheck: ReenableCheck,
    private val notificationSettingsSync: NotificationSettingsSync,
    private val appScope: CoroutineScope,
    /** The club subscriptions' document, which syncs after each full sync; none in a test that leaves it out. */
    private val clubs: ClubSubscriptionsSyncer? = null,
) : PassSteps {

    override suspend fun mayRun(): Boolean {
        // cloudSyncEnabled already reads false on fdroid at its source (AppPreferences), so the
        // policy's master switch needs no flavor check of its own here.
        if (syncPolicies.current.cloudSync && authTokenManager.signInIfSignedOut()) return true
        markBackendIdle()
        return false
    }

    /**
     * Report "no sync was attempted": no local-only banner, and TigerSync's row reading Minimal
     * rather than OK. Deliberately leaves the backend's status: [ServerStatusTracker.setCloudSyncEnabled]
     * drops a stale OK on the flip, the moment it stops meaning what it meant. Cleared here as
     * well, with sync off it would blank on every refresh the reachability the public GETs report.
     */
    private fun markBackendIdle() {
        prefs.setLastSyncSource(SyncSource.NONE)
        // Three reasons land here and only one of them is "switched off"; the
        // tracker folds fdroid in itself, and logged-out hides the dot.
        ServerStatusTracker.setCloudSyncEnabled(syncPolicies.current.cloudSync)
    }

    override fun beginCourseSync(): DataCache.CourseSync = dataCache.beginCourseSync()

    override fun endCourseSync(courseSync: DataCache.CourseSync) = dataCache.endCourseSync(courseSync)

    override suspend fun stampDeletes(): Long = pendingCourseDeletes.stamp()

    override suspend fun fetch(signIn: SignIn, courseSync: DataCache.CourseSync): BackendSyncResult {
        // An add back made from here on is newer than a course reset the snapshot reads. One
        // attempt: a refused session is Home's to sign in again for, and the whole pass runs again.
        courseSync.fetching()
        return fetchSnapshot(signIn)
    }

    override suspend fun listed(result: BackendSyncResult, signIn: SignIn) =
        pendingCourseAdds.listed(result.serverCourses, signIn)

    override fun policy(): SyncPolicy = syncPolicies.current

    override fun syncedBefore(): Set<SyncCategory> = categoriesSyncedBefore(prefs)

    /**
     * Only what this sync took, and only for the sign-in it was for: recorded after a sign-out, it
     * would mark the next session's categories before their first sync.
     */
    override fun recordTaken(pass: SyncPass, took: Set<String>) {
        val switchedOn = pass.policy.switchedOn.mapTo(HashSet(), SyncCategory::stored)
        prefs.school.syncedCategories.run { set(get() + categoriesSynced(switchedOn, took, prefs), pass.signIn) }
    }

    /**
     * Only for the account this sync fetched for: set after a sign-out reset it, the departing
     * account's would stand for the next one.
     */
    override fun noteRevision(pass: SyncPass) {
        pass.home.lastKnownRevision.set(pass.result.currentRevision, pass.signIn)
    }

    override fun notePull(pass: SyncPass) = firstPullGate.notePull(pass.signIn)

    override fun synced() {
        ServerStatusTracker.set(ServerStatus.OK, ServerKind.BACKEND)
        prefs.setLastSyncSource(SyncSource.BACKEND)
        widgetUpdater.requestUpdate()
    }

    override fun afterSync(pass: SyncPass) {
        // A re-enable check that failed runs again here rather than at
        // the next Settings visit, so what it holds is not held for days.
        reenableCheck.runIfOwed()
        // A full sync that just succeeded proves a session and a network,
        // so this is where a notification-settings edit (Live Update or
        // assignment reminders) whose push never landed — made while sync
        // was off or the phone was offline, or one whose retries ran out —
        // gets sent again. Catch-up only: it must never mark the device
        // dirty, or every sync would push this phone's values over the
        // other devices'. iOS re-sends at every full sync too.
        notificationSettingsSync.pushIfUnconfirmed()
        pullSettings(pass.result.currentRevision)
        // The club document sends and takes in one step, launched like the pull above.
        clubs?.afterFullSync(pass.signIn, pass.result.currentRevision)
    }

    /**
     * Where a change another device made to the notification settings is picked up. Android fires
     * assignment reminders itself, so without this an iPhone change reached this phone's alarms
     * only once the user opened one of the two settings screens. iOS reconciles after every full
     * sync as well. pullNow() applies nothing over an edit still waiting for its push, cannot
     * interleave with that push, and cancels or re-arms the reminders armed here when a value
     * changes. It already catches its own transport failures; the guard is so nothing else it
     * throws can turn this successful sync into a failed one.
     *
     * Launched, never awaited: the sync this refresh is showing has already succeeded by here, and
     * pullNow() waits for the document lock — which a queued push can hold for a whole
     * read-modify-write — and then reads the document. On a degrading connection that is up to a
     * minute of spinner (10 s connect / 20 s read, twice) for work Home is not showing. Nothing
     * downstream needs the result: the pull re-arms the reminders itself, and `scheduleAll` is
     * synchronized and reads the preferences at arm time, so whichever of it and Home's own arming
     * runs last is correct either way.
     *
     * On the application scope rather than the caller's: the caller is a viewModelScope that a
     * rotation or a swipe off Home cancels, which could land in the middle of the apply. On
     * Dispatchers.Main because pullNow()'s check-and-apply requires it — the application scope's
     * own dispatcher is Default.
     */
    private fun pullSettings(knownRevision: Long) {
        appScope.launch(Dispatchers.Main) {
            runCatching { notificationSettingsSync.pullNow(knownRevision = knownRevision) }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    Log.w(TAG, "[Sync] notification settings pull failed: ${e.logSummary()}")
                }
        }
    }

    override fun failed() {
        ServerStatusTracker.set(ServerStatus.FAILED, ServerKind.BACKEND)
        prefs.setLastSyncSource(SyncSource.LOCAL)
    }

    private companion object {
        const val TAG = "HomeViewModel"
    }
}

/**
 * The categories this account has synced on this phone (LWW D13): `syncedCategories`, and the marks
 * once a sync took them (`assignmentMarksSyncedOnce`), which is recorded at once, before a later
 * step of the pass can fail.
 */
internal fun categoriesSyncedBefore(prefs: AppPreferences): Set<SyncCategory> =
    SyncCategory.of(prefs.school.syncedCategories.get()) +
        if (prefs.school.assignmentMarksSyncedOnce.get()) setOf(SyncCategory.ASSIGNMENTS) else emptySet()
