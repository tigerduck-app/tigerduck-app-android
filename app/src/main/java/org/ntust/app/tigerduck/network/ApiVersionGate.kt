package org.ntust.app.tigerduck.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Latches when our backend answers `410 Gone`.
 *
 * The server retires an API version by answering every request to it with
 * 410 rather than 404, precisely so a build talking to a version that no
 * longer exists gets an unambiguous "this app is too old" instead of a
 * shapeless network failure. Nothing the app can do fixes it — the only
 * remedy is a newer build — so it is worth telling the user plainly rather
 * than letting every screen fail on its own.
 *
 * Only responses from our own backend reach here; [ApiVersionInterceptor]
 * does the filtering. A 410 from NTUST or Moodle means one of *their* pages
 * moved, which says nothing about the app's version.
 *
 * One-way on purpose. Once the server has said a version is gone, no amount
 * of retrying brings it back, and a banner that came and went as unrelated
 * requests happened to succeed would read as a glitch rather than as the
 * permanent state it is.
 *
 * A plain object rather than an injected singleton so the interceptor — which
 * is built inside the OkHttp provider, before most of the graph exists — can
 * reach it without threading a dependency through the client.
 */
object ApiVersionGate {

    private val _isRetired = MutableStateFlow(false)

    /** True once any backend call has come back 410. */
    val isRetired: StateFlow<Boolean> = _isRetired.asStateFlow()

    /** Report the status of a response from our own backend. */
    fun note(statusCode: Int) {
        if (statusCode == 410) _isRetired.value = true
    }

    /** Test seam. The latch is process-wide by design. */
    fun resetForTesting() {
        _isRetired.value = false
    }
}
