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

/**
 * The stored NTUST account, as the mail sign-in reads it.
 *
 * An interface rather than `AuthService` itself, so the mail sign-in can do exactly two
 * things with that account -- read the ID, read the password -- and so the prefill rules
 * below are testable without the whole auth graph. `MailModule.ntustAccount` is where it
 * comes from, and it reads `AuthService.storedStudentId` / `storedPassword`, the accessors
 * the rest of the app uses, rather than reaching into the credential store directly.
 */
interface NtustAccountSource {
    val studentId: String?
    val password: String?
}

/**
 * What the two sign-in fields start from.
 *
 * [toString] hides the password the way [org.ntust.app.tigerduck.mail.MailCredentials] and
 * [org.ntust.app.tigerduck.mail.MailProbeCredentials] do: nothing about a mail password may
 * reach a log line or a crash report, not even by accident.
 */
class MailSignInPrefill(val username: String, val password: String) {
    override fun toString(): String = "MailSignInPrefill(username=$username, password=***)"
}

/** The mail sign-in, shared by the Settings row and the mail page's login card. */
@HiltViewModel
class MailAccountViewModel @Inject constructor(
    private val account: MailAccount,
    private val site: MailSite,
    private val ntust: NtustAccountSource,
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

    /**
     * What the three ways into this sign-in start their two fields from.
     *
     * Spec §7.1 keeps the mail account deliberately separate from the NTUST one -- a
     * Mail2000 password is set in webmail and need not match the SSO one -- and that is
     * still true: nothing here couples the two accounts, saves one as the other, or assumes
     * they match. The user asked for the stored NTUST ID and password to be offered here
     * anyway, having been told exactly that, and attached one condition: **nothing is ever
     * submitted for them**. That condition is the whole safety argument and is not
     * negotiable. A *manual* sign-in the server rejects costs one error message; §7.4's
     * stop-everything lockout is about a *saved* password failing in the background, where
     * repeated tries can lock the school account and its Wi-Fi with it. Filling the fields
     * and stopping keeps that property intact; signing in on the user's behalf would spend
     * it. So this is a read, and only the button signs in.
     *
     * Two more limits, both load-bearing:
     *  - Nothing from the NTUST account at all while the debug mail-server override is on.
     *    That password is the school's, and it must not be typed into a form pointed at a
     *    third-party server. It is the rule
     *    [org.ntust.app.tigerduck.mail.SocketMailConnectionProbe] already applies to itself:
     *    a stored password is offered only to the host it was typed for.
     *  - The password is only ever offered next to the ID it belongs to, so it can never be
     *    paired with some other account's login name.
     *
     * The fields themselves fill only while empty, so a correction already typed is never
     * replaced -- see [org.ntust.app.tigerduck.ui.screen.settings.signInFieldValue].
     */
    val signInPrefill: MailSignInPrefill
        get() {
            // Under the override: exactly what was offered before any of this existed --
            // the mail account's own saved ID, else the configured domain to type a local
            // part in front of. Never the school's ID or password.
            if (devServer != null) {
                return MailSignInPrefill(studentId.orEmpty().ifEmpty { signInAddressSuffix.orEmpty() }, "")
            }
            val ntustId = ntust.studentId?.trim()?.uppercase().orEmpty()
            // The mail account's own saved ID wins, whoever it belongs to: re-auth is about
            // that account, and the NTUST one may name a different person entirely.
            val username = studentId.orEmpty().ifEmpty { ntustId }
            val password = if (ntustId.isNotEmpty() && username.equals(ntustId, ignoreCase = true)) {
                ntust.password.orEmpty()
            } else {
                ""
            }
            return MailSignInPrefill(username, password)
        }

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
