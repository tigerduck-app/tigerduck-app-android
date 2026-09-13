package org.ntust.app.tigerduck.ui.component

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.ntust.app.tigerduck.BuildConfig

enum class ServerKind {
    MOODLE,
    COURSE_SELECTION,
    BACKEND,
}

enum class ServerStatus {
    UNKNOWN,
    OK,
    FAILED,
}

object ServerStatusTracker {
    /** That build ships without Play Services and so never syncs. */
    private val IS_FDROID =
        BuildConfig.FLAVOR.equals("fdroid", ignoreCase = true)

    private val _statuses = MutableStateFlow<Map<ServerKind, ServerStatus>>(emptyMap())
    val statuses: StateFlow<Map<ServerKind, ServerStatus>> = _statuses.asStateFlow()

    /**
     * Whether the device is syncing through the backend, as opposed to only
     * reading the public part of it.
     *
     * This does not gate whether BACKEND has a status — it only picks which
     * word the header's detail list puts beside it, "OK" or "Minimal". The
     * backend is contacted either way, so it always has a real state to
     * report; see [noteBackendReachable].
     *
     * fdroid is folded in here because that build has no Play Services and
     * never syncs whatever the preference says, so reporting it as syncing
     * would put "OK" beside a sync that cannot happen. Kept in this object
     * rather than plumbed through five ViewModels because this is already
     * what the headers read.
     */
    private val _cloudSyncEnabled = MutableStateFlow(!IS_FDROID)
    val cloudSyncEnabled: StateFlow<Boolean> = _cloudSyncEnabled.asStateFlow()

    fun setCloudSyncEnabled(enabled: Boolean) {
        val effective = enabled && !IS_FDROID
        if (_cloudSyncEnabled.value == effective) return
        _cloudSyncEnabled.value = effective
        // The slot means a different thing on each side of this flip — a full
        // sync result vs. a public GET's reachability — so a reading taken
        // under the old meaning must not survive the change. Cleared rather
        // than recomputed: the next fetch of either kind fills it in, and a
        // moment of grey beats a stale green claiming a sync that is now off.
        if (!demoMode) _statuses.update { it - ServerKind.BACKEND }
    }

    /**
     * Report whether a *public* backend call got through.
     *
     * The academic calendar refresh is an unauthenticated GET that runs on
     * every app open no matter how sync is configured, which makes it the one
     * caller that can keep TigerSync's row honest when there is no sync to
     * report: the row reads Minimal-and-reachable, or Failed, rather than
     * sitting grey and unexplained forever. (The bulletin feed is public too
     * and could report here; the calendar is the one that always runs.)
     *
     * Ignored while sync is on. There the sync is the more demanding call and
     * its result is the authoritative one — letting a public GET that
     * happened to land later paint over a sync failure would hide exactly the
     * breakage the dot exists to surface.
     */
    fun noteBackendReachable(reachable: Boolean) {
        if (_cloudSyncEnabled.value) return
        set(if (reachable) ServerStatus.OK else ServerStatus.FAILED, ServerKind.BACKEND)
    }

    /**
     * Whether an NTUST account is signed in.
     *
     * Mirrored from `AuthService.authState` for the same reason as
     * [cloudSyncEnabled]: this object is already what the headers read, so
     * the five screens that draw a dot would otherwise each have to plumb
     * the flag down from their own ViewModel. `TigerDuckApp` collects the
     * auth state and keeps this in step, which also makes it impossible for
     * the two to disagree.
     *
     * Starts `false` on purpose. The collector is a StateFlow subscription
     * set up in `Application.onCreate`, so it delivers the real value before
     * the first frame, and hiding a dot for that window beats showing one
     * that claims a sync nobody asked for.
     */
    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    fun setSignedIn(signedIn: Boolean) {
        _signedIn.value = signedIn
    }

    /**
     * While the demo account is signed in: hold every source at OK and ignore
     * what the network says.
     *
     * A demo session refuses every request
     * ([org.ntust.app.tigerduck.debug.DemoModeInterceptor]), so without this
     * the header dot on five screens goes red and the reviewer sees a sync
     * error the real app does not have. Entered at the demo sign-in and from
     * `AppState` init after a restart; left at sign-out.
     */
    @Volatile
    private var demoMode = false

    fun enterDemoMode() {
        demoMode = true
        _statuses.value = ServerKind.entries.associateWith { ServerStatus.OK }
    }

    /** Sign-out: drop the held OKs so real results fill the dots in again. */
    fun exitDemoMode() {
        demoMode = false
        _statuses.value = emptyMap()
    }

    fun set(status: ServerStatus, server: ServerKind) {
        if (demoMode) return
        _statuses.update { it + (server to status) }
    }

    fun status(server: ServerKind): ServerStatus =
        _statuses.value[server] ?: ServerStatus.UNKNOWN

    fun reset() {
        if (demoMode) return
        _statuses.value = emptyMap()
    }
}
