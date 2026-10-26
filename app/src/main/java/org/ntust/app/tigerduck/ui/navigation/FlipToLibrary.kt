package org.ntust.app.tigerduck.ui.navigation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ntust.app.tigerduck.library.FlipToLibrarySetting
import org.ntust.app.tigerduck.sensor.FlipDetector
import org.ntust.app.tigerduck.sensor.FlipSensor
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.firsttrigger.FirstTriggerPromptController
import org.ntust.app.tigerduck.ui.firsttrigger.FirstTriggerPromptKey
import org.ntust.app.tigerduck.ui.haptics.HapticScenario
import org.ntust.app.tigerduck.ui.haptics.Haptics
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Side-effect composable that wires [FlipDetector] into the nav controller.
 *
 * The detector is registered only while:
 *   - the device has a rotation-vector sensor, AND
 *   - [FlipToLibraryGate.isArmed]: a library account is signed in, as the
 *     switch in Settings shows it, and either the user has turned it on via
 *     `appState.flipToLibraryEnabled` or the question that offers it has never
 *     been answered, AND
 *   - the host activity is at least STARTED.
 *
 * A flip checks the sign-in again as it fires: the account can be refused or
 * signed out between the last
 * [org.ntust.app.tigerduck.library.LibrarySession.refresh] and the flip. An
 * expired token with stored credentials still counts, since the Library page
 * signs in again behind the code (tigerduck-app/tigerduck-app#528).
 *
 * Net effect: when the user flips the phone face-down with a valid library
 * session, the nav controller jumps to [Screen.Library]. With the switch off,
 * that flip asks once whether to turn it on instead, and Turn on opens Library
 * for it. The question is asked on a flip only, never on a sign-in or a
 * launch. Flips already on Library or with something in front of the page
 * (see [FlipToLibraryGate]) are silently no-op.
 */
@Composable
fun FlipToLibraryEffect(
    navController: NavController,
    appState: AppState,
) {
    val context = LocalContext.current
    val supported = remember(context) { FlipDetector.isSupported(context) }
    if (!supported) return

    val entryPoint = remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, FlipToLibraryEntryPoint::class.java)
    }
    val firstTriggerController = remember(entryPoint) { entryPoint.firstTriggerPromptController() }
    val openRequest = remember(entryPoint) { entryPoint.flipToLibraryOpenRequest() }

    val signedIn by appState.librarySession.signedIn.collectAsStateWithLifecycle()
    val offerUnanswered = rememberFlipOfferUnanswered(firstTriggerController, appState.flipToLibraryEnabled)

    FlipToLibraryEffect(
        navController = navController,
        flipToLibraryEnabled = { appState.flipToLibraryEnabled },
        signedInToLibrary = { signedIn },
        stillSignedInToLibrary = { appState.librarySession.refresh() },
        onNavigated = { Haptics.perform(context, HapticScenario.FlipToLibrary) },
        offerUnanswered = { offerUnanswered },
        // The root host shows it, behind What's New and the update prompt.
        onOffer = {
            FlipToLibraryPrompt.request(firstTriggerController, appState.flipToLibrary, onTurnedOn = openRequest::request)
        },
        openRequest = openRequest,
    )
}

/**
 * Whether the question that offers the gesture is still to ask. The answer is
 * stored, which Compose does not follow, so it is read again whenever a
 * question comes or goes, as answering one clears it, and whenever the switch
 * changes, as a change in Settings answers it too.
 */
@Composable
internal fun rememberFlipOfferUnanswered(controller: FirstTriggerPromptController, enabled: Boolean): Boolean {
    val pending by controller.pending.collectAsStateWithLifecycle()
    return remember(pending, enabled) { !controller.hasSeen(FirstTriggerPromptKey.FLIP_TO_LIBRARY) }
}

/**
 * [FlipToLibraryEffect] with its app-wide state read through lambdas, so a
 * test can drive a real detector, window and back stack without Hilt.
 * [stillSignedInToLibrary] checks the sign-in again as a flip fires.
 * [onNavigated] runs after a flip has opened Library (the haptic).
 * [offerUnanswered] is whether the question that offers the gesture is still
 * to ask; with the switch off, a flip then calls [onOffer] instead of opening
 * Library. Its Turn on comes back through [openRequest], which this takes
 * while on screen to open Library.
 * [newSensor] builds the detector, or returns null where the phone has no
 * sensor for it; an instrumented test passes one it can fire by hand.
 *
 * [flipToLibraryEnabled], [signedInToLibrary] and [offerUnanswered] are also
 * read during composition: a change to any of them must reach it, so it
 * re-registers the detector.
 */
@Composable
internal fun FlipToLibraryEffect(
    navController: NavController,
    flipToLibraryEnabled: () -> Boolean,
    signedInToLibrary: () -> Boolean,
    stillSignedInToLibrary: () -> Boolean,
    onNavigated: () -> Unit,
    offerUnanswered: () -> Boolean = { false },
    onOffer: () -> Unit = {},
    openRequest: FlipToLibraryOpenRequest? = null,
    newSensor: (Context, onFaceDown: () -> Unit) -> FlipSensor? = ::flipDetectorIfSupported,
) {
    val context = LocalContext.current

    val currentFlipEnabled by rememberUpdatedState(flipToLibraryEnabled)
    val currentStillSignedIn by rememberUpdatedState(stillSignedInToLibrary)
    val currentOnNavigated by rememberUpdatedState(onNavigated)
    val currentOfferUnanswered by rememberUpdatedState(offerUnanswered)
    val currentOnOffer by rememberUpdatedState(onOffer)
    // The app window's; a dialog or sheet is a window of its own.
    val windowInfo by rememberUpdatedState(LocalWindowInfo.current)

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val detector = remember(navController) {
        newSensor(context) onFaceDown@{
            // Fire-time guards. All checks are synchronous and main-thread —
            // sensor callbacks deliver on the main thread by default. Re-checking
            // the switch closes the brief window where the user toggles
            // flipToLibrary off and a sensor event lands before
            // DisposableEffect restarts and unregisters the detector. With
            // the switch off, only the question still to ask is listening.
            val enabled = currentFlipEnabled()
            if (!enabled && !currentOfferUnanswered()) return@onFaceDown
            if (!currentStillSignedIn()) return@onFaceDown
            // Not over a dialog, a sheet or a mail draft: leaving the page
            // drops what the user was in the middle of. Nothing is consumed,
            // so the next flip with nothing open works.
            if (!FlipToLibraryGate.mayNavigate(
                    windowFocused = windowInfo.isWindowFocused,
                    currentRoute = navController.currentDestination?.route,
                )
            ) return@onFaceDown
            // No graph yet: dropped like any other ignored flip. This used to
            // queue the flip for the graph to appear, but no flip can get here
            // before it does. A flip fires at the earliest 800 ms of sensor
            // events (two 400 ms debounce windows) after the detector registers,
            // and the detector registers in the same frame that composes
            // MainNavigation's NavHost, which sets the graph; the window
            // focus the gate above requires also arrives only after that
            // first frame.
            if (navController.currentBackStackEntry == null) return@onFaceDown
            if (enabled) {
                if (navigateToLibrary(navController)) currentOnNavigated()
            } else if (FlipToLibraryGate.offersGesture(
                    enabled = false,
                    asked = !currentOfferUnanswered(),
                    onLibraryPage = navController.currentDestination?.route == Screen.Library.route,
                )
            ) {
                currentOnOffer()
            }
        }
    } ?: return // No rotation-vector sensor on this phone.

    // The question's Turn on, taken by the page on screen when it is given:
    // the activity that asked may have been made anew under the question. The
    // switch and the sign-in are checked again, as at a flip: the sign-in can
    // be refused while the question is up. A request that fails them is
    // dropped rather than kept for later.
    if (openRequest != null) {
        val requested by openRequest.pending.collectAsStateWithLifecycle()
        val hasPage = navController.currentBackStackEntryAsState().value != null
        LaunchedEffect(requested, hasPage) {
            if (!requested || !hasPage || !openRequest.take()) return@LaunchedEffect
            if (!currentFlipEnabled() || !currentStillSignedIn()) return@LaunchedEffect
            if (navigateToLibrary(navController)) currentOnNavigated()
        }
    }

    // Reading the Compose-state-backed switch, the sign-in and the question
    // here makes the enclosing composition observe changes, so the
    // DisposableEffect below restarts when any of them changes.
    val armed = FlipToLibraryGate.isArmed(
        enabled = flipToLibraryEnabled(),
        signedInToLibrary = signedInToLibrary(),
        offerUnanswered = offerUnanswered(),
    )

    DisposableEffect(armed, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (armed) detector.register()
                Lifecycle.Event.ON_STOP -> detector.unregister()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (armed && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            detector.register()
        }
        onDispose {
            lifecycle.removeObserver(observer)
            detector.unregister()
        }
    }
}

/**
 * Whether flip-to-library listens, what its switch in Settings shows, when a
 * flip offers it, and whether a flip may leave the current page.
 *
 * The flip opens the library's QR code, which needs a signed-in library
 * account, so until one is the switch reads off and takes no input; the
 * choice it holds comes back with the sign-in.
 *
 * The question that offers the gesture is asked on a flip, not after a
 * sign-in as on the iPhone (tigerduck-app/tigerduck-app#529): it comes when
 * someone has just done what it offers.
 *
 * The iPhone ignores the flip while a sheet or alert is presented, since the
 * switch would drop whatever the user was in the middle of. Android has no
 * "something is presented" flag, so [mayNavigate] asks whether the app's
 * window has the input focus, which every window in front of it takes. That
 * makes the flip do nothing, by design, whenever the app is visible but
 * something else holds the focus:
 *   - in split screen, a freeform or pop-up window, or DeX, while the other
 *     app is the one last touched;
 *   - while a system dialog is up: a permission prompt, the low-battery or
 *     USB dialog, the share sheet;
 *   - while the notification shade, an expanded bubble or an edge panel is
 *     open.
 * A heads-up notification, the volume panel, a toast and the keyboard take
 * no focus, so a flip with one of them showing still works. Once the focus
 * is back, the next flip (upright, then face down again) works.
 */
internal object FlipToLibraryGate {
    /**
     * Pages the iPhone presents as a sheet, which the flip leaves alone
     * there: the mail composer, whose draft would be dropped.
     */
    private val SHEET_ROUTES = setOf(Screen.SchoolMailCompose.route)

    /**
     * Signed in to the library, with the switch on, or with it off and the
     * question that offers it still to ask ([offerUnanswered]), which a flip
     * asks.
     */
    fun isArmed(enabled: Boolean, signedInToLibrary: Boolean, offerUnanswered: Boolean): Boolean =
        signedInToLibrary && (enabled || offerUnanswered)

    fun switchReadsOn(enabled: Boolean, signedInToLibrary: Boolean): Boolean = enabled && signedInToLibrary

    fun switchTakesInput(signedInToLibrary: Boolean): Boolean = signedInToLibrary

    /**
     * Whether a flip that may leave the page asks to turn the gesture on
     * rather than opening Library: the switch off and the question never
     * answered. Not on the Library page, where the question would cover the
     * code it shows; a flip there does nothing, as it does with the switch on.
     */
    fun offersGesture(enabled: Boolean, asked: Boolean, onLibraryPage: Boolean): Boolean =
        !enabled && !asked && !onLibraryPage

    /**
     * [windowFocused] is whether the app's own window has the focus. A
     * dialog, a bottom sheet, a dropdown menu, a system permission prompt
     * and the pulled-down notification shade are each a window of their
     * own that takes it, so this one check covers them all, the way the
     * iPhone asks UIKit whether anything is presented.
     */
    fun mayNavigate(windowFocused: Boolean, currentRoute: String?): Boolean =
        windowFocused && currentRoute !in SHEET_ROUTES
}

/** A [FlipDetector] where the phone has the sensor it reads, else nothing to register. */
private fun flipDetectorIfSupported(context: Context, onFaceDown: () -> Unit): FlipSensor? =
    if (FlipDetector.isSupported(context)) FlipDetector(context, onFaceDown) else null

/** Whether it navigated: a flip on Library itself stays put, without the haptic. */
private fun navigateToLibrary(navController: NavController): Boolean {
    val currentRoute = navController.currentDestination?.route
    if (currentRoute == Screen.Library.route) return false
    navController.navigate(Screen.Library.route) {
        launchSingleTop = true
    }
    return true
}

/**
 * The question that offers the flip gesture, asked on a flip and shared with
 * the debug Triggers screen, which replays it after
 * [FirstTriggerPromptController.reset]. No-ops once the question has been
 * answered.
 *
 * Turn on stores the switch on, which keeps the sensor in
 * [FlipToLibraryEffect] listening, then runs [onTurnedOn]: asking for Library
 * to open for the flip that asked ([FlipToLibraryOpenRequest]). Maybe later
 * stores it off: an answer, which the default off is not, and the sensor
 * stops.
 */
object FlipToLibraryPrompt {
    fun request(controller: FirstTriggerPromptController, setting: FlipToLibrarySetting, onTurnedOn: () -> Unit = {}) {
        controller.requestIfFirstTime(
            key = FirstTriggerPromptKey.FLIP_TO_LIBRARY,
            onAccept = {
                setting.enabled = true
                onTurnedOn()
            },
            onDecline = { setting.enabled = false },
        )
    }
}

/**
 * A Turn on given to a flip's question, for the page on screen to open Library.
 * The question is the app's and outlives the activity that asked it, which a
 * theme, font or display size change makes anew under it, so the Turn on is
 * held as data [FlipToLibraryEffect] takes, never as a call into the
 * NavController that asked.
 */
@Singleton
class FlipToLibraryOpenRequest @Inject constructor() {
    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    fun request() {
        _pending.value = true
    }

    /** Whether there was a request to take; it is taken, so only one page opens Library for it. */
    fun take(): Boolean = _pending.compareAndSet(expect = true, update = false)
}

@EntryPoint
@InstallIn(SingletonComponent::class)
private interface FlipToLibraryEntryPoint {
    fun firstTriggerPromptController(): FirstTriggerPromptController
    fun flipToLibraryOpenRequest(): FlipToLibraryOpenRequest
}
