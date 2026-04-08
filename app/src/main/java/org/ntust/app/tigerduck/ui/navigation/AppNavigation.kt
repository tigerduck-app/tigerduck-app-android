package org.ntust.app.tigerduck.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.screen.announcements.AnnouncementsScreen
import org.ntust.app.tigerduck.ui.screen.calendar.CalendarScreen
import org.ntust.app.tigerduck.ui.screen.classtable.ClassTableScreen
import org.ntust.app.tigerduck.ui.screen.home.HomeScreen
import org.ntust.app.tigerduck.ui.screen.library.LibraryScreen
import org.ntust.app.tigerduck.ui.screen.more.MoreScreen
import org.ntust.app.tigerduck.ui.screen.onboarding.OnboardingScreen
import org.ntust.app.tigerduck.ui.screen.settings.SettingsScreen
import org.ntust.app.tigerduck.ui.screen.settings.TabEditorScreen

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object ClassTable : Screen("classTable")
    object Calendar : Screen("calendar")
    object Announcements : Screen("announcements")
    object Library : Screen("library")
    object More : Screen("more")
    object Settings : Screen("settings")
    object TabEditor : Screen("tabEditor")
}

@Composable
fun AppNavigation(appState: AppState) {
    if (!appState.hasCompletedOnboarding) {
        OnboardingScreen()
    } else {
        MainNavigation(appState)
    }
}

@Composable
fun MainNavigation(appState: AppState) {
    val navController = rememberNavController()
    val configuredTabs by remember {
        derivedStateOf {
            appState.configuredTabs.filter { feature ->
                !feature.isLibraryRelated || appState.libraryFeatureEnabled
            }
        }
    }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val selectedTabRoute = when (currentRoute) {
        Screen.Settings.route, Screen.TabEditor.route -> Screen.More.route
        else -> currentRoute
    }

    val bottomItems = configuredTabs + listOf(AppFeature.MORE)
    val startDest = remember { configuredTabs.firstOrNull()?.toRoute() ?: Screen.Home.route }

    Scaffold(
        bottomBar = {
            NavigationBar {
                bottomItems.forEach { feature ->
                    val route = feature.toRoute()
                    NavigationBarItem(
                        icon = { Icon(feature.icon, contentDescription = feature.displayName) },
                        label = { Text(feature.displayName) },
                        selected = selectedTabRoute == route,
                        onClick = {
                            navController.navigate(route) {
                                popUpTo(startDest) {
                                    inclusive = false
                                }
                                launchSingleTop = true
                                restoreState = route != Screen.More.route
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDest,
            modifier = Modifier.padding(innerPadding),
            enterTransition = { fadeIn(tween(150)) },
            exitTransition = { fadeOut(tween(100)) },
            popEnterTransition = { fadeIn(tween(150)) },
            popExitTransition = { fadeOut(tween(100)) }
        ) {
            composable(Screen.Home.route) { HomeScreen(appState = appState) }
            composable(Screen.ClassTable.route) { ClassTableScreen() }
            composable(Screen.Calendar.route) { CalendarScreen() }
            composable(Screen.Announcements.route) { PlaceholderScreen(AppFeature.ANNOUNCEMENTS) }
            composable(Screen.Library.route) { LibraryScreen() }
            composable(Screen.More.route) { MoreScreen(navController, appState) }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateToTabEditor = { navController.navigate(Screen.TabEditor.route) }
                )
            }
            composable(Screen.TabEditor.route) {
                TabEditorScreen(
                    appState = appState,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("placeholder/{feature}",
                arguments = listOf(navArgument("feature") { type = NavType.StringType })
            ) { backStackEntry ->
                val featureId = backStackEntry.arguments?.getString("feature") ?: ""
                PlaceholderScreen(AppFeature.fromId(featureId))
            }
        }
    }
}

fun AppFeature.toRoute(): String = when (this) {
    AppFeature.HOME -> Screen.Home.route
    AppFeature.CLASS_TABLE -> Screen.ClassTable.route
    AppFeature.CALENDAR -> Screen.Calendar.route
    AppFeature.ANNOUNCEMENTS -> Screen.Announcements.route
    AppFeature.LIBRARY -> Screen.Library.route
    AppFeature.MORE -> Screen.More.route
    AppFeature.SETTINGS -> Screen.Settings.route
    else -> "placeholder/$id"
}
