package org.ntust.app.tigerduck.ui.screen.signin

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.auth.StoredPasswordLatch
import org.ntust.app.tigerduck.notification.BackgroundSyncWorker
import org.ntust.app.tigerduck.ui.AppState
import javax.inject.Inject

/**
 * The refusal the "Automatic sign-in failed" banner shows: [rejection] while [signedIn], unless it
 * is the one the user closed this run ([dismissed]).
 */
internal fun bannerRejection(
    rejection: StoredPasswordLatch.Rejection?,
    dismissed: StoredPasswordLatch.Rejection?,
    signedIn: Boolean,
): StoredPasswordLatch.Rejection? = rejection?.takeIf { signedIn && it !== dismissed }

/**
 * Whether [typed] may sign in from the "Sign in again" sheet: the [stored] account only, as
 * another student would inherit what it left on the device. Anyone with none stored.
 */
internal fun signsInAsStoredAccount(typed: String, stored: String?): Boolean =
    stored == null || typed.trim().uppercase() == stored.trim().uppercase()

/**
 * The "Sign in again" sheet's sign-in: [login] with what was typed, only as the [stored] account
 * ([signsInAsStoredAccount]), whatever reaches here. Says whether it signed in; another student
 * is never sent.
 */
internal suspend fun signInAsStoredAccount(
    studentId: String,
    password: String,
    stored: String?,
    login: suspend (studentId: String, password: String) -> Boolean,
): Boolean = signsInAsStoredAccount(studentId, stored) && login(studentId, password)

/**
 * Whether a link that opens another page closes the "Sign in again" sheet
 * ([NtustSignInSheetHost]), as on iOS: when it is [open], as a tap outside it would, and not while
 * its sign-in is under way, which holds it open until it ends, as it holds it against that tap.
 * With none open there is nothing to close, and the error of a sign-in elsewhere, such as
 * Settings' own, which shares the sheet's, stays.
 */
internal fun signInSheetClosesForLink(open: Boolean, signingIn: Boolean): Boolean = open && !signingIn

/**
 * Whether the TigerSync status asks the user to sign in again, in place of the latest error: sync
 * is on, the school account is signed in with credentials to read, the device has no TigerSync
 * session, and the last sign-in that had something to send failed, so none came. Not while
 * another is under way, which may yet succeed ([AuthTokenManager.lastSignInFailed] reads false
 * then). Mirrors iOS's `AuthService.asksToSignInAgain`.
 */
internal fun asksToSignInAgain(
    syncEnabled: Boolean,
    credentialsReadable: Boolean,
    hasBackendSession: Boolean,
    lastSignInFailed: Boolean,
): Boolean = syncEnabled && credentialsReadable && !hasBackendSession && lastSignInFailed

/**
 * "Sign in again" after the automatic sign-in failed: the banner on Home, the class table and
 * Score, the TigerSync status row, and the school sign-in sheet they open over any page
 * ([NtustSignInSheetHost]). The sheet signs in as the stored account; the backend sign-in that
 * follows it signs in to TigerSync too.
 */
@HiltViewModel
class NtustSignInViewModel @Inject constructor(
    private val appState: AppState,
    private val authService: AuthService,
    private val authTokenManager: AuthTokenManager,
    passwordLatch: StoredPasswordLatch,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    val rejection: StateFlow<StoredPasswordLatch.Rejection?> = passwordLatch.rejection
    val signedIn: StateFlow<Boolean> = authService.authState
    val lastSignInFailed: StateFlow<Boolean> = authTokenManager.lastSignInFailed
    val isLoggingIn: StateFlow<Boolean> = authService.isLoggingIn
    val loginError: StateFlow<String?> = authService.loginError

    val storedStudentId: String? get() = authService.storedStudentId

    /** Snapshot state, so a composable reading it follows the request. */
    val sheetRequested: Boolean get() = appState.ntustSignInSheetRequested

    /** Snapshot state, so a composable reading it follows a close on another page. */
    val dismissedRejection: StoredPasswordLatch.Rejection?
        get() = appState.dismissedPasswordRejection

    /** [asksToSignInAgain] for this device now, after [lastSignInFailed]. */
    fun asksToSignInAgain(syncEnabled: Boolean, lastSignInFailed: Boolean): Boolean =
        asksToSignInAgain(
            syncEnabled = syncEnabled,
            credentialsReadable = authService.storedStudentId != null &&
                authService.storedPassword != null,
            hasBackendSession = authTokenManager.isLoggedIn,
            lastSignInFailed = lastSignInFailed,
    )

    fun requestSignIn() {
        // An error left from an earlier sign-in is not this one's.
        authService.clearLoginError()
        appState.ntustSignInSheetRequested = true
    }

    fun dismissSheet() {
        appState.ntustSignInSheetRequested = false
        authService.clearLoginError()
    }

    /** A link opens another page; see [signInSheetClosesForLink]. */
    fun closeForLink() {
        if (signInSheetClosesForLink(open = sheetRequested, signingIn = isLoggingIn.value)) dismissSheet()
    }

    fun dismissBanner(rejection: StoredPasswordLatch.Rejection) {
        appState.dismissedPasswordRejection = rejection
    }

    fun signIn(studentId: String, password: String) {
        // The sheet fixes the student ID; this keeps it so whatever reaches here.
        val stored = authService.storedStudentId
        viewModelScope.launch {
            if (signInAsStoredAccount(studentId, password, stored, authService::login)) {
                // As Settings does after a sign-in, in case it had stopped.
                BackgroundSyncWorker.schedule(context)
                appState.ntustSignInSheetRequested = false
            }
        }
    }
}
