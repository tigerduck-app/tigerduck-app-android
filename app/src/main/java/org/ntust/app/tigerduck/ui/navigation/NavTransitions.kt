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
 * back, off one — keeps the fade; every other push and pop slides. A
 * bottom-bar tap ([tabTapped]) always fades: tapping the first tab from a
 * pushed screen pops back to it, which would otherwise read as a back.
 */
internal fun slidesBetween(
    from: String?,
    to: String?,
    isPop: Boolean,
    tabRoutes: Set<String>,
    tabTapped: Boolean = false,
): Boolean = !tabTapped && if (isPop) from !in tabRoutes else to !in tabRoutes

/**
 * The move the latest bottom-bar tap made, as the ids of the back stack
 * entries it went from and to. Navigation Compose reports a tap on the first
 * tab from a pushed screen as a pop — popUpTo removes the screens above the
 * tab and nothing is pushed — so this record is what tells that tap apart
 * from a back. No back can repeat a recorded move: the entry a tap leaves is
 * either popped by it or left beneath the entry it lands on.
 */
internal class TabTap {
    private var move: Pair<String, String>? = null

    fun record(fromId: String?, toId: String?) {
        move = if (fromId != null && toId != null) fromId to toId else null
    }

    fun made(fromId: String, toId: String): Boolean = move == (fromId to toId)
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.slides(
    isPop: Boolean,
    tabRoutes: Set<String>,
    tabTap: TabTap,
) = slidesBetween(
    initialState.destination.route,
    targetState.destination.route,
    isPop,
    tabRoutes,
    tabTapped = tabTap.made(initialState.id, targetState.id),
)

/** The opened screen comes in from the end edge. */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.pushEnter(tabRoutes: Set<String>, tabTap: TabTap): EnterTransition =
    if (slides(isPop = false, tabRoutes, tabTap)) slideIntoContainer(SlideDirection.Start, slideSpec) else fadeIn(tween(150))

/** The screen underneath drifts a quarter width towards the start. */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.pushExit(tabRoutes: Set<String>, tabTap: TabTap): ExitTransition =
    if (slides(isPop = false, tabRoutes, tabTap)) slideOutOfContainer(SlideDirection.Start, slideSpec) { it / 4 } else fadeOut(tween(100))

/** The screen underneath drifts back from a quarter width out. */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnter(tabRoutes: Set<String>, tabTap: TabTap): EnterTransition =
    if (slides(isPop = true, tabRoutes, tabTap)) slideIntoContainer(SlideDirection.End, slideSpec) { it / 4 } else fadeIn(tween(150))

/** The closed screen leaves by the end edge; a back swipe drags it there. */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(tabRoutes: Set<String>, tabTap: TabTap): ExitTransition =
    if (slides(isPop = true, tabRoutes, tabTap)) slideOutOfContainer(SlideDirection.End, slideSpec) else fadeOut(tween(100))

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
