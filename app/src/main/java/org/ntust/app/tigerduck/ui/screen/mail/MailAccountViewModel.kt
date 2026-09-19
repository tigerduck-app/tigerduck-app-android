package org.ntust.app.tigerduck.ui.screen.mail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.mail.MailAccount
import org.ntust.app.tigerduck.mail.MailDevServerSettings
import org.ntust.app.tigerduck.mail.MailError
import org.ntust.app.tigerduck.mail.MailSite
import javax.inject.Inject

/** The mail sign-in, shared by the Settings row and the mail page's login card. */
@HiltViewModel
class MailAccountViewModel @Inject constructor(
    private val account: MailAccount,
    private val site: MailSite,
) : ViewModel() {
    val signedIn: StateFlow<Boolean> = account.signedIn
    val authFailed: StateFlow<Boolean> = account.authFailed

    private val _signingIn = MutableStateFlow(false)
    val signingIn: StateFlow<Boolean> = _signingIn.asStateFlow()

    private val _error = MutableStateFlow<MailError?>(null)
    val error: StateFlow<MailError?> = _error.asStateFlow()

    /**
     * The debug-only mail-server override in force, or null -- always null in a release
     * build. The mail page shows it so a test mailbox cannot be mistaken for the school
     * inbox, and the sign-in field stops uppercasing while it is on.
     */
    val devServer: MailDevServerSettings? get() = site.activeOverride()

    /** Uppercased only for the school account, where the login name is the uppercased student ID. */
    val studentId: String? get() = account.studentId?.let { if (devServer == null) it.uppercase() else it }

    /**
     * What an address on the overridden server starts from -- `@example.test` -- or null
     * against the school, which is also always the answer in a release build.
     *
     * The sign-in field is labelled "student ID" because against the school that is exactly
     * what it wants: the app knows the domain and supplies it. Under the override the app
     * knows the domain too -- it was typed on the Developer -> Email screen -- but the server
     * wants the whole address, and a bare local part is simply rejected, which arrives on
     * screen as "Wrong student ID or password". So there the field says which domain it is
     * about and starts the value there, leaving the local part to type in front of it.
     */
    val signInAddressSuffix: String? get() = devServer?.toConfig()?.domain?.let { "@$it" }

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
