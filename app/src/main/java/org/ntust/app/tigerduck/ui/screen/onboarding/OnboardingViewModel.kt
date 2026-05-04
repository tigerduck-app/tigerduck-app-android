package org.ntust.app.tigerduck.ui.screen.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.ui.AppState
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val appState: AppState,
    private val authService: AuthService,
) : ViewModel() {

    val isLoggingIn = authService.isLoggingIn
    val loginError = authService.loginError
    val systemPermissions = appState.systemPermissions

    fun login(studentId: String, password: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            val success = authService.login(studentId, password)
            if (success) onSuccess()
        }
    }

    fun completeOnboarding() {
        appState.completeOnboarding()
    }
}
