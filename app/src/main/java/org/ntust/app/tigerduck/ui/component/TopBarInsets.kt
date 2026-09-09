package org.ntust.app.tigerduck.ui.component

import androidx.compose.foundation.layout.WindowInsets

/**
 * Zero window insets for a sub-screen's `TopAppBar`.
 *
 * Every sub-screen is a destination inside the root `Scaffold`'s `NavHost`,
 * and that Scaffold already pads the whole graph by the system bars — the
 * `NavHost` carries `Modifier.padding(innerPadding)`. A `TopAppBar` left on
 * `TopAppBarDefaults.windowInsets` therefore inset itself by the status bar
 * a *second* time, which is the empty strip that used to sit above the back
 * button and the sub-menu title.
 *
 * Horizontal insets go to zero for the same reason: the root padding already
 * covers display cutouts, so re-applying them would indent the back button
 * in landscape.
 *
 * This is the top-bar counterpart of the `contentWindowInsets =
 * WindowInsets(0, 0, 0, 0)` several of these screens already pass to their
 * own `Scaffold`.
 */
val NoTopBarInsets = WindowInsets(0, 0, 0, 0)
