package org.ntust.app.tigerduck.ui.screen.library

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.debug.DebugFixtureStore
import org.ntust.app.tigerduck.demo.DemoAccount
import org.ntust.app.tigerduck.library.LibraryPagePresence
import org.ntust.app.tigerduck.library.LibraryQRBitmapCache
import org.ntust.app.tigerduck.library.LibrarySession
import org.ntust.app.tigerduck.library.libraryErrorMessage
import org.ntust.app.tigerduck.shared.LibraryQRRenderer
import org.ntust.app.tigerduck.shared.LibraryService
import org.ntust.app.tigerduck.wear.WearScheduleBridge
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryService: LibraryService,
    private val credentials: CredentialManager,
    private val wearBridge: WearScheduleBridge,
    private val debugFixtures: DebugFixtureStore,
    private val demoAccount: DemoAccount,
    private val librarySession: LibrarySession,
    private val pagePresence: LibraryPagePresence,
    qrCache: LibraryQRBitmapCache,
    private val prefs: AppPreferences,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    private val _isLoggingIn = MutableStateFlow(false)
    val isLoggingIn: StateFlow<Boolean> = _isLoggingIn

    private val page = LibraryPageAccount(
        credentials = credentials,
        libraryService = libraryService,
        demoCode = { fixtureQr },
        demoSignedIn = { fixtureSignedIn },
    )

    private val pass = LibraryPassState(
        scope = viewModelScope,
        hasAccount = { page.hasAccount },
        codeAccount = { page.codeAccount },
        fetch = page::fetchCode,
        // QR rendering scans 512*512 pixels — keep it off the main thread so
        // the countdown animation and any ongoing gestures don't stutter.
        render = { payload -> withContext(Dispatchers.Default) { generateQRBitmap(payload) } },
        signedIn = librarySession.signedIn,
        signingIn = _isLoggingIn,
        refreshSession = { librarySession.refresh() },
        describe = { e -> libraryErrorMessage(e, R.string.library_qr_generate_failed, context::getString) },
        cache = qrCache,
        now = SystemClock::elapsedRealtime,
        // The demo library's code comes from the device, not the library.
        onFetched = { if (fixtureQr == null) prefs.markLibrarySynced(System.currentTimeMillis()) },
        fetchedAt = prefs.librarySyncedAtMs,
    )

    private val _errorMessage = pass.errors
    val errorMessage: StateFlow<String?> = pass.errors

    val qrBitmap: StateFlow<Bitmap?> = pass.image
    val countdown: StateFlow<Int> = pass.countdown
    val isLoadingQR: StateFlow<Boolean> = pass.isLoading
    val isLoggedIn: StateFlow<Boolean> = pass.isLoggedIn

    /** Whether the error banner offers Retry; see [LibraryPassState.waitingToRetry]. */
    val isWaitingToRetry: StateFlow<Boolean> = pass.waitingToRetry

    /** For the sync dot's "Last synced" row; see [LibraryPassState.syncedAt]. */
    val syncedAtMs: StateFlow<Long> = pass.syncedAt

    /**
     * The demo account's QR payload, or null to ask the backend for a real
     * one. See [DebugFixtureStore].
     */
    private val fixtureQr: String?
        get() = debugFixtures.libraryQrContent

    /**
     * Whether to render the screen as signed in on the strength of the
     * override alone. Without this the sign-in form shows and the demo QR
     * never appears unless a real library account happens to be signed in on
     * the device.
     */
    private val fixtureSignedIn: Boolean
        get() = fixtureQr != null && debugFixtures.libraryFakeSignedIn

    private fun resolveUsername(): String? =
        demoAccount.libraryUsername ?: credentials.libraryUsername

    private val _storedUsername = MutableStateFlow(credentials.libraryUsername)
    val storedUsername: StateFlow<String?> = _storedUsername

    /** Suggested pre-fill for the login form — stored library user, or NTUST student ID. */
    val suggestedUsername: String
        get() = resolveUsername() ?: credentials.ntustStudentId.orEmpty()

    fun load() {
        _storedUsername.value = resolveUsername()
        pass.load()
    }

    fun loginAndRefresh(username: String, password: String) {
        viewModelScope.launch {
            _isLoggingIn.value = true
            _errorMessage.value = null
            try {
                // The demo library password signs in on the device, with or
                // without the demo account; see [DemoAccount.signInLibrary].
                if (demoAccount.signInLibrary(username, password)) {
                    librarySession.refresh()
                    _storedUsername.value = resolveUsername()
                    pass.signedIn()
                    return@launch
                }
                // The demo account itself never reaches the library, so
                // anything else fails there the way a wrong password would.
                if (demoAccount.isActive) {
                    _errorMessage.value = context.getString(R.string.error_sign_in_failed)
                    return@launch
                }
                libraryService.login(username, password, credentials.libraryAccountGeneration, byUser = true)
                demoAccount.signOutLibrary()
                librarySession.refresh()
                _storedUsername.value = username
                pass.signedIn()
                // Push the fresh credentials to the paired watch so its
                // library page can start showing the QR straight away
                // instead of waiting for the next launch-time mirror.
                wearBridge.publishLibraryCredentials()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errorMessage.value = libraryErrorMessage(e, R.string.error_sign_in_failed, context::getString)
            } finally {
                _isLoggingIn.value = false
            }
        }
    }

    fun onResume() {
        _storedUsername.value = resolveUsername()
        pass.resume()
    }

    fun onPause() = pass.pause()

    fun retryNow() = pass.retryNow()

    /**
     * Counts the page as shown while its back stack entry is resumed; the
     * flip-to-library offer waits until it is left. [recreating] says whether
     * a pause is one only to build the activity again, as when the dark theme
     * or the font size changes. This view model outlives that, and leaves the
     * count when it is cleared.
     */
    fun presenceObserver(recreating: () -> Boolean) = pagePresence.observer(this, recreating)

    override fun onCleared() {
        pagePresence.disappear(this)
    }

    private fun generateQRBitmap(content: String, size: Int = 512): Bitmap =
        LibraryQRRenderer.render(content, size)

    companion object {
        const val QR_VALID_SECONDS = 30
    }
}
