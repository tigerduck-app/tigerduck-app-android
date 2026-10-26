package org.ntust.app.tigerduck.wear.data

import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.shared.LibraryApi
import org.ntust.app.tigerduck.shared.LibraryQRRenderer
import org.ntust.app.tigerduck.shared.LibraryServiceError
import org.ntust.app.tigerduck.shared.sameStudentId
import kotlin.coroutines.CoroutineContext
import org.ntust.app.tigerduck.shared.tls.pinMismatch

/**
 * Drives the watch's library QR page: fetches a fresh code from
 * `api.lib.ntust.edu.tw`, renders the bitmap off-main, and ticks a countdown
 * until it auto-refreshes. Mirrors the phone's [LibraryViewModel] behaviour but
 * lives outside the Compose layer so it can be reused by Tile/Complication
 * later if we want a watch-face shortcut.
 */
class LibraryQRController(
    /** The code for the account stored now: `LibraryService.generateQRCode`. */
    private val generate: suspend () -> String,
    private val scope: CoroutineScope,
    /** Shown for a code whose account signed out or changed on the phone while it was fetched. */
    private val accountChangedMessage: String,
    private val renderContext: CoroutineContext = Dispatchers.Default,
) {
    private val _qrBitmap = MutableStateFlow<Bitmap?>(null)
    val qrBitmap: StateFlow<Bitmap?> = _qrBitmap.asStateFlow()

    /**
     * Bounding box of the actual QR module pattern within [qrBitmap],
     * excluding the embedded quiet zone and zxing's floor-rounding leftover
     * white border. The fullscreen view draws only this sub-rect at the
     * brackets-indicated size so what the user sees lines up with the
     * QR-padding settings preview.
     */
    private val _qrPatternBounds = MutableStateFlow<Rect?>(null)
    val qrPatternBounds: StateFlow<Rect?> = _qrPatternBounds.asStateFlow()

    private val _countdown = MutableStateFlow(0)
    val countdown: StateFlow<Int> = _countdown.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // The fetch, the countdown and the next fetch, all in one job, so cancelling
    // it stops each of them.
    private var refreshJob: Job? = null

    // The account the bitmap on screen is a code of.
    private var shownAccount: String? = null

    /**
     * Start (or restart) the fetch + countdown cycle, and start it afresh each
     * time [accounts] names another account: a code fetched as the phone handed
     * over an account is dropped, and its error would otherwise stand until the
     * page is opened again.
     */
    fun start(qrSidePx: Int, accounts: Flow<String?>) {
        val previous = refreshJob
        refreshJob = scope.launch {
            // Joined, not only cancelled: its render may still be finishing, and
            // its cleanup would mark this one's fetch as done.
            previous?.cancelAndJoin()
            accounts.distinctUntilChanged()
                // Another account's code is not this one's. Taken off as the account changes,
                // before collectLatest waits for the previous account's cycle to end, which a
                // fetch still running can hold up.
                .onEach { account -> if (!sameStudentId(account, shownAccount)) clearCode() }
                // collectLatest cancels and joins the previous account's cycle the same way.
                .collectLatest { account ->
                    if (!sameStudentId(account, shownAccount)) clearCode()
                    // Until a fetch fails, as before: the page is opened again for another try.
                    while (refresh(qrSidePx, account)) countDown()
                }
        }
    }

    private fun clearCode() {
        shownAccount = null
        _qrBitmap.value = null
        _qrPatternBounds.value = null
        _countdown.value = 0
    }

    /** Pause refreshes when the page leaves the screen. */
    fun stop() {
        refreshJob?.cancel()
    }

    /** Drop the cached bitmap (e.g. after a logout push from phone). */
    fun reset() {
        stop()
        clearCode()
        _error.value = null
    }

    /** Fetches and renders a code for [account], and says whether it did. */
    private suspend fun refresh(qrSidePx: Int, account: String?): Boolean {
        _isLoading.value = _qrBitmap.value == null
        _error.value = null
        try {
            val qrData = generate()
            val rendered = withContext(renderContext) {
                val bmp = LibraryQRRenderer.render(qrData, qrSidePx)
                bmp to LibraryQRRenderer.patternBounds(bmp)
            }
            _qrBitmap.value = rendered.first
            _qrPatternBounds.value = rendered.second
            shownAccount = account
            return true
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            // Never swallow cancellation: doing so breaks structured
            // concurrency (the scope thinks the job completed normally).
            throw e
        } catch (e: Exception) {
            // Surface the real reason (network failure, server message, etc.)
            // rather than a generic string — diagnosing a watch-only failure
            // from logcat alone is painful and the screen has room for it.
            android.util.Log.w("LibraryQRController", "QR refresh failed: ${e.javaClass.simpleName}")
            // A pin mismatch is the phone's to settle (TLS pins spec, A8): the watch has no prompt.
            // So is a password the library refused, which reads as no credentials while its wait
            // holds: the phone hands over a live token, or takes the password away and asks for
            // the right one, which ends the wait. Waiting it out sends the same password again.
            _error.value = if (e is LibraryServiceError.CredentialsNotFound || e.pinMismatch() != null) {
                accountChangedMessage
            } else {
                qrErrorDetail(e)
            }
            _qrBitmap.value = null
            _qrPatternBounds.value = null
            return false
        } finally {
            _isLoading.value = false
        }
    }

    private suspend fun countDown() {
        _countdown.value = LibraryApi.QR_VALID_SECONDS
        while (_countdown.value > 0) {
            delay(1000)
            _countdown.value--
        }
    }
}

/**
 * The reason under the page's generic "Failed to generate QR code": the
 * failure's own message (a network error, the library server's reply), or
 * nothing. Never the exception's class name, which a release build shortens.
 */
internal fun qrErrorDetail(e: Throwable): String =
    e.message?.takeUnless { it.isBlank() }.orEmpty()
