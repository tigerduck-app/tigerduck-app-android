package org.ntust.app.tigerduck.ui.component

import androidx.compose.foundation.layout.WindowInsets

/**
 * Zero window insets for a sub-screen's `TopAppBar`.
 *
 * A sub-screen is drawn already clear of the system bars, because whatever
 * hosts it pads the whole screen by them. In the main app that host is the
 * root `Scaffold`, whose `NavHost` carries `Modifier.padding(innerPadding)`.
 * A `TopAppBar` left on `TopAppBarDefaults.windowInsets` would inset itself by
 * the status bar a *second* time, which is the empty strip that used to sit
 * above the back button and the sub-menu title.
 *
 * Horizontal insets go to zero for the same reason: the root padding already
 * covers display cutouts, so re-applying them would indent the back button
 * in landscape.
 *
 * A screen shown anywhere else has to be padded the same way by whoever
 * shows it. Onboarding does this for `ApiEndpointDebugScreen`, wrapping it in
 * `ScaffoldDefaults.contentWindowInsets` padding (top and sides). A new host
 * that skips that padding puts the back button and title under the status
 * bar and the camera cutout.
 *
 * This is the top-bar counterpart of the `contentWindowInsets =
 * WindowInsets(0, 0, 0, 0)` several of these screens already pass to their
 * own `Scaffold`.
 */
val NoTopBarInsets = WindowInsets(0, 0, 0, 0)
