package org.ntust.app.tigerduck.ui.screen.classtable.sharing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The import scanner's flashlight, apart from CameraX so it can be tested without a camera. It is
 * offered only while a camera with a flash is bound, and its icon shows what the camera reports
 * (`CameraInfo.torchState`), not what was asked, so it stays right when the system turns the
 * torch off. It goes off with the camera, and a camera bound again starts with it off.
 * Called on the main thread only.
 */
class ScannerTorch {
    private var enable: ((Boolean) -> Unit)? = null

    /** Whether the toggle shows: a camera with a flash is bound. */
    var isAvailable by mutableStateOf(false)
        private set

    /**
     * Until the camera answers: the toggle's place is held meanwhile, so the buttons beside it do
     * not move once a camera with a flash is bound, as on most phones.
     */
    var isPending by mutableStateOf(true)
        private set

    /** Whether the camera reports the torch on. */
    var isOn by mutableStateOf(false)
        private set

    /** A camera bound; [enable] turns its torch on or off (`CameraControl.enableTorch`), null for one without a flash. */
    fun bound(enable: ((Boolean) -> Unit)?) {
        this.enable = enable
        isAvailable = enable != null
        isOn = false
        isPending = false
    }

    /** No camera will be bound: none, access refused, or one that did not start. */
    fun unavailable() {
        unbinding()
        isPending = false
    }

    /** The camera's torch state changed, to [on]. */
    fun stateChanged(on: Boolean) {
        isOn = isAvailable && on
    }

    fun toggle() {
        enable?.invoke(!isOn)
    }

    /** The camera is about to stop: the torch goes off with it, and the toggle goes away. */
    fun unbinding() {
        if (isOn) enable?.invoke(false)
        enable = null
        isAvailable = false
        isOn = false
    }
}
