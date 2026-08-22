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
import android.util.Log
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
import org.ntust.app.tigerduck.push.CloudSyncCoordinator
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.push.PushDiagnostic
import org.ntust.app.tigerduck.push.PushIdentity
import org.ntust.app.tigerduck.push.PushRegistrationService
import org.ntust.app.tigerduck.push.SyncApiClient
import org.ntust.app.tigerduck.push.SyncIdMap
import org.ntust.app.tigerduck.push.SyncOutbox
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

    private companion object {
        const val TAG = "SyncReenable"
    }

    val cloudSyncCoordinator: CloudSyncCoordinator = CloudSyncCoordinator(
        pushApiClient = pushApiClient,
        pushRegistration = pushRegistration,
        prefs = prefs,
        outbox = SyncOutbox(context),
        idMap = SyncIdMap(context),
        scope = viewModelScope,
    )

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
        if (enabled) {
            cloudSyncCoordinator.enable()
        } else {
            cloudSyncCoordinator.disable()
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
        Log.i(TAG, "[reenable] marked category=$category pending=${prefs.pendingConflictCategories}")
    }

    fun checkPendingConflicts() {
        val pending = prefs.pendingConflictCategories
        if (pending.isEmpty()) {
            Log.d(TAG, "[reenable] checkPendingConflicts skip: pending is empty")
            return
        }
        Log.i(TAG, "[reenable] checkPendingConflicts start: pending=$pending")
        viewModelScope.launch {
            try {
                val result = syncApiClient.fetchFullSync()
                val diffs = mutableListOf<String>()

                if ("courses" in pending) {
                    val allCachedNos = dataCache.loadCourses().map { it.courseNo }.toSet()
                    val deletedNos = dataCache.loadDeletedCourseNos()
                    val localNos = allCachedNos - deletedNos
                    val serverNos = result.serverCourseNos
                    Log.i(TAG, "[reenable] courses: cached=${allCachedNos.size} deleted=${deletedNos.size} effective=${localNos.size} server=${serverNos.size}")
                    Log.d(TAG, "[reenable] courses local=${localNos.sorted()}")
                    Log.d(TAG, "[reenable] courses server=${serverNos.sorted()}")
                    if (localNos != serverNos) {
                        val localOnly = (localNos - serverNos).size
                        val serverOnly = (serverNos - localNos).size
                        Log.i(TAG, "[reenable] courses DIFFER: localOnly=$localOnly serverOnly=$serverOnly")
                        diffs.add(when {
                            localOnly > 0 && serverOnly > 0 -> context.getString(R.string.sync_conflict_reenable_courses, localOnly.toString(), serverOnly.toString())
                            localOnly > 0 -> context.getString(R.string.sync_conflict_reenable_courses_local_only, localOnly.toString())
                            else -> context.getString(R.string.sync_conflict_reenable_courses_server_only, serverOnly.toString())
                        })
                    } else {
                        Log.i(TAG, "[reenable] courses MATCH — no conflict")
                    }
                }

                if ("course_colors" in pending) {
                    val courses = dataCache.loadCourses()
                    val localColors = courses.associate { it.courseNo to it.customColorHex }
                    var colorMismatches = 0
                    for (override in result.courseOverrides) {
                        val courseNo = override.courseNo ?: continue
                        val serverHex = override.colorHex ?: continue
                        val localHex = localColors[courseNo]
                        if (localHex != serverHex) {
                            colorMismatches++
                            Log.d(TAG, "[reenable] color mismatch: $courseNo local=$localHex server=$serverHex")
                        }
                    }
                    Log.i(TAG, "[reenable] colors: ${if (colorMismatches == 0) "MATCH" else "DIFFER ($colorMismatches)"}")
                    if (colorMismatches > 0) {
                        diffs.add(context.getString(R.string.sync_conflict_reenable_colors_differ))
                    }
                }

                if ("course_names" in pending) {
                    val localNames = dataCache.loadCourseCustomNames()
                    var nameMismatches = 0
                    val serverNosWithNames = mutableSetOf<String>()
                    for (override in result.courseOverrides) {
                        val courseNo = override.courseNo ?: continue
                        if (override.customNames.isEmpty()) continue
                        serverNosWithNames.add(courseNo)
                        if ((localNames[courseNo] ?: emptyMap()) != override.customNames) {
                            nameMismatches++
                            Log.d(TAG, "[reenable] name mismatch: $courseNo local=${localNames[courseNo]} server=${override.customNames}")
                        }
                    }
                    for ((courseNo, locales) in localNames) {
                        if (locales.isNotEmpty() && courseNo !in serverNosWithNames) {
                            nameMismatches++
                            Log.d(TAG, "[reenable] name mismatch: $courseNo local=$locales server=default")
                        }
                    }
                    Log.i(TAG, "[reenable] names: ${if (nameMismatches == 0) "MATCH" else "DIFFER ($nameMismatches)"}")
                    if (nameMismatches > 0) {
                        diffs.add(context.getString(R.string.sync_conflict_reenable_names_differ))
                    }
                }

                if ("assignments" in pending) {
                    val localIgnored = dataCache.loadIgnoredAssignments()
                    val localCompleted = dataCache.loadMarkedCompletedAssignments()
                    val ignoredMatch = localIgnored == result.ignoredIds
                    val completedMatch = localCompleted == result.completedIds
                    Log.i(TAG, "[reenable] assignments: localIgnored=${localIgnored.size} serverIgnored=${result.ignoredIds.size} match=$ignoredMatch | localCompleted=${localCompleted.size} serverCompleted=${result.completedIds.size} match=$completedMatch")
                    if (!ignoredMatch || !completedMatch) {
                        diffs.add(context.getString(R.string.sync_conflict_reenable_assignments_differ))
                    }
                }

                Log.i(TAG, "[reenable] result: ${diffs.size} diffs → ${if (diffs.isEmpty()) "no conflict" else "SHOW POPUP"}")
                if (diffs.isNotEmpty()) {
                    _reenableConflict.value = ReenableConflict(
                        categories = pending.toList(),
                        description = diffs.joinToString("\n"),
                    )
                } else {
                    prefs.pendingConflictCategories = emptySet()
                }
            } catch (e: Exception) {
                Log.e(TAG, "[reenable] checkPendingConflicts FAILED — pending kept for retry", e)
            }
        }
    }

    fun resolveReenableConflict(keepLocal: Boolean) {
        val conflict = _reenableConflict.value ?: return
        Log.i(TAG, "[reenable] resolve: keepLocal=$keepLocal categories=${conflict.categories}")
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
                if ("course_colors" in conflict.categories) {
                    val courses = dataCache.loadCourses()
                    for (course in courses) {
                        val hex = course.customColorHex ?: continue
                        val moodleId = course.moodleIdNumber ?: continue
                        runCatching { pushApiClient.patchCourseOverride(moodleId, colorHex = hex) }
                    }
                }
                if ("course_names" in conflict.categories) {
                    val customNames = dataCache.loadCourseCustomNames()
                    val courses = dataCache.loadCourses()
                    val noToMoodle = courses.mapNotNull { c ->
                        c.moodleIdNumber?.let { c.courseNo to it }
                    }.toMap()
                    for ((courseNo, locales) in customNames) {
                        val moodleId = noToMoodle[courseNo] ?: continue
                        for ((locale, name) in locales) {
                            if (name.isNotEmpty()) {
                                runCatching { pushApiClient.patchCourseOverride(moodleId, customName = name, locale = locale) }
                            }
                        }
                    }
                }
                if ("assignments" in conflict.categories) {
                    for (id in dataCache.loadIgnoredAssignments()) {
                        val intId = id.toIntOrNull() ?: continue
                        runCatching { pushApiClient.patchAssignmentOverride(intId, "ignored") }
                    }
                    for (id in dataCache.loadMarkedCompletedAssignments()) {
                        val intId = id.toIntOrNull() ?: continue
                        runCatching { pushApiClient.patchAssignmentOverride(intId, "locally_completed") }
                    }
                }
            } else {
                if ("courses" in conflict.categories) {
                    dataCache.saveDeletedCourseNos(emptySet())
                }
                if ("course_names" in conflict.categories) {
                    dataCache.saveCourseCustomNames(emptyMap())
                }
                if ("assignments" in conflict.categories) {
                    dataCache.replaceIgnoredAssignments(emptySet())
                    dataCache.replaceMarkedCompletedAssignments(emptySet())
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
                    if ("course_names" in conflict.categories) {
                        val localNames = dataCache.loadCourseCustomNames().toMutableMap()
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
                        dataCache.saveCourseCustomNames(localNames)
                    }
                    if ("assignments" in conflict.categories) {
                        dataCache.replaceIgnoredAssignments(result.ignoredIds)
                        dataCache.replaceMarkedCompletedAssignments(result.completedIds)
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
