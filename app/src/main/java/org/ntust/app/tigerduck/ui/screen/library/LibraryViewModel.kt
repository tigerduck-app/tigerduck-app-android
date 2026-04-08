package org.ntust.app.tigerduck.ui.screen.library

import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.network.LibraryService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryService: LibraryService,
    private val credentials: CredentialManager
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
    private val qrValidSeconds = 60

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
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "登入失敗"
            } finally {
                _isLoggingIn.value = false
            }
        }
    }

    fun refreshQR() {
        viewModelScope.launch {
            _isLoadingQR.value = true
            _errorMessage.value = null
            try {
                val qrData = libraryService.generateQRCode()
                _qrBitmap.value = generateQRBitmap(qrData)
                startCountdown()
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "QR 碼產生失敗"
            } finally {
                _isLoadingQR.value = false
            }
        }
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        _countdown.value = qrValidSeconds
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
        _isLoggedIn.value = credentials.isLibraryTokenValid
        if (_isLoggedIn.value && _qrBitmap.value == null) {
            refreshQR()
        }
    }

    fun onPause() {
        countdownJob?.cancel()
    }

    private fun generateQRBitmap(content: String, size: Int = 512): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }
}
