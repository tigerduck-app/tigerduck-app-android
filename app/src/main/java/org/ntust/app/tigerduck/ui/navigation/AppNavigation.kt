package org.ntust.app.tigerduck.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.AppConstants
import java.time.Instant
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.component.PermissionWarningDialogHost
import org.ntust.app.tigerduck.ui.screen.calendar.CalendarScreen
import org.ntust.app.tigerduck.ui.screen.classtable.ClassTableScreen
import org.ntust.app.tigerduck.ui.screen.home.HomeScreen
import org.ntust.app.tigerduck.ui.screen.library.LibraryScreen
import org.ntust.app.tigerduck.ui.screen.more.MoreScreen
import org.ntust.app.tigerduck.ui.screen.onboarding.OnboardingScreen
import org.ntust.app.tigerduck.ui.screen.score.ScoreScreen
import org.ntust.app.tigerduck.ui.screen.settings.LiveActivitySettingsScreen
import org.ntust.app.tigerduck.ui.screen.settings.NotificationSetupScreen
import org.ntust.app.tigerduck.ui.screen.settings.SettingsScreen
import org.ntust.app.tigerduck.ui.screen.settings.TabEditorScreen

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object ClassTable : Screen("classTable")
    object Calendar : Screen("calendar")
    object Announcements : Screen("announcements")
    object Library : Screen("library")
    object Score : Screen("score")
    object More : Screen("more")
    object Settings : Screen("settings")
    object TabEditor : Screen("tabEditor")
    object LiveActivitySettings : Screen("liveActivitySettings")
    object NotificationSetup : Screen("notificationSetup")
}

@Composable
fun AppNavigation(appState: AppState) {
    if (!appState.hasCompletedOnboarding) {
        OnboardingScreen()
    } else {
        MainNavigation(appState)
        PermissionWarningDialogHost(appState.systemPermissions)
    }

    val needsReset by appState.needsUserReset.collectAsStateWithLifecycle()
    if (needsReset) {
        // Non-dismissable: the app is in an unrecoverable data state, so the
        // only way forward is to reset and walk through onboarding again.
        AlertDialog(
            onDismissRequest = {},
            title = { Text("需要重新設定") },
            text = {
                Text(
                    "您的登入狀態與偏好設定已無法讀取，" +
                        "可能因為裝置升級、備份還原或儲存空間異常。\n\n" +
                        "請點選「重新設定」以清除舊資料，然後重新登入並重新配置偏好。"
                )
            },
            confirmButton = {
                TextButton(onClick = { appState.performFullReset() }) {
                    Text("重新設定")
                }
            },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
        )
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

    val haptic = LocalHapticFeedback.current
    val bottomItems = configuredTabs + listOf(AppFeature.MORE)
    val startDest = remember { configuredTabs.firstOrNull()?.toRoute() ?: Screen.Home.route }

    val isNonTaipeiTz = remember {
        val now = Instant.now()
        java.util.TimeZone.getDefault().getOffset(now.toEpochMilli()) !=
            AppConstants.TAIPEI_TZ.getOffset(now.toEpochMilli())
    }

    Scaffold(
        bottomBar = {
            Column {
                if (isNonTaipeiTz) {
                    Text(
                        text = "您目前不在臺灣時區，此 APP 已自動使用臺灣時區。\n請注意日期與時間，並敬祝您旅途平安！",
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFFFF3B0))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        color = Color(0xFF5C4A00),
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center
                    )
                }
                NavigationBar {
                bottomItems.forEach { feature ->
                    val route = feature.toRoute()
                    NavigationBarItem(
                        icon = { Icon(feature.icon, contentDescription = feature.displayName) },
                        label = { Text(feature.displayName) },
                        selected = selectedTabRoute == route,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
            composable(Screen.Score.route) { ScoreScreen() }
            composable(Screen.More.route) { MoreScreen(navController, appState) }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateToTabEditor = { navController.navigate(Screen.TabEditor.route) },
                    onNavigateToLiveActivity = { navController.navigate(Screen.LiveActivitySettings.route) },
                    onNavigateToNotificationSetup = { navController.navigate(Screen.NotificationSetup.route) },
                )
            }
            composable(Screen.NotificationSetup.route) {
                NotificationSetupScreen(onDone = { navController.popBackStack() })
            }
            composable(Screen.TabEditor.route) {
                TabEditorScreen(
                    appState = appState,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.LiveActivitySettings.route) {
                LiveActivitySettingsScreen(onBack = { navController.popBackStack() })
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
    AppFeature.SCORE -> Screen.Score.route
    AppFeature.MORE -> Screen.More.route
    AppFeature.SETTINGS -> Screen.Settings.route
    else -> "placeholder/$id"
}
