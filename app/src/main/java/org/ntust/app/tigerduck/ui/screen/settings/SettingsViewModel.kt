package org.ntust.app.tigerduck.ui.screen.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.CourseColorStore
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.liveactivity.LiveActivityManager
import org.ntust.app.tigerduck.notification.AssignmentNotificationScheduler
import org.ntust.app.tigerduck.notification.BackgroundSyncWorker
import org.ntust.app.tigerduck.shared.LibraryService
import org.ntust.app.tigerduck.analytics.AnalyticsLogger
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.push.PushDiagnostic
import org.ntust.app.tigerduck.push.PushIdentity
import org.ntust.app.tigerduck.push.PushRegistrationService
import org.ntust.app.tigerduck.push.SyncApiClient
import org.ntust.app.tigerduck.ui.AppState
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
    private val notificationScheduler: AssignmentNotificationScheduler,
    private val courseColorStore: CourseColorStore,
    private val liveActivityManager: LiveActivityManager,
    private val wearBridge: WearScheduleBridge,
    val identity: PushIdentity,
    private val pushRegistration: PushRegistrationService,
    private val pushApiClient: PushApiClient,
    private val syncApiClient: SyncApiClient,
    private val courseService: CourseService,
    private val dataCache: DataCache,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    private val _syncDiagnostic = MutableStateFlow(PushDiagnostic(false, false, null, null, null))
    val syncDiagnostic: StateFlow<PushDiagnostic> = _syncDiagnostic

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing

    private val _serverPushOn = MutableStateFlow(!pushRegistration.isServerPushOptedOut())
    val serverPushOn: StateFlow<Boolean> = _serverPushOn

    private val _isTogglingPush = MutableStateFlow(false)

    init {
        pushRegistration.diagnostic
            .onEach { d -> _syncDiagnostic.value = d }
            .launchIn(viewModelScope)
    }

    fun setServerPushOn(isOn: Boolean) {
        if (_isTogglingPush.value || _serverPushOn.value == isOn) return
        _serverPushOn.value = isOn
        _isTogglingPush.value = true
        viewModelScope.launch {
            try { pushRegistration.updateServerPushOptOut(optOut = !isOn) }
            finally { _isTogglingPush.value = false }
        }
    }

    fun pushCloudSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            pushRegistration.updateCloudSyncEnabled(enabled)
        }
    }

    fun pushSyncPreferences() {
        viewModelScope.launch {
            pushRegistration.updateSyncPreferences(
                syncCourses = prefs.syncCourses,
                syncCourseColors = prefs.syncCourseColors,
                syncCourseNames = prefs.syncCourseNames,
                syncAssignments = prefs.syncAssignments,
            )
        }
    }

    data class ReenableConflict(
        val categories: List<String>,
        val description: String,
    )

    private val _reenableConflict = MutableStateFlow<ReenableConflict?>(null)
    val reenableConflict: StateFlow<ReenableConflict?> = _reenableConflict

    fun markCategoryReenabled(category: String) {
        prefs.pendingConflictCategories = prefs.pendingConflictCategories + category
    }

    fun checkPendingConflicts() {
        val pending = prefs.pendingConflictCategories
        if (pending.isEmpty()) return
        viewModelScope.launch {
            try {
                val result = syncApiClient.fetchFullSync()
                val diffs = mutableListOf<String>()

                if ("courses" in pending) {
                    val localNos = dataCache.loadCourses().map { it.courseNo }.toSet()
                    val serverNos = result.serverCourseNos
                    if (localNos != serverNos) {
                        val localOnly = (localNos - serverNos).size
                        val serverOnly = (serverNos - localNos).size
                        diffs.add(when {
                            localOnly > 0 && serverOnly > 0 -> context.getString(R.string.sync_conflict_reenable_courses, localOnly.toString(), serverOnly.toString())
                            localOnly > 0 -> context.getString(R.string.sync_conflict_reenable_courses_local_only, localOnly.toString())
                            else -> context.getString(R.string.sync_conflict_reenable_courses_server_only, serverOnly.toString())
                        })
                    }
                }
                if ("course_colors" in pending && result.courseOverrides.any { it.colorHex != null }) {
                    diffs.add(context.getString(R.string.sync_conflict_reenable_colors_differ))
                }
                if ("course_names" in pending && result.courseOverrides.any { it.customNames.isNotEmpty() }) {
                    diffs.add(context.getString(R.string.sync_conflict_reenable_names_differ))
                }

                if (diffs.isNotEmpty()) {
                    _reenableConflict.value = ReenableConflict(
                        categories = pending.toList(),
                        description = diffs.joinToString("\n"),
                    )
                } else {
                    prefs.pendingConflictCategories = emptySet()
                }
            } catch (_: Exception) {
                prefs.pendingConflictCategories = emptySet()
            }
        }
    }

    fun resolveReenableConflict(keepLocal: Boolean) {
        val conflict = _reenableConflict.value ?: return
        _reenableConflict.value = null
        prefs.pendingConflictCategories = emptySet()
        viewModelScope.launch {
            if (keepLocal) {
                if ("courses" in conflict.categories) {
                    val courses = dataCache.loadCourses()
                    val semester = courseService.currentSemesterCode()
                    runCatching { pushApiClient.deleteAllCourses() }
                    runCatching { pushApiClient.uploadCourses(courses, semester) }
                }
            } else {
                if ("courses" in conflict.categories) {
                    dataCache.saveDeletedCourseNos(emptySet())
                }
                val result = runCatching { syncApiClient.fetchFullSync() }.getOrNull()
                if (result != null) {
                    if ("courses" in conflict.categories && result.serverCourseNos.isNotEmpty()) {
                        val localCourses = dataCache.loadCourses()
                        val deleted = localCourses.map { it.courseNo }.toSet() - result.serverCourseNos
                        if (deleted.isNotEmpty()) {
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
                }
            }
        }
    }

    fun syncNow() {
        if (_isSyncing.value) return
        _isSyncing.value = true
        viewModelScope.launch {
            try { pushRegistration.syncNow() }
            finally { _isSyncing.value = false }
        }
    }

    val isNtustLoggingIn = authService.isLoggingIn
    val ntustLoginError = authService.loginError

    private val _libIsLoggingIn = MutableStateFlow(false)
    val libIsLoggingIn: StateFlow<Boolean> = _libIsLoggingIn

    private val _libLoginError = MutableStateFlow<String?>(null)
    val libLoginError: StateFlow<String?> = _libLoginError

    val isNtustLoggedIn: StateFlow<Boolean> = authService.authState

    private val _isLibraryLoggedIn = MutableStateFlow(credentials.isLibraryTokenValid)
    val isLibraryLoggedIn: StateFlow<Boolean> = _isLibraryLoggedIn

    fun refreshLoginState() {
        _isLibraryLoggedIn.value = credentials.isLibraryTokenValid
    }

    fun loginNtust(studentId: String, password: String) {
        viewModelScope.launch {
            val success = authService.login(studentId, password)
            if (success) BackgroundSyncWorker.schedule(context)
        }
    }

    fun logoutNtust() {
        // Persisted-data clearing now lives in AuthService.logout() on an
        // application-scoped coroutine, so it survives ViewModel destruction
        // when the user navigates away from Settings immediately after logout.
        authService.logout()
        _isLibraryLoggedIn.value = false
        notificationScheduler.cancelAllTracked()
        liveActivityManager.stop()
        BackgroundSyncWorker.cancel(context)
    }

    fun loginLibrary(username: String, password: String) {
        viewModelScope.launch {
            _libIsLoggingIn.value = true
            _libLoginError.value = null
            try {
                libraryService.login(username, password)
                _isLibraryLoggedIn.value = true
                // The NTUST authState collector in TigerDuckApp pushes library
                // credentials on NTUST login/logout, but a Settings-only
                // library login/logout doesn't flip that state — so mirror the
                // fresh creds to the watch here.
                wearBridge.publishLibraryCredentials()
            } catch (e: Exception) {
                _libLoginError.value = e.message?.takeUnless { it.isBlank() }
                    ?: context.getString(R.string.error_sign_in_failed)
            } finally {
                _libIsLoggingIn.value = false
            }
        }
    }

    fun logoutLibrary() {
        credentials.clearLibraryCredentials()
        _isLibraryLoggedIn.value = false
        viewModelScope.launch { wearBridge.publishLibraryCredentials() }
    }

    val libraryUsername: String? get() = credentials.libraryUsername
    val libraryTokenExpiry: Long get() = credentials.libraryTokenExpiry
    val ntustStudentId: String? get() = authService.storedStudentId

    fun cancelAllAssignmentNotifications() = notificationScheduler.cancelAllTracked()

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
