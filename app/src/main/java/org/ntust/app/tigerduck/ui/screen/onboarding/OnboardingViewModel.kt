package org.ntust.app.tigerduck.ui.screen.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.analytics.AnalyticsLogger
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.ui.AppState
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val appState: AppState,
    private val authService: AuthService,
    private val analyticsLogger: AnalyticsLogger,
    val prefs: AppPreferences,
) : ViewModel() {

    val isLoggingIn = authService.isLoggingIn
    val loginError = authService.loginError
    val isSignedIn = authService.authState

    /**
     * The stored account, for the sign-in page's already-signed-in state.
     * A plain read: it cannot change while the wizard is up without a sign-in
     * that recomposes the page anyway.
     */
    val signedInStudentId: String?
        get() = authService.storedStudentId
    val systemPermissions = appState.systemPermissions

    fun login(studentId: String, password: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            val success = authService.login(studentId, password)
            if (success) onSuccess()
        }
    }

    fun setAnalyticsEnabled(enabled: Boolean) {
        prefs.analyticsEnabled = enabled
        analyticsLogger.setEnabled(enabled)
    }

    fun setSyncEnabled(enabled: Boolean) {
        // Through AppState, not straight to prefs: it holds the observable
        // copy the settings screen and the revision poll read. Writing the
        // preference alone left them on the value from process start, which
        // an upgrade re-run makes visible — the user turns TigerSync off in
        // the wizard and lands on a settings screen that still says on.
        appState.cloudSyncEnabled = enabled
    }

    fun completeOnboarding() {
        appState.completeOnboarding()
    }
}
