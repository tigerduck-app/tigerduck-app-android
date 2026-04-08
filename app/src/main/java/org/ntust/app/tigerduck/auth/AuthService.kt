package org.ntust.app.tigerduck.auth

import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.network.LibraryService
import org.ntust.app.tigerduck.network.NtustSessionManager
import org.ntust.app.tigerduck.network.SsoLoginError
import org.ntust.app.tigerduck.network.SsoLoginService
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val loginMutex = Mutex()

    val isNtustAuthenticated: Boolean
        get() = sessionManager.cookiesValid && credentials.ntustStudentId != null

    val storedStudentId: String? get() = credentials.ntustStudentId
    internal val storedPassword: String? get() = credentials.ntustPassword

    suspend fun login(studentId: String, password: String): Boolean = loginMutex.withLock {
        _isLoggingIn.value = true
        _loginError.value = null

        try {
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
        } catch (e: CancellationException) {
            _isLoggingIn.value = false
            throw e
        } catch (e: Exception) {
            _loginError.value = if (e is SsoLoginError.NetworkError) {
                "無法連線，請檢查網路連線"
            } else {
                e.message ?: "登入失敗"
            }
            _isLoggingIn.value = false
            false
        }
    }

    suspend fun ensureAuthenticated(): Boolean = loginMutex.withLock {
        val studentId = credentials.ntustStudentId ?: return@withLock false
        val password = credentials.ntustPassword ?: return@withLock false

        if (sessionManager.cookiesValid) return@withLock true

        try {
            val normalizedId = studentId.trim().uppercase()
            val serviceUrl = "https://courseselection.ntust.edu.tw/"
            val success = ssoLoginService.ensureServiceLogin(serviceUrl, normalizedId, password)

            if (success) {
                if (!credentials.isLibraryTokenValid) {
                    try {
                        libraryService.login(normalizedId, password)
                    } catch (_: Exception) { }
                }
            }

            success
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    fun logout() {
        credentials.clearNtustCredentials()
        credentials.clearLibraryCredentials()
        sessionManager.invalidateSession()
        _loginError.value = null
    }
}
