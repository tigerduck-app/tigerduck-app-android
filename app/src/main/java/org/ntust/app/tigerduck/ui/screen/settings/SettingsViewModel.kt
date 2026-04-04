package org.ntust.app.tigerduck.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.network.LibraryService
import org.ntust.app.tigerduck.ui.AppState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    val appState: AppState,
    private val authService: AuthService,
    private val libraryService: LibraryService,
    private val credentials: CredentialManager,
    val prefs: AppPreferences
) : ViewModel() {

    val isNtustLoggingIn = authService.isLoggingIn
    val ntustLoginError = authService.loginError

    private val _libIsLoggingIn = MutableStateFlow(false)
    val libIsLoggingIn: StateFlow<Boolean> = _libIsLoggingIn

    private val _libLoginError = MutableStateFlow<String?>(null)
    val libLoginError: StateFlow<String?> = _libLoginError

    private val _isNtustLoggedIn = MutableStateFlow(appState.isNtustLoggedIn)
    val isNtustLoggedIn: StateFlow<Boolean> = _isNtustLoggedIn

    private val _isLibraryLoggedIn = MutableStateFlow(credentials.isLibraryTokenValid)
    val isLibraryLoggedIn: StateFlow<Boolean> = _isLibraryLoggedIn

    fun loginNtust(studentId: String, password: String) {
        viewModelScope.launch {
            val success = authService.login(studentId, password)
            _isNtustLoggedIn.value = success
        }
    }

    fun logoutNtust() {
        authService.logout()
        _isNtustLoggedIn.value = false
    }

    fun loginLibrary(username: String, password: String) {
        viewModelScope.launch {
            _libIsLoggingIn.value = true
            _libLoginError.value = null
            try {
                libraryService.login(username, password)
                _isLibraryLoggedIn.value = true
            } catch (e: Exception) {
                _libLoginError.value = e.message ?: "登入失敗"
            } finally {
                _libIsLoggingIn.value = false
            }
        }
    }

    fun logoutLibrary() {
        credentials.clearLibraryCredentials()
        _isLibraryLoggedIn.value = false
    }

    val libraryUsername: String? get() = credentials.libraryUsername
    val libraryTokenExpiry: Long get() = credentials.libraryTokenExpiry
    val ntustStudentId: String? get() = authService.storedStudentId
    val cookieExpiryMs: Long get() = appState.sessionManager.cookieExpiryMs
}
