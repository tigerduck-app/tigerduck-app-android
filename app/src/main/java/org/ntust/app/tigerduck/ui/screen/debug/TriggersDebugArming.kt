package org.ntust.app.tigerduck.ui.screen.debug

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.firsttrigger.FirstTriggerPromptController
import org.ntust.app.tigerduck.ui.firsttrigger.FirstTriggerPromptKey
import org.ntust.app.tigerduck.ui.navigation.FlipToLibraryFirstTrigger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the debug "arm flip prompt" delay beyond a single screen's lifetime, so
 * popping and re-opening the Triggers page can't spawn a second in-flight timer.
 * Mirrors the iOS `TriggersDebugArming` singleton.
 *
 * The 3-second delay is deliberate: navigating away during it is part of what's
 * being tested (which tab the root-level prompt overlays), so the timer is not
 * cancelled when the screen is popped.
 */
@Singleton
class TriggersDebugArming @Inject constructor(
    private val controller: FirstTriggerPromptController,
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var job: Job? = null

    private val _isFlipArmed = MutableStateFlow(false)
    val isFlipArmed: StateFlow<Boolean> = _isFlipArmed.asStateFlow()

    /**
     * Reset the flip first-trigger seen flag and schedule the prompt to fire
     * after [ARM_DELAY_MS]. No-ops if a previous arm is still in flight.
     */
    fun armFlipPrompt(appState: AppState) {
        if (job != null) return
        controller.reset(FirstTriggerPromptKey.FLIP_TO_LIBRARY)
        _isFlipArmed.value = true
        job = scope.launch {
            delay(ARM_DELAY_MS)
            FlipToLibraryFirstTrigger.request(controller, appState)
            _isFlipArmed.value = false
            job = null
        }
    }

    companion object {
        private const val ARM_DELAY_MS = 3_000L
    }
}
