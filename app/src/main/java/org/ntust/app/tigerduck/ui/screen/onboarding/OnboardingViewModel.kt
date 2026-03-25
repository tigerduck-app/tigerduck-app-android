package org.ntust.app.tigerduck.ui.screen.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.ui.AppState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val appState: AppState,
    private val authService: AuthService
) : ViewModel() {

    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage

    val isLoggingIn = authService.isLoggingIn
    val loginError = authService.loginError

    fun nextPage() {
        _currentPage.value = (_currentPage.value + 1).coerceAtMost(3)
    }

    fun goToPage(page: Int) {
        _currentPage.value = page
    }

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
