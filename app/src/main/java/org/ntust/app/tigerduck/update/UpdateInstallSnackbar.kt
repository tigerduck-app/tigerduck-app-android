package org.ntust.app.tigerduck.update

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R

/**
 * Observes [UpdateChecker.installReady]; once a FLEXIBLE update has downloaded,
 * shows an indefinite snackbar with a "Restart" action that installs it.
 *
 * Flavor-safe: on fdroid `installReady` is permanently false, so this never
 * shows anything.
 */
@Composable
fun UpdateInstallSnackbar(
    updateChecker: UpdateChecker,
    snackbarHostState: SnackbarHostState,
) {
    val installReady by updateChecker.installReady.collectAsStateWithLifecycle()
    val message = stringResource(R.string.update_ready_message)
    val actionLabel = stringResource(R.string.update_restart_action)

    LaunchedEffect(installReady) {
        if (!installReady) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = actionLabel,
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) {
            updateChecker.completeUpdate()
        }
    }
}
