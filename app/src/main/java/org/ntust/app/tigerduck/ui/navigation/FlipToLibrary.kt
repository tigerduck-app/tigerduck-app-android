package org.ntust.app.tigerduck.ui.navigation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.ntust.app.tigerduck.sensor.FlipDetector
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.haptics.HapticScenario
import org.ntust.app.tigerduck.ui.haptics.Haptics

/**
 * Side-effect composable that wires [FlipDetector] into the nav controller.
 *
 * The detector is registered only while:
 *   - the device has a rotation-vector sensor, AND
 *   - the user has opted in via `appState.flipToLibraryEnabled`, AND
 *   - the parent Library feature itself is on (`appState.libraryFeatureEnabled`),
 *     so disabling Library actually stops the sensor instead of leaving it
 *     running while every callback bails at a fire-time guard, AND
 *   - the host activity is at least STARTED.
 *
 * The library-session check ([AppState.isLibraryLoggedIn]) runs at fire time
 * inside the callback, not as a registration gate, because
 * [org.ntust.app.tigerduck.data.preferences.CredentialManager] only exposes
 * synchronous accessors — adding a flow purely for this gate is more plumbing
 * than the feature warrants.
 *
 * Net effect: when the user flips the phone face-down with a valid library
 * session, the nav controller jumps to [Screen.Library]; flips with no session
 * or already-on-Library are silently no-op.
 */
@Composable
fun FlipToLibraryEffect(
    navController: NavController,
    appState: AppState,
) {
    val context = LocalContext.current
    val supported = remember(context) { FlipDetector.isSupported(context) }
    if (!supported) return

    // Reading the Compose-state-backed properties here makes the enclosing
    // composition observe changes, so the DisposableEffect below restarts
    // when the user toggles either setting.
    val enabled = appState.flipToLibraryEnabled && appState.libraryFeatureEnabled

    // Cold-start race: MainNavigation installs this effect before NavHost
    // declares its graph, so a flip during initial composition lands before
    // navigate() has any destinations to route to. Dropping the callback
    // would also strand the detector in FaceDown (it only fires once per
    // Upright -> FaceDown transition), forcing the user to re-arm with a
    // full upright -> face-down cycle before the next try would register.
    // Queue the pending flip and consume it once the back stack appears.
    var pendingFlip by remember(navController, appState) { mutableStateOf(false) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val detector = remember(navController, appState) {
        FlipDetector(context) {
            // Fire-time guards. All checks are synchronous and main-thread —
            // sensor callbacks deliver on the main thread by default.
            if (!appState.libraryFeatureEnabled) return@FlipDetector
            if (!appState.isLibraryLoggedIn) return@FlipDetector
            if (navController.currentBackStackEntry == null) {
                pendingFlip = true
                return@FlipDetector
            }
            navigateToLibrary(navController, context)
        }
    }

    if (pendingFlip) {
        LaunchedEffect(Unit) {
            snapshotFlow { navController.currentBackStackEntry }
                .filterNotNull()
                .first()
            // Re-check gates at consume time — `enabled` and the session
            // can change while the back stack is being built.
            if (appState.flipToLibraryEnabled &&
                appState.libraryFeatureEnabled &&
                appState.isLibraryLoggedIn
            ) {
                navigateToLibrary(navController, context)
            }
            pendingFlip = false
        }
    }

    DisposableEffect(enabled, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (enabled) detector.register()
                Lifecycle.Event.ON_STOP -> detector.unregister()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (enabled && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            detector.register()
        }
        onDispose {
            lifecycle.removeObserver(observer)
            detector.unregister()
        }
    }
}

private fun navigateToLibrary(navController: NavController, context: Context) {
    val currentRoute = navController.currentDestination?.route
    if (currentRoute == Screen.Library.route) return
    navController.navigate(Screen.Library.route) {
        launchSingleTop = true
    }
    Haptics.perform(context, HapticScenario.FlipToLibrary)
}
