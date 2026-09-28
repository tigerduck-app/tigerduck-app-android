package org.ntust.app.tigerduck.ui.screen.informationsystem

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import org.ntust.app.tigerduck.auth.AuthService
import javax.inject.Inject

/**
 * Exists only so [PortalBrowserScreen] can read the signed-in credentials for the SSO
 * auto-fill script without threading them through navigation arguments. A `NavHost` route
 * argument round-trips through `SavedStateHandle`, which Android can persist to disk across
 * process death for state restoration — fine for a link's public title/URL, not for a password.
 * Reading straight from [AuthService] here keeps the credentials off that path entirely.
 */
@HiltViewModel
class PortalBrowserViewModel @Inject constructor(
    private val authService: AuthService,
) : ViewModel() {
    val studentId: String? get() = authService.storedStudentId
    val password: String? get() = authService.storedPassword
}
