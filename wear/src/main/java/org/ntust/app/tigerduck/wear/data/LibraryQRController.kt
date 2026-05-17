package org.ntust.app.tigerduck.wear.data

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.shared.LibraryApi
import org.ntust.app.tigerduck.shared.LibraryQRRenderer
import org.ntust.app.tigerduck.shared.LibraryService

/**
 * Drives the watch's library QR page: fetches a fresh code from
 * `api.lib.ntust.edu.tw`, renders the bitmap off-main, and ticks a countdown
 * until it auto-refreshes. Mirrors the phone's [LibraryViewModel] behaviour but
 * lives outside the Compose layer so it can be reused by Tile/Complication
 * later if we want a watch-face shortcut.
 */
class LibraryQRController(
    private val service: LibraryService,
    private val scope: CoroutineScope,
) {
    private val _qrBitmap = MutableStateFlow<Bitmap?>(null)
    val qrBitmap: StateFlow<Bitmap?> = _qrBitmap.asStateFlow()

    private val _countdown = MutableStateFlow(0)
    val countdown: StateFlow<Int> = _countdown.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var refreshJob: Job? = null
    private var countdownJob: Job? = null

    /** Start (or restart) the fetch + countdown cycle. */
    fun start(qrSidePx: Int) {
        refreshJob?.cancel()
        refreshJob = scope.launch { refresh(qrSidePx) }
    }

    /** Pause refreshes when the page leaves the screen. */
    fun stop() {
        refreshJob?.cancel()
        countdownJob?.cancel()
    }

    /** Drop the cached bitmap (e.g. after a logout push from phone). */
    fun reset() {
        stop()
        _qrBitmap.value = null
        _countdown.value = 0
        _error.value = null
    }

    private suspend fun refresh(qrSidePx: Int) {
        _isLoading.value = _qrBitmap.value == null
        _error.value = null
        try {
            val qrData = service.generateQRCode()
            val bitmap = withContext(Dispatchers.Default) {
                LibraryQRRenderer.render(qrData, qrSidePx)
            }
            _qrBitmap.value = bitmap
            startCountdown(qrSidePx)
        } catch (e: Exception) {
            // Surface the real reason (network failure, server message, etc.)
            // rather than a generic string — diagnosing a watch-only failure
            // from logcat alone is painful and the screen has room for it.
            android.util.Log.w("LibraryQRController", "QR refresh failed", e)
            _error.value = e.message?.takeUnless { it.isBlank() }
                ?: e::class.simpleName
                ?: "QR fetch failed"
            _qrBitmap.value = null
        } finally {
            _isLoading.value = false
        }
    }

    private fun startCountdown(qrSidePx: Int) {
        countdownJob?.cancel()
        _countdown.value = LibraryApi.QR_VALID_SECONDS
        countdownJob = scope.launch {
            while (isActive && _countdown.value > 0) {
                delay(1000)
                _countdown.value--
            }
            if (isActive && _countdown.value == 0) {
                refresh(qrSidePx)
            }
        }
    }
}
