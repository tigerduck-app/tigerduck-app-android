package org.ntust.app.tigerduck.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
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

sealed class Screen(val route: String) {
    object Onboarding : Screen("onboarding")
    object Main : Screen("main")
    object Home : Screen("home")
    object ClassTable : Screen("classTable")
    object Calendar : Screen("calendar")
    object Announcements : Screen("announcements")
    object Library : Screen("library")
    object More : Screen("more")
    object Settings : Screen("settings")
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
    val configuredTabs by remember(appState.configuredTabs) { mutableStateOf(appState.configuredTabs) }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val bottomItems = configuredTabs + listOf(AppFeature.MORE)

    Scaffold(
        bottomBar = {
            NavigationBar {
                bottomItems.forEach { feature ->
                    val route = feature.toRoute()
                    NavigationBarItem(
                        icon = { Icon(feature.icon, contentDescription = feature.displayName) },
                        label = { Text(feature.displayName) },
                        selected = currentRoute == route,
                        onClick = {
                            navController.navigate(route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = configuredTabs.firstOrNull()?.toRoute() ?: Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) { HomeScreen() }
            composable(Screen.ClassTable.route) { ClassTableScreen() }
            composable(Screen.Calendar.route) { CalendarScreen() }
            composable(Screen.Announcements.route) { AnnouncementsScreen(navController) }
            composable(Screen.Library.route) { LibraryScreen() }
            composable(Screen.More.route) { MoreScreen(navController) }
            composable(Screen.Settings.route) { SettingsScreen() }
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
