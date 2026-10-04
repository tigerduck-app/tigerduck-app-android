package org.ntust.app.tigerduck.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

// One slide for every push and pop, the back swipe included: NavHost's own
// predictive-back default is a scale-down to 70%, which Android 16 plays on
// every gesture back now that the app targets API 36.
private val slideSpec = tween<IntOffset>(300, easing = FastOutSlowInEasing)

/**
 * Whether moving from [from] to [to] slides rather than crossfades. Tabs are
 * siblings with no left-to-right order, so moving onto a tab — or, going
 * back, off one — keeps the fade; every other push and pop slides.
 */
internal fun slidesBetween(from: String?, to: String?, isPop: Boolean, tabRoutes: Set<String>): Boolean =
    if (isPop) from !in tabRoutes else to !in tabRoutes

private fun AnimatedContentTransitionScope<NavBackStackEntry>.slides(isPop: Boolean, tabRoutes: Set<String>) =
    slidesBetween(initialState.destination.route, targetState.destination.route, isPop, tabRoutes)

/** The opened screen comes in from the end edge. */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.pushEnter(tabRoutes: Set<String>): EnterTransition =
    if (slides(isPop = false, tabRoutes)) slideIntoContainer(SlideDirection.Start, slideSpec) else fadeIn(tween(150))

/** The screen underneath drifts a quarter width towards the start. */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.pushExit(tabRoutes: Set<String>): ExitTransition =
    if (slides(isPop = false, tabRoutes)) slideOutOfContainer(SlideDirection.Start, slideSpec) { it / 4 } else fadeOut(tween(100))

/** The screen underneath drifts back from a quarter width out. */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnter(tabRoutes: Set<String>): EnterTransition =
    if (slides(isPop = true, tabRoutes)) slideIntoContainer(SlideDirection.End, slideSpec) { it / 4 } else fadeIn(tween(150))

/** The closed screen leaves by the end edge; a back swipe drags it there. */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(tabRoutes: Set<String>): ExitTransition =
    if (slides(isPop = true, tabRoutes)) slideOutOfContainer(SlideDirection.End, slideSpec) else fadeOut(tween(100))

/**
 * A [composable] destination painted with the theme background. Many screens
 * leave their background to the outer Scaffold, which a fade hid; while two
 * screens slide past each other, one would show through the other.
 */
internal fun NavGraphBuilder.page(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit,
) = composable(route, arguments) { entry ->
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        content(entry)
    }
}
