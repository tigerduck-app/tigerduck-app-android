package com.tigerduck.app.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tigerduck.app.auth.AuthService
import com.tigerduck.app.data.preferences.AppPreferences
import com.tigerduck.app.data.preferences.CredentialManager
import com.tigerduck.app.network.LibraryService
import com.tigerduck.app.ui.AppState
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

    fun loginNtust(studentId: String, password: String) {
        viewModelScope.launch {
            authService.login(studentId, password)
        }
    }

    fun logoutNtust() {
        authService.logout()
    }

    fun loginLibrary(username: String, password: String) {
        viewModelScope.launch {
            _libIsLoggingIn.value = true
            _libLoginError.value = null
            try {
                libraryService.login(username, password)
            } catch (e: Exception) {
                _libLoginError.value = e.message ?: "登入失敗"
            } finally {
                _libIsLoggingIn.value = false
            }
        }
    }

    fun logoutLibrary() {
        credentials.clearLibraryCredentials()
    }

    val isLibraryLoggedIn: Boolean get() = credentials.isLibraryTokenValid
    val libraryUsername: String? get() = credentials.libraryUsername
    val libraryTokenExpiry: Long get() = credentials.libraryTokenExpiry
    val ntustStudentId: String? get() = authService.storedStudentId
    val cookieExpiryMs: Long get() = appState.sessionManager.cookieExpiryMs
}
