package org.ntust.app.tigerduck.ui.screen.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import android.util.Log
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.CourseColorStore
import org.ntust.app.tigerduck.data.CourseIgnoreSync
import org.ntust.app.tigerduck.data.PendingCourseOverrides
import org.ntust.app.tigerduck.data.UnsentMarks
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.library.libraryErrorMessage
import org.ntust.app.tigerduck.data.sharing.ImportedClassTableStore
import org.ntust.app.tigerduck.data.sharing.ImportedClassTables
import org.ntust.app.tigerduck.network.tls.PinManager
import org.ntust.app.tigerduck.shared.LibraryService
import org.ntust.app.tigerduck.analytics.AnalyticsLogger
import org.ntust.app.tigerduck.data.CourseTombstoneKeys
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.debug.DebugFixtureStore
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.demo.DemoAccount
import org.ntust.app.tigerduck.network.SemesterCatalog
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.push.PushDiagnostic
import org.ntust.app.tigerduck.push.PushIdentity
import org.ntust.app.tigerduck.push.PushRegistrationService
import org.ntust.app.tigerduck.push.SyncApiClient
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.component.ServerStatusTracker
import org.ntust.app.tigerduck.util.logSummary
import org.ntust.app.tigerduck.wear.WearScheduleBridge
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    val appState: AppState,
    private val analyticsLogger: AnalyticsLogger,
    private val authService: AuthService,
    private val libraryService: LibraryService,
    private val credentials: CredentialManager,
    val prefs: AppPreferences,
    private val courseColorStore: CourseColorStore,
    private val wearBridge: WearScheduleBridge,
    val identity: PushIdentity,
    private val pushRegistration: PushRegistrationService,
    private val pushApiClient: PushApiClient,
    private val syncApiClient: SyncApiClient,
    private val semesterCatalog: SemesterCatalog,
    private val dataCache: DataCache,
    private val signOutLossCheck: SignOutLossCheck,
    private val debugFixtures: DebugFixtureStore,
    private val demoAccount: DemoAccount,
    private val courseIgnoreSync: CourseIgnoreSync,
    private val unsentMarks: UnsentMarks,
    private val pendingCourseOverrides: PendingCourseOverrides,
    private val reenableCheck: ReenableCheck,
    private val importedClassTableStore: ImportedClassTableStore,
    @param:ApplicationScope private val appScope: CoroutineScope,
    @param:ApplicationContext private val context: Context,
    /** Debug builds' Developer page simulates a pin mismatch through it. */
    val pinManager: PinManager,
) : ViewModel() {

    /** For the NTUST sign-out, which asks first when it would delete imported class tables. */
    val importedClassTables: StateFlow<ImportedClassTables> = importedClassTableStore.tables


    private companion object {
        const val TAG = "SyncReenable"

    }

    private val _syncDiagnostic = MutableStateFlow(PushDiagnostic(false, false, null))
    val syncDiagnostic: StateFlow<PushDiagnostic> = _syncDiagnostic

    private val _serverPushOn = MutableStateFlow(!pushRegistration.isServerPushOptedOut())
    val serverPushOn: StateFlow<Boolean> = _serverPushOn

    private val _isTogglingPush = MutableStateFlow(false)
    val isTogglingServerPush: StateFlow<Boolean> = _isTogglingPush

    /**
     * One-shot signal for [CloudSyncSettingsScreen] to surface
     * `settings_server_push_update_failed` (e.g. via a Toast) when the
     * server rejects a server-push preference change. Not a [StateFlow]:
     * there is no "current" failure to replay to a screen that
     * recomposes after the fact, only an event to react to once.
     */
    private val _serverPushUpdateFailed = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val serverPushUpdateFailed: SharedFlow<Unit> = _serverPushUpdateFailed.asSharedFlow()

    init {
        pushRegistration.diagnostic
            .onEach { d -> _syncDiagnostic.value = d }
            .launchIn(viewModelScope)
    }

    /**
     * Optimistically flips the switch, then reverts it to [previous] if the
     * backend rejects the change — a rejected PATCH must not leave the UI
     * claiming a preference took effect when it didn't. This only needs to
     * revert the in-memory flag: `PushRegistrationService.updateServerPushOptOut`
     * defers its own persisted write until the backend accepts the change,
     * so a rejected PATCH never reaches storage in the first place — see
     * `applyOptOutIfAccepted`.
     */
    fun setServerPushOn(isOn: Boolean) {
        if (_isTogglingPush.value || _serverPushOn.value == isOn) return
        val previous = _serverPushOn.value
        _serverPushOn.value = isOn
        _isTogglingPush.value = true
        viewModelScope.launch {
            try {
                val success = pushRegistration.updateServerPushOptOut(optOut = !isOn)
                if (!success) {
                    _serverPushOn.value = previous
                    _serverPushUpdateFailed.tryEmit(Unit)
                }
            } finally {
                _isTogglingPush.value = false
            }
        }
    }

    fun pushCloudSyncEnabled(enabled: Boolean) {
        ServerStatusTracker.setCloudSyncEnabled(enabled)
        if (enabled) {
            // Fail closed: the preference is recorded only once the server
            // has accepted the device, so a failed registration cannot leave
            // the app believing it is syncing when it is not. The service
            // records it, before any registration crossing the PATCH ends.
            viewModelScope.launch {
                runCatching { pushRegistration.updateCloudSyncEnabled(true) }
                    .onFailure {
                        ServerStatusTracker.setCloudSyncEnabled(false)
                        Log.w(TAG, "enabling cloud sync failed: ${it.logSummary()}")
                    }
            }
        } else {
            // Fail open, and synchronously rather than inside the launch:
            // viewModelScope may be cancelled before the coroutine runs
            // (toggle off, then leave the screen), and a preference left true
            // would silently re-activate sync on the next launch.
            prefs.cloudSyncEnabled = false
            // The tracker was already told at the top of this function, which
            // is what makes the row match the switch immediately rather than
            // at the next pull — and dropped the now-meaningless full-sync
            // reading as it flipped. The next public GET fills the row back
            // in as Minimal.
            viewModelScope.launch {
                runCatching { pushRegistration.updateCloudSyncEnabled(false) }
                    .onFailure { Log.w(TAG, "disabling cloud sync: server update failed (non-fatal): ${it.logSummary()}") }
            }
        }
    }

    fun pushSyncPreferences() {
        viewModelScope.launch {
            pushRegistration.updateSyncPreferences(
                syncCourses = prefs.syncCourses,
                syncCourseColors = prefs.syncCourseColors,
                syncCourseNames = prefs.syncCourseNames,
                syncAssignments = prefs.syncAssignments,
                syncAssignmentReminders = prefs.syncAssignmentReminders,
                syncLiveActivity = prefs.syncLiveActivity,
            )
        }
    }

    data class ReenableConflict(
        val categories: List<String>,
        val description: String,
        /** The snapshot the check compared, for Use Local when it cannot read the server again. */
        val checked: BackendSyncResult? = null,
    )

    private val _reenableConflict = MutableStateFlow<ReenableConflict?>(null)
    val reenableConflict: StateFlow<ReenableConflict?> = _reenableConflict

    fun markCategoryReenabled(category: String) {
        markForReenableCheck(prefs, category, signedIn = authService.authState.value)
    }

    /** See [ReenableCheck.check]; a difference is asked about on this screen. */
    fun checkPendingConflicts() {
        reenableCheck.check { _reenableConflict.value = it }
    }

    fun resolveReenableConflict(keepLocal: Boolean) {
        val conflict = _reenableConflict.value ?: return
        Log.i(TAG, "[reenable] resolve: keepLocal=$keepLocal categories=${conflict.categories}")
        _reenableConflict.value = null
        // Full syncs leave assignment marks and course ignores alone while
        // "assignments" is pending, and colours and names while theirs are,
        // so these are let go only once every answer has sent, queued or
        // adopted them (heldUntilAnswered).
        // On the application scope, so leaving Settings cannot strand it,
        // and tied to this account: a sign-out ends it, and drops any write
        // still on its way.
        val held = heldUntilAnswered(conflict.categories)
        val left = unanswered(prefs.pendingConflictCategories, conflict.categories)
        val holdsCourseIgnores = "assignments" in held
        // Use Local's terms are latched before the courses check lets the
        // course list go: a sync in between would un-hide a course deleted
        // here before Use Local sends its delete.
        val latch = handOverCourseHold(
            useLocal = keepLocal && "courses" in conflict.categories,
            semesters = semesterCatalog::availableSemesters,
            host = useLocalHost,
        ) {
            prefs.pendingConflictCategories = held + left
        }
        val generation = dataCache.userDataGeneration
        reenableCheck.resolutions.start()
        // What the dialog did not cover is checked from this screen once the
        // answer has been applied: the check waits for it, and then settles
        // the hold in its place (ReenableResolutions.finish).
        if (left.isNotEmpty()) checkPendingConflicts()
        appScope.launch(DataCache.Generation(generation)) {
            try {
                coroutineScope {
                    val signOut = launch {
                        authService.authState.first { !it }
                        this@coroutineScope.cancel()
                    }
                    resolve(conflict, keepLocal, generation, latch)
                    signOut.cancel()
                }
            } finally {
                // Already let go when Use Local ran; this covers a sign-out before it.
                latch?.release()
                // Each Settings screen has its own view model, and an answer
                // given on one is still being applied when the next one checks.
                if (reenableCheck.resolutions.finish()) {
                    prefs.pendingConflictCategories = prefs.pendingConflictCategories - held
                }
            }
            if (holdsCourseIgnores) courseIgnoreSync.retryPending()
            // Use Local queued its colours and names behind their holds, lifted just above.
            if (keepLocal && ("course_colors" in held || "course_names" in held)) pendingCourseOverrides.retry()
        }
    }

    private suspend fun resolve(
        conflict: ReenableConflict,
        keepLocal: Boolean,
        generation: Int,
        latch: UseLocalLatch?,
    ) {
        if (keepLocal) {
            val resetTerms = if (latch != null) sendLocalCourses(generation, latch) else emptySet()
            // Through the outbox, queued whole at a version reserved before
            // the colours and names are read, so an edit made from here on is
            // the newer. The outbox holds them while their category is
            // pending and sends them once resolveReenableConflict has lifted
            // the hold; one that fails to land is sent again at the next sync.
            val version = pendingCourseOverrides.reserveVersion()
            if ("course_colors" in conflict.categories) {
                pendingCourseOverrides.queue(
                    dataCache.loadCourses().mapNotNull { course ->
                        val hex = course.customColorHex ?: return@mapNotNull null
                        val moodleId = course.moodleIdNumber ?: return@mapNotNull null
                        PendingCourseOverrides.Change(moodleId, colorHex = hex)
                    },
                    version,
                )
            }
            if ("course_names" in conflict.categories) {
                val noToMoodle = dataCache.loadCourses().mapNotNull { c ->
                    c.moodleIdNumber?.let { c.courseNo to it }
                }.toMap()
                pendingCourseOverrides.queue(
                    dataCache.loadCourseCustomNames().flatMap { (courseNo, locales) ->
                        val moodleId = noToMoodle[courseNo] ?: return@flatMap emptyList<PendingCourseOverrides.Change>()
                        locales.filterValues { it.isNotEmpty() }.map { (locale, name) ->
                            PendingCourseOverrides.Change(moodleId, customName = name, locale = locale)
                        }
                    },
                    version,
                )
            }
            if ("assignments" in conflict.categories) {
                // Syncs leave the marks alone while "assignments" is pending,
                // so the cache changes now only by taps on Home: the newest.
                keepLocalMarks(
                    conflict = conflict,
                    fetch = syncApiClient::fetchFullSync,
                    marksVersion = { dataCache.assignmentMarksVersion.value },
                    localMarks = {
                        dataCache.loadIgnoredAssignments() to dataCache.loadMarkedCompletedAssignments()
                    },
                    send = pushApiClient::patchAssignmentOverride,
                    keep = { id, status -> unsentMarks.keep(id, status, generation) },
                    pushCourseIgnores = { server ->
                        if (prefs.syncCourses) {
                            courseIgnoreSync.pushLocal(server, generation, resetTerms, conflict.checked)
                        }
                    },
                )
            }
        } else {
            if ("courses" in conflict.categories) {
                dataCache.saveDeletedCourseNos(emptySet())
            }
            if ("course_names" in conflict.categories) {
                dataCache.saveCourseCustomNames(emptyMap())
            }
            // The server's colours and names win, so changes still waiting
            // to go up are dropped; a category not here is left alone.
            pendingCourseOverrides.adoptServer(conflict.categories)
            if ("assignments" in conflict.categories) {
                // The server's marks win, so taps still waiting to go up are dropped.
                unsentMarks.forget()
                // Adopted, not replaced: Home reads them again at once.
                dataCache.adoptAssignmentMarks(emptySet(), emptySet())
            }
            val result = runCatching { syncApiClient.fetchFullSync() }.getOrNull()
            if (result != null) {
                if ("courses" in conflict.categories) {
                    // Every term the check compared — see coursesUseServerHides.
                    val terms = (result.serverCourses.map { it.semester } + semesterCatalog.availableSemesters())
                        .filterTo(sortedSetOf()) { it.isNotBlank() }
                    val cached = terms.associateWith { term -> dataCache.loadCourses(term).map { it.courseNo } }
                    val hides = coursesUseServerHides(result.serverCourses, cached)
                    if (hides.isNotEmpty()) {
                        // Scoped, and added to the store rather than
                        // replacing it: an unscoped overwrite dropped every
                        // other term's tombstones on the floor.
                        var deleted = dataCache.loadDeletedCourseNos()
                        for ((semester, nos) in hides) {
                            for (no in nos) deleted = CourseTombstoneKeys.hide(no, semester, deleted)
                        }
                        dataCache.saveDeletedCourseNos(deleted)
                    }
                }
                if ("course_colors" in conflict.categories || "course_names" in conflict.categories) {
                    if (result.courseOverrides.isNotEmpty()) {
                        val courses = dataCache.loadCourses()
                        val updated = courses.map { course ->
                            val override = result.courseOverrides.find { it.courseNo == course.courseNo }
                                ?: return@map course
                            var c = course
                            if ("course_colors" in conflict.categories && override.colorHex != null) {
                                c = c.copy(customColorHex = override.colorHex)
                            }
                            c
                        }
                        dataCache.saveCourses(updated)
                    }
                }
                if ("course_names" in conflict.categories) {
                    // Onto the names as they stand, under their lock: a rename made meanwhile stays.
                    dataCache.updateCourseCustomNames { current ->
                        val localNames = current.toMutableMap()
                        for (override in result.courseOverrides) {
                            val courseNo = override.courseNo ?: continue
                            if (override.customNames.isNotEmpty()) {
                                val existing = localNames[courseNo]?.toMutableMap() ?: mutableMapOf()
                                for ((locale, name) in override.customNames) {
                                    if (name.isEmpty()) existing.remove(locale) else existing[locale] = name
                                }
                                if (existing.isEmpty()) localNames.remove(courseNo) else localNames[courseNo] = existing
                            }
                        }
                        localNames
                    }
                }
                if ("assignments" in conflict.categories) {
                    dataCache.adoptAssignmentMarks(result.ignoredIds, result.completedIds)
                }
            }
            if ("assignments" in conflict.categories && prefs.syncCourses) {
                courseIgnoreSync.adoptServer(result, generation)
            }
        }
    }

    private val useLocalHost = object : UseLocalHost {
        override fun beginReset(semester: String) = dataCache.beginReset(semester)
        override fun endReset(semester: String) = dataCache.endReset(semester)
        override suspend fun stampReset(semester: String, atMs: Long) {
            dataCache.saveSemesterResetAt(semester, atMs)
            // Its courses come back as new rows, which are not re-added elsewhere.
            courseIgnoreSync.forgetRowsSeen(setOf(semester))
        }
        override fun askAgain() = markCategoryReenabled("courses")
    }

    /**
     * Use Local for courses, every term this phone knows — see
     * [useLocalCourses]. Returns the terms it reset.
     */
    private suspend fun sendLocalCourses(generation: Int, latch: UseLocalLatch): Set<String> {
        // The terms latched on the tap; the catalogue may have moved since.
        val semesters = latch.semesters
        val portalCached = semesters.filterTo(mutableSetOf()) { dataCache.hasPortalCourses(it) }
        return useLocalCourses(
            terms = semesters.map { it to dataCache.loadCourses(it) },
            hiding = dataCache.loadDeletedCourseNos(),
            backend = PushCourseSyncBackend(pushApiClient, prefs, pendingCourseOverrides),
            // A term's reset takes its courses' names off the server with
            // them. With name sync off here nothing can be sent back (the
            // PATCH honours the switch), so names other devices set on that
            // term's courses are lost: the price of Use Local resetting a term.
            customNames = if (prefs.syncCourseNames) dataCache.loadCourseCustomNames() else emptyMap(),
            portalCached = { it in portalCached },
            latch = latch,
            host = useLocalHost,
            now = System::currentTimeMillis,
            isCurrent = { dataCache.userDataGeneration == generation },
        )
    }

    val isNtustLoggingIn = authService.isLoggingIn
    val ntustLoginError = authService.loginError

    private val _libIsLoggingIn = MutableStateFlow(false)
    val libIsLoggingIn: StateFlow<Boolean> = _libIsLoggingIn

    private val _libLoginError = MutableStateFlow<String?>(null)
    val libLoginError: StateFlow<String?> = _libLoginError

    val isNtustLoggedIn: StateFlow<Boolean> = authService.authState

    val isLibraryLoggedIn: StateFlow<Boolean> = appState.librarySession.signedIn

    fun refreshLoginState() {
        appState.librarySession.refresh()
    }

    fun loginNtust(studentId: String, password: String) {
        // The background sync follows the sign-in: AccountSessionEffects.
        viewModelScope.launch { authService.login(studentId, password) }
    }

    private val _signOutConfirmation = MutableStateFlow<Set<SignOutLoss>?>(null)

    /** What the NTUST sign-out waiting for an answer would delete; null when none is waiting. */
    val signOutConfirmation: StateFlow<Set<SignOutLoss>?> = _signOutConfirmation

    /**
     * The Sign out button. A sign-out deletes this phone's data, so it asks first when that
     * holds anything that exists nowhere else ([SignOutLoss]), and signs out at once otherwise.
     */
    fun requestNtustSignOut() {
        // A second tap while the first is still reading the phone's data.
        if (checkingSignOut) return
        checkingSignOut = true
        viewModelScope.launch {
            try {
                val losses = signOutLossCheck.losses()
                if (losses.isEmpty()) logoutNtust() else _signOutConfirmation.value = losses
            } finally {
                checkingSignOut = false
            }
        }
    }

    private var checkingSignOut = false

    fun confirmNtustSignOut() {
        _signOutConfirmation.value = null
        logoutNtust()
    }

    fun dismissNtustSignOut() {
        _signOutConfirmation.value = null
    }

    fun logoutNtust() {
        // Persisted-data clearing now lives in AuthService.logout() on an
        // application-scoped coroutine, so it survives ViewModel destruction
        // when the user navigates away from Settings immediately after logout.
        // The reminders, notifications, Live Update and background sync follow
        // the sign-out itself: AccountSessionEffects.
        authService.logout()
        appState.librarySession.refresh()
    }

    fun loginLibrary(username: String, password: String) {
        viewModelScope.launch {
            _libIsLoggingIn.value = true
            _libLoginError.value = null
            try {
                // The demo library password signs in on the device, with or
                // without the demo account; see [DemoAccount.signInLibrary].
                if (demoAccount.signInLibrary(username, password)) {
                    appState.librarySession.refresh()
                    return@launch
                }
                // The demo account itself never reaches the library, so
                // anything else fails there the way a wrong password would.
                if (demoAccount.isActive) {
                    _libLoginError.value = context.getString(R.string.error_sign_in_failed)
                    return@launch
                }
                libraryService.login(username, password, credentials.libraryAccountGeneration, byUser = true)
                demoAccount.signOutLibrary()
                appState.librarySession.refresh()
                // The NTUST authState collector in TigerDuckApp pushes library
                // credentials on NTUST login/logout, but a Settings-only
                // library login/logout doesn't flip that state — so mirror the
                // fresh creds to the watch here.
                wearBridge.publishLibraryCredentials()
            } catch (e: Exception) {
                _libLoginError.value = libraryErrorMessage(e, R.string.error_sign_in_failed, context::getString)
            } finally {
                _libIsLoggingIn.value = false
            }
        }
    }

    fun logoutLibrary() {
        demoAccount.signOutLibrary()
        credentials.clearLibraryCredentials()
        appState.librarySession.refresh()
        viewModelScope.launch { wearBridge.publishLibraryCredentials() }
    }

    val libraryUsername: String?
        get() = demoAccount.libraryUsername ?: credentials.libraryUsername
    val libraryTokenExpiry: Long get() = credentials.libraryTokenExpiry
    val ntustStudentId: String?
        get() = debugFixtures.studentIdOverride
            ?: authService.storedStudentId

    fun clearNtustLoginError() = authService.clearLoginError()

    fun resetCourseColors() {
        viewModelScope.launch { courseColorStore.resetAllColors() }
    }

    fun setAnalyticsEnabled(enabled: Boolean) {
        prefs.analyticsEnabled = enabled
        analyticsLogger.setEnabled(enabled)
    }

    fun setAppLanguage(language: String) {
        appState.appLanguage = language
    }
}
