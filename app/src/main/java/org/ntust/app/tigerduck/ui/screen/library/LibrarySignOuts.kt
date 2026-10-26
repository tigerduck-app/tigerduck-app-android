package org.ntust.app.tigerduck.ui.screen.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Runs [onSignedOut] each time [signedIn], the library sign-in state Settings
 * and the flip follow, turns signed out while [stillSignedIn], which reads the
 * stores again, agrees. The Library page drops to its sign-in form then, also
 * while its code request backs off between tries: a refusal another caller
 * found, such as the sign-in at a return to the app, would otherwise leave it
 * showing the pass until its next try (tigerduck-app/tigerduck-app#556).
 */
internal fun CoroutineScope.followSignOuts(
    signedIn: Flow<Boolean>,
    stillSignedIn: () -> Boolean,
    onSignedOut: suspend () -> Unit,
): Job = launch {
    signedIn.collect { if (!it && !stillSignedIn()) onSignedOut() }
}
