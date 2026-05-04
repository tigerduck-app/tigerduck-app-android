package org.ntust.app.tigerduck.auth

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.network.LibraryService
import org.ntust.app.tigerduck.network.NtustSessionManager
import org.ntust.app.tigerduck.network.SsoLoginError
import org.ntust.app.tigerduck.network.SsoLoginService
import org.ntust.app.tigerduck.push.PushRegistrationService
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class AuthService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sessionManager: NtustSessionManager,
    private val ssoLoginService: SsoLoginService,
    private val libraryService: LibraryService,
    private val credentials: CredentialManager,
    private val pushRegistration: PushRegistrationService,
) {
    private val _isLoggingIn = MutableStateFlow(false)
    val isLoggingIn: StateFlow<Boolean> = _isLoggingIn

    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError

    /**
     * Observable NTUST auth state. Screens and view-models collect this so
     * they can reactively clear or reload when the user logs in or out —
     * [isNtustAuthenticated] is a snapshot, this is the live signal.
     */
    private val _authState = MutableStateFlow(credentials.ntustStudentId != null)
    val authState: StateFlow<Boolean> = _authState

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
            val success = performSsoLoginUnlocked(normalizedId, password)

            if (success) {
                credentials.ntustStudentId = normalizedId
                credentials.ntustPassword = password
                _authState.value = true
                runCatching { pushRegistration.onSignedIn(normalizedId) }
                    .onFailure { e -> if (e is CancellationException) throw e }
            }

            _isLoggingIn.value = false
            success
        } catch (e: CancellationException) {
            _isLoggingIn.value = false
            throw e
        } catch (e: Exception) {
            _loginError.value = if (e is SsoLoginError.NetworkError) {
                context.getString(R.string.error_network_unavailable)
            } else {
                e.message ?: context.getString(R.string.error_login_failed)
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
            performSsoLoginUnlocked(studentId.trim().uppercase(), password)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Shared SSO + library login work. Caller MUST already hold [loginMutex] —
     * Kotlin `Mutex` is non-reentrant, so the public entry points each acquire
     * the lock once and delegate here, avoiding the deadlock that would happen
     * if one path called the other.
     */
    private suspend fun performSsoLoginUnlocked(
        normalizedId: String,
        password: String,
    ): Boolean {
        val serviceUrl = "https://courseselection.ntust.edu.tw/"
        val success = ssoLoginService.ensureServiceLogin(serviceUrl, normalizedId, password)
        if (success && !credentials.isLibraryTokenValid) {
            // Best-effort: library credentials may differ from NTUST SSO.
            try {
                libraryService.login(normalizedId, password)
            } catch (_: Exception) {
            }
        }
        return success
    }

    fun logout() {
        credentials.clearNtustCredentials()
        credentials.clearLibraryCredentials()
        sessionManager.invalidateSession()
        _loginError.value = null
        _authState.value = false
        pushRegistration.unregister()
    }

    fun clearLoginError() {
        _loginError.value = null
    }
}
