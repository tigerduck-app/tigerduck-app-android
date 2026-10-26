package org.ntust.app.tigerduck.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Work the student waits on and must not cut short, such as the sign-in an open of a Moodle page
 * waits for. While any of it runs, [AppProcessingHost][org.ntust.app.tigerduck.ui.component.AppProcessingHost]
 * shows the app-wide spinner saying the app is busy and not to close it. Each piece of work takes
 * a [Hold], started when the wait begins and stopped on every way out of it.
 */
@Singleton
class AppProcessing @Inject constructor() {
    private val count = MutableStateFlow(0)

    /** How many holds are started; the spinner shows while this is above zero. */
    val holds: StateFlow<Int> = count.asStateFlow()

    fun newHold(): Hold = Hold()

    /** One piece of work's claim on the spinner; starting or stopping it again changes nothing. */
    inner class Hold internal constructor() {
        private var started = false

        fun start() = synchronized(this) {
            if (!started) {
                started = true
                count.update { it + 1 }
            }
        }

        fun stop() = synchronized(this) {
            if (started) {
                started = false
                count.update { it - 1 }
            }
        }
    }
}
