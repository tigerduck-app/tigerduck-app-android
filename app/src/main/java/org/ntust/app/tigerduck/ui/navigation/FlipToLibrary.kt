package org.ntust.app.tigerduck.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
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

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val detector = remember(navController, appState) {
        FlipDetector(context) {
            // Fire-time guards. All checks are synchronous and main-thread —
            // sensor callbacks deliver on the main thread by default.
            if (!appState.libraryFeatureEnabled) return@FlipDetector
            if (!appState.isLibraryLoggedIn) return@FlipDetector
            // Cold-start race: MainNavigation installs this effect before
            // the NavHost declares its graph, so a flip during initial
            // composition can navigate() with no destinations registered.
            // currentBackStackEntry stays null until startDestination is
            // routed; drop early flips rather than queueing — the gesture
            // is opportunistic and the user can just flip again.
            if (navController.currentBackStackEntry == null) return@FlipDetector
            val currentRoute = navController.currentDestination?.route
            if (currentRoute == Screen.Library.route) return@FlipDetector
            navController.navigate(Screen.Library.route) {
                launchSingleTop = true
            }
            Haptics.perform(context, HapticScenario.FlipToLibrary)
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
