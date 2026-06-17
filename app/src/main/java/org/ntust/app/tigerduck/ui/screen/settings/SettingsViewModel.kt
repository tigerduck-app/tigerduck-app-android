package org.ntust.app.tigerduck.ui.screen.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

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
