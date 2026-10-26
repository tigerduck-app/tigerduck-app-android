package org.ntust.app.tigerduck.ui.screen.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.ntust.app.tigerduck.library.LibraryQRCache

/**
 * Whether the Library page shows the pass or the sign-in form, the pass's
 * code cycle, and the failure the page says. Apart from [LibraryViewModel] so
 * a test can run them together.
 */
internal class LibraryPassState<Image : Any>(
    scope: CoroutineScope,
    /** Whether the page has an account to show the pass for; see [LibraryPageAccount.hasAccount]. */
    private val hasAccount: () -> Boolean,
    /** The account a code belongs to; see [LibraryPageAccount.codeAccount]. */
    codeAccount: () -> Int,
    fetch: suspend () -> String,
    render: suspend (String) -> Image,
    /** The library sign-in state Settings and the flip follow. */
    signedIn: Flow<Boolean>,
    /** Whether a sign-in typed on the page is under way; it shows the pass itself ([signedIn]). */
    signingIn: Flow<Boolean> = flowOf(false),
    /** Reads that state again. */
    private val refreshSession: () -> Unit,
    describe: (Exception) -> String,
    cache: LibraryQRCache<Image>,
    now: () -> Long,
    /** A new code has landed; see [LibraryQRCycle]. */
    onFetched: () -> Unit = {},
    /** When the last code landed, epoch ms, kept across restarts; see [org.ntust.app.tigerduck.data.preferences.AppPreferences.librarySyncedAtMs]. */
    fetchedAt: StateFlow<Long>,
) {
    /** What failed, for the page to say; a sign-in on the page writes it too. */
    val errors = MutableStateFlow<String?>(null)

    private val _isLoggedIn = MutableStateFlow(hasAccount())

    /** Whether the page shows the pass rather than the sign-in form. */
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    // Between a resume and a pause: the page is on screen.
    private var showing = false

    private val cycle = LibraryQRCycle(
        scope = scope,
        fetch = fetch,
        render = render,
        account = codeAccount,
        sessionValid = hasAccount,
        // The account is gone (refused, or signed out): back to the sign-in
        // form instead of looping on it. Before the session is read again,
        // so the drop that follows it keeps this failure's message.
        onSessionEnded = {
            _isLoggedIn.value = false
            refreshSession()
        },
        describe = describe,
        errors = errors,
        cache = cache,
        now = now,
        onFetched = onFetched,
    )

    val image: StateFlow<Image?> = cycle.image
    val countdown: StateFlow<Int> = cycle.countdown
    val isLoading: StateFlow<Boolean> = cycle.isLoading

    /** Whether the error banner offers Retry: the wait after a failed code request. */
    val waitingToRetry: StateFlow<Boolean> = cycle.waitingToRetry

    /** The banner's Retry; see [LibraryQRCycle.retryNow]. */
    fun retryNow() = cycle.retryNow()

    /**
     * When the code on screen was fetched, for the dot's "Last synced" row: [fetchedAt] while a
     * code is up, 0 while none is -- before the first lands, after an expired one has gone, or
     * signed out -- as on the iPhone. The kept date alone would say the page was synced minutes
     * ago while it shows no pass at all; a date belongs to the code it dates.
     */
    val syncedAt: StateFlow<Long> = combine(cycle.image, fetchedAt) { image, at -> if (image == null) 0L else at }
        .stateIn(scope, SharingStarted.Eagerly, if (cycle.image.value == null) 0L else fetchedAt.value)

    init {
        // Signed out elsewhere, such as a refusal the sign-in at a return to
        // the app found: back to the sign-in form at once, also while a code
        // request backs off, rather than at its next try. That failure's
        // message is no longer what happened. A code request still out is
        // waited for first, for a while: one whose sign-in the library
        // refused ends the session itself and says so, and stopping it would
        // lose that; one that hangs is stopped.
        scope.followSignOuts(signedIn, hasAccount) {
            withTimeoutOrNull(SIGN_OUT_WAIT_MS) { cycle.fetching.first { !it } }
            if (_isLoggedIn.value && !hasAccount()) {
                cycle.clear()
                errors.value = null
                _isLoggedIn.value = false
            }
        }
        // Signed in elsewhere, as the phone's retry does once the library takes
        // a stored password it had refused: a page left on the sign-in form
        // shows the pass, as on the iPhone. One away from the screen does when
        // it comes back ([resume]), and one typing a sign-in once that ends:
        // it shows the pass itself when it lands.
        scope.launch {
            combine(signedIn, signingIn) { signedIn, signingIn -> signedIn && !signingIn }.collect {
                if (it && showing && !_isLoggedIn.value && hasAccount()) {
                    errors.value = null
                    _isLoggedIn.value = true
                    cycle.start()
                }
            }
        }
    }

    /** The page opens: the pass and its code while there is an account. */
    fun load() {
        _isLoggedIn.value = hasAccount()
        if (_isLoggedIn.value) cycle.start()
    }

    /**
     * The page comes back: the pause stopped the cycle, and a sign-in or a
     * sign-out may have come from another screen meanwhile.
     */
    fun resume() {
        showing = true
        _isLoggedIn.value = hasAccount()
        if (_isLoggedIn.value) {
            cycle.start()
        } else {
            // Signed out elsewhere: the code on screen was that account's.
            cycle.clear()
        }
    }

    fun pause() {
        showing = false
        cycle.stop()
    }

    /** A sign-in on the page landed: the pass, with a new code. */
    fun signedIn() {
        _isLoggedIn.value = true
        cycle.restart()
    }

    companion object {
        /**
         * How long a sign-out elsewhere waits for a code request still out.
         * The library client has no overall timeout, so a hanging request
         * could hold the pass up for its connect and read timeouts.
         */
        const val SIGN_OUT_WAIT_MS = 15_000L
    }
}
