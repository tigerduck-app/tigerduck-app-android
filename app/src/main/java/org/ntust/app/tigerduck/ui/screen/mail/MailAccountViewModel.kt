package org.ntust.app.tigerduck.ui.screen.mail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.mail.MailAccount
import org.ntust.app.tigerduck.mail.MailError
import javax.inject.Inject

/** The mail sign-in, shared by the Settings row and the mail page's login card. */
@HiltViewModel
class MailAccountViewModel @Inject constructor(private val account: MailAccount) : ViewModel() {
    val signedIn: StateFlow<Boolean> = account.signedIn
    val authFailed: StateFlow<Boolean> = account.authFailed

    private val _signingIn = MutableStateFlow(false)
    val signingIn: StateFlow<Boolean> = _signingIn.asStateFlow()

    private val _error = MutableStateFlow<MailError?>(null)
    val error: StateFlow<MailError?> = _error.asStateFlow()

    val studentId: String? get() = account.studentId?.uppercase()

    fun signIn(studentId: String, password: String) {
        if (_signingIn.value) return
        _signingIn.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                _error.value = account.signIn(studentId, password)
            } finally {
                _signingIn.value = false
            }
        }
    }

    fun signOut() = account.signOut()

    fun clearError() {
        _error.value = null
    }
}
