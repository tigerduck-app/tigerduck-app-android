package org.ntust.app.tigerduck.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.auth.session.AccountSessions
import org.ntust.app.tigerduck.library.LibrarySession
import org.ntust.app.tigerduck.mail.MailAccount
import org.ntust.app.tigerduck.ui.AppState
import javax.inject.Inject

/** Who is signed in, as Settings' rows are gated by it. */
data class SignedInAccounts(val school: Boolean, val mail: Boolean, val library: Boolean)

/**
 * What Settings' gates follow: each account's sign-ins and sign-outs, live, so an open page dims a
 * row, or closes, as its account leaves. The school's and School Mail's come from their sessions'
 * [org.ntust.app.tigerduck.auth.session.AccountSession.changes], a page's way to follow an
 * account. The library's session does not follow its state yet (a lapsed token moves it unsaid),
 * so the library's is [LibrarySession.signedIn], which Settings' library row reads too. Each starts
 * from the state as it stands, so a page never opens on a wrong first frame and closes.
 */
@HiltViewModel
class SettingsGatesViewModel @Inject constructor(
    sessions: AccountSessions,
    auth: AuthService,
    mail: MailAccount,
    library: LibrarySession,
    val appState: AppState,
) : ViewModel() {
    val accounts: StateFlow<SignedInAccounts> =
        combine(sessions.school.changes, sessions.mail.changes, library.signedIn, ::SignedInAccounts)
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                SignedInAccounts(auth.authState.value, mail.signedIn.value, library.signedIn.value),
            )
}
