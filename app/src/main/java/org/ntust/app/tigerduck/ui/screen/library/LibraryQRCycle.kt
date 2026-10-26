package org.ntust.app.tigerduck.ui.screen.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.library.LibraryQRCache
import org.ntust.app.tigerduck.shared.LibraryApi

/**
 * The Library page's rotating QR code: fetches one, shows it with its
 * 30-second countdown and fetches the next when that runs out, for as long as
 * it runs ([start] on the page's resume, [stop] on its pause).
 *
 * A fetch that fails while the session is still valid (a server error, a
 * timeout, a short network drop) is tried again after 60, 120, then every 300
 * seconds, as on the iPhone, so a flapping server is not asked every 30
 * seconds and the page recovers on its own; the page's Retry asks sooner
 * ([retryNow]). An expired code is never left on screen to be scanned: it
 * goes as soon as a fetch fails. A failure with the session gone hands back
 * to [onSessionEnded].
 *
 * Each code goes into [cache]. Coming back to the page while the last code
 * still has [LibraryQRCache.REUSE_THRESHOLD_SECONDS] left shows that code at
 * once and resumes its countdown, rendering it again only if the image was
 * dropped; the next fetch is when it runs out.
 *
 * [Image] is the rendered code, a Bitmap in the app.
 */
internal class LibraryQRCycle<Image : Any>(
    private val scope: CoroutineScope,
    /** The library's answer: a new code's payload. */
    private val fetch: suspend () -> String,
    /** Renders a payload; the caller picks the thread. */
    private val render: suspend (String) -> Image,
    /** The library account a code is for; a code fetched for another is dropped. */
    private val account: () -> Int,
    /** Whether the session can still fetch codes; checked after a failure. */
    private val sessionValid: () -> Boolean,
    private val onSessionEnded: () -> Unit,
    private val describe: (Exception) -> String,
    /** Where a failure's message goes; cleared when a fetch starts. */
    private val errors: MutableStateFlow<String?>,
    private val cache: LibraryQRCache<Image>,
    /** A monotonic clock in ms, for how long the code on screen has left. */
    private val now: () -> Long,
    /** A new code has landed and is on screen. */
    private val onFetched: () -> Unit = {},
) {
    private val _image = MutableStateFlow<Image?>(null)
    val image: StateFlow<Image?> = _image.asStateFlow()

    private val _countdown = MutableStateFlow(0)
    val countdown: StateFlow<Int> = _countdown.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _fetching = MutableStateFlow(false)

    /**
     * Whether a code request is out. One that fails because its account has
     * gone ends the session itself, with what failed, such as the library's
     * refusal.
     */
    val fetching: StateFlow<Boolean> = _fetching.asStateFlow()

    private val _waitingToRetry = MutableStateFlow(false)

    /** Whether the cycle waits to ask again after a failed request, which [retryNow] cuts short. */
    val waitingToRetry: StateFlow<Boolean> = _waitingToRetry.asStateFlow()

    private var job: Job? = null

    /** Transient failures since the last code that landed; drives [retryDelaySeconds]. */
    private var consecutiveErrors = 0

    /** When the code on screen was issued, on [now]'s clock, and for which account. */
    private var shownIssuedAt: Long? = null
    private var shownAccount: Int? = null

    /** Starts the cycle, unless it is already running. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { cycle() }
    }

    fun stop() {
        job?.cancel()
        job = null
        // Not left to the cancelled wait, which may end only after a Retry has read it.
        _waitingToRetry.value = false
    }

    fun restart() {
        stop()
        start()
    }

    /**
     * Retry on the page's error banner: asks for a code at once during the wait after a failed
     * request, as on the iPhone, and starts the backoff over, so one that fails too waits its
     * first step again. Outside that wait it does nothing, so a request already out is never
     * sent twice and a stopped cycle stays stopped.
     */
    fun retryNow() {
        if (!_waitingToRetry.value) return
        consecutiveErrors = 0
        restart()
    }

    /** Stops, and takes the code off the screen: the account it belongs to has gone. */
    fun clear() {
        stop()
        clearCode()
        _isLoading.value = false
        consecutiveErrors = 0
    }

    private suspend fun cycle() {
        if (!showCached()) {
            // A code from before a pause that has run out, or that is another
            // account's, would scan as a valid pass until the new one lands.
            if (shownAccount != account() || codeExpired()) clearCode()
            _countdown.value = 0
        }
        while (true) {
            when (fetchOnce()) {
                Outcome.SHOWN -> countDown(LibraryApi.QR_VALID_SECONDS)
                Outcome.FAILED -> waitToRetry(retryDelaySeconds(consecutiveErrors) * 1000L)
                Outcome.ENDED -> return
                // The code on screen is the previous account's too. Another
                // account signed in meanwhile gets its own at once; with none,
                // the sign-out ends the page.
                Outcome.ACCOUNT_CHANGED -> {
                    clearCode()
                    if (!sessionValid()) return
                }
            }
        }
    }

    private enum class Outcome { SHOWN, FAILED, ENDED, ACCOUNT_CHANGED }

    /** Shows the cached code while it has time left, and counts it down; returns whether it did. */
    private suspend fun showCached(): Boolean {
        val forAccount = account()
        val (entry, secondsLeft) = cache.reusable(forAccount, now()) ?: return false
        val image = entry.image ?: run {
            _isLoading.value = _image.value == null
            try {
                render(entry.payload).also { cache.store(entry.payload, it, forAccount, entry.issuedAt) }
            } finally {
                _isLoading.value = false
            }
        }
        if (account() != forAccount) return false
        _image.value = image
        shownIssuedAt = entry.issuedAt
        shownAccount = forAccount
        errors.value = null
        countDown(secondsLeft)
        return true
    }

    private suspend fun fetchOnce(): Outcome {
        val forAccount = account()
        _isLoading.value = _image.value == null
        _fetching.value = true
        errors.value = null
        try {
            val payload = fetch()
            val rendered = render(payload)
            // Signed out, or another account signed in, while the request was
            // out: this code is the previous account's.
            if (account() != forAccount) return Outcome.ACCOUNT_CHANGED
            val issuedAt = now()
            cache.store(payload, rendered, forAccount, issuedAt)
            _image.value = rendered
            shownIssuedAt = issuedAt
            shownAccount = forAccount
            consecutiveErrors = 0
            onFetched()
            return Outcome.SHOWN
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (account() != forAccount) return Outcome.ACCOUNT_CHANGED
            errors.value = describe(e)
            if (!sessionValid()) {
                cache.clear()
                clearCode()
                consecutiveErrors = 0
                onSessionEnded()
                return Outcome.ENDED
            }
            consecutiveErrors++
            if (codeExpired()) clearCode()
            return Outcome.FAILED
        } finally {
            _isLoading.value = false
            _fetching.value = false
        }
    }

    /**
     * Waits [ms], taking a code still on screen off it the moment it runs out. [stop] ends the
     * wait's [waitingToRetry] when it cancels it.
     */
    private suspend fun waitToRetry(ms: Long) {
        _waitingToRetry.value = true
        val retryAt = now() + ms
        val issuedAt = shownIssuedAt
        if (issuedAt != null) {
            val expiresIn = issuedAt + LibraryApi.QR_VALID_SECONDS * 1000L - now()
            if (expiresIn < ms) {
                delay(expiresIn.coerceAtLeast(0))
                clearCode()
            }
        }
        delay((retryAt - now()).coerceAtLeast(0))
        _waitingToRetry.value = false
    }

    private suspend fun countDown(seconds: Int) {
        _countdown.value = seconds
        while (_countdown.value > 0) {
            delay(1000)
            _countdown.value--
        }
    }

    private fun codeExpired(): Boolean {
        val issuedAt = shownIssuedAt ?: return true
        return now() - issuedAt >= LibraryApi.QR_VALID_SECONDS * 1000L
    }

    private fun clearCode() {
        _image.value = null
        shownIssuedAt = null
        shownAccount = null
        _countdown.value = 0
    }

    companion object {
        private val BACKOFF_SECONDS = listOf(60, 120, 300)

        /** Seconds before the next try after [failures] transient failures in a row; holds at the last. */
        fun retryDelaySeconds(failures: Int): Int =
            BACKOFF_SECONDS[(failures - 1).coerceIn(0, BACKOFF_SECONDS.lastIndex)]
    }
}
