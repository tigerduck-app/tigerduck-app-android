package org.ntust.app.tigerduck.ui.screen.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.network.LibraryService
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryService: LibraryService,
    private val credentials: CredentialManager,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    private val _qrBitmap = MutableStateFlow<Bitmap?>(null)
    val qrBitmap: StateFlow<Bitmap?> = _qrBitmap

    private val _countdown = MutableStateFlow(0)
    val countdown: StateFlow<Int> = _countdown

    private val _isLoadingQR = MutableStateFlow(false)
    val isLoadingQR: StateFlow<Boolean> = _isLoadingQR

    private val _isLoggingIn = MutableStateFlow(false)
    val isLoggingIn: StateFlow<Boolean> = _isLoggingIn

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _isLoggedIn = MutableStateFlow(credentials.isLibraryTokenValid)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn

    private val _storedUsername = MutableStateFlow(credentials.libraryUsername)
    val storedUsername: StateFlow<String?> = _storedUsername

    private var countdownJob: Job? = null
    private var refreshJob: Job? = null

    /** Suggested pre-fill for the login form — stored library user, or NTUST student ID. */
    val suggestedUsername: String
        get() = credentials.libraryUsername ?: credentials.ntustStudentId.orEmpty()

    fun load() {
        _isLoggedIn.value = credentials.isLibraryTokenValid
        _storedUsername.value = credentials.libraryUsername
        if (_isLoggedIn.value) {
            refreshQR()
        }
    }

    fun loginAndRefresh(username: String, password: String) {
        viewModelScope.launch {
            _isLoggingIn.value = true
            _errorMessage.value = null
            try {
                libraryService.login(username, password)
                _isLoggedIn.value = true
                _storedUsername.value = username
                refreshQR()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: context.getString(R.string.error_login_failed)
            } finally {
                _isLoggingIn.value = false
            }
        }
    }

    private fun refreshQR() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _isLoadingQR.value = _qrBitmap.value == null
            _errorMessage.value = null
            try {
                val qrData = libraryService.generateQRCode()
                // QR rendering scans 512*512 pixels — keep it off the main
                // thread so the countdown animation and any ongoing gestures
                // don't stutter.
                val bitmap = withContext(Dispatchers.Default) { generateQRBitmap(qrData) }
                _qrBitmap.value = bitmap
                startCountdown()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errorMessage.value =
                    e.message ?: context.getString(R.string.library_qr_generate_failed)
                // If the failure means our session is gone, drop back to the
                // login prompt instead of looping on a dead token.
                if (!credentials.isLibraryTokenValid) {
                    _isLoggedIn.value = false
                    _qrBitmap.value = null
                    countdownJob?.cancel()
                }
            } finally {
                _isLoadingQR.value = false
            }
        }
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        _countdown.value = QR_VALID_SECONDS
        countdownJob = viewModelScope.launch {
            while (isActive && _countdown.value > 0) {
                delay(1000)
                _countdown.value--
            }
            if (isActive && _countdown.value == 0) {
                refreshQR()
            }
        }
    }

    fun onResume() {
        val wasLoggedIn = _isLoggedIn.value
        _isLoggedIn.value = credentials.isLibraryTokenValid
        _storedUsername.value = credentials.libraryUsername
        if (!_isLoggedIn.value) return
        // onPause cancels the countdown, so always kick off a fresh fetch
        // when the screen becomes active again. This also handles the case
        // where the user just logged in via another screen.
        if (!wasLoggedIn || _qrBitmap.value == null || refreshJob?.isActive != true) {
            refreshQR()
        }
    }

    fun onPause() {
        countdownJob?.cancel()
        refreshJob?.cancel()
    }

    private fun generateQRBitmap(content: String, size: Int = 512): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        // Build a single IntArray then hand it to Bitmap.createBitmap so we
        // avoid the ~260k setPixel calls the naive loop would do per refresh.
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val row = y * size
            for (x in 0 until size) {
                pixels[row + x] = if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
    }

    companion object {
        const val QR_VALID_SECONDS = 30
    }
}
