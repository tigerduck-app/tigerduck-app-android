package com.tigerduck.app.auth

import com.tigerduck.app.data.preferences.CredentialManager
import com.tigerduck.app.network.LibraryService
import com.tigerduck.app.network.NtustSessionManager
import com.tigerduck.app.network.SsoLoginService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthService @Inject constructor(
    private val sessionManager: NtustSessionManager,
    private val ssoLoginService: SsoLoginService,
    private val libraryService: LibraryService,
    private val credentials: CredentialManager
) {
    private val _isLoggingIn = MutableStateFlow(false)
    val isLoggingIn: StateFlow<Boolean> = _isLoggingIn

    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError

    val isNtustAuthenticated: Boolean
        get() = sessionManager.cookiesValid && credentials.ntustStudentId != null

    val storedStudentId: String? get() = credentials.ntustStudentId
    val storedPassword: String? get() = credentials.ntustPassword

    suspend fun login(studentId: String, password: String): Boolean {
        _isLoggingIn.value = true
        _loginError.value = null

        return try {
            val normalizedId = studentId.trim().uppercase()
            val serviceUrl = "https://courseselection.ntust.edu.tw/"

            val success = ssoLoginService.ensureServiceLogin(serviceUrl, normalizedId, password)

            if (success) {
                credentials.ntustStudentId = normalizedId
                credentials.ntustPassword = password

                // Auto-attempt library login (best-effort)
                if (!credentials.isLibraryTokenValid) {
                    try {
                        libraryService.login(normalizedId, password)
                    } catch (e: Exception) {
                        // Ignore — library credentials may differ
                    }
                }
            }

            _isLoggingIn.value = false
            success
        } catch (e: Exception) {
            _loginError.value = e.message ?: "登入失敗"
            _isLoggingIn.value = false
            false
        }
    }

    suspend fun ensureAuthenticated(): Boolean {
        val studentId = credentials.ntustStudentId ?: return false
        val password = credentials.ntustPassword ?: return false

        if (sessionManager.cookiesValid) return true

        return login(studentId, password)
    }

    fun logout() {
        credentials.clearNtustCredentials()
        sessionManager.invalidateSession()
        _loginError.value = null
    }
}
