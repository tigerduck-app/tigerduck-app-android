package org.ntust.app.tigerduck.ui.navigation

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.AppConstants
import java.time.Instant
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.widget.LibraryShortcutWidget
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.component.PermissionWarningDialogHost
import org.ntust.app.tigerduck.ui.screen.calendar.CalendarScreen
import org.ntust.app.tigerduck.ui.screen.calendar.CalendarViewModel
import org.ntust.app.tigerduck.ui.screen.classtable.ClassTableScreen
import org.ntust.app.tigerduck.ui.screen.classtable.ClassTableViewModel
import org.ntust.app.tigerduck.ui.screen.home.HomeScreen
import org.ntust.app.tigerduck.ui.screen.home.HomeViewModel
import org.ntust.app.tigerduck.ui.screen.library.LibraryScreen
import org.ntust.app.tigerduck.ui.screen.more.MoreScreen
import org.ntust.app.tigerduck.ui.screen.onboarding.OnboardingScreen
import org.ntust.app.tigerduck.ui.screen.score.ScoreScreen
import org.ntust.app.tigerduck.ui.screen.settings.LiveActivitySettingsScreen
import org.ntust.app.tigerduck.ui.screen.settings.LanguagePickerScreen
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
    object LanguagePicker : Screen("languagePicker")
    object LiveActivitySettings : Screen("liveActivitySettings")
    object NotificationSetup : Screen("notificationSetup")
}

@Composable
fun AppNavigation(appState: AppState, widgetStartRoute: String? = null) {
    if (!appState.hasCompletedOnboarding) {
        OnboardingScreen()
    } else {
        MainNavigation(appState, widgetStartRoute)
        PermissionWarningDialogHost(appState.systemPermissions)
    }

    val needsReset by appState.needsUserReset.collectAsStateWithLifecycle()
    if (needsReset) {
        // Non-dismissable: the app is in an unrecoverable data state, so the
        // only way forward is to reset and walk through onboarding again.
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.app_reset_required_title)) },
            text = {
                Text(stringResource(R.string.app_reset_required_message))
            },
            confirmButton = {
                TextButton(onClick = { appState.performFullReset() }) {
                    Text(stringResource(R.string.app_reset_required_action))
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
fun MainNavigation(appState: AppState, widgetStartRoute: String? = null) {
    val navController = rememberNavController()
    LaunchedEffect(widgetStartRoute) {
        widgetStartRoute ?: return@LaunchedEffect
        // The library-shortcut widget emits a sentinel instead of a direct
        // route so the feature gate is re-evaluated at tap time. If library
        // has been turned off since the widget was placed, reroute to
        // Settings and raise the "enable first" prompt.
        val target = if (widgetStartRoute == LibraryShortcutWidget.ROUTE_SENTINEL) {
            if (appState.libraryFeatureEnabled) {
                Screen.Library.route
            } else {
                appState.pendingLibraryEnablePrompt = true
                Screen.Settings.route
            }
        } else {
            widgetStartRoute
        }
        navController.navigate(target) {
            launchSingleTop = true
        }
    }
    // Hoist Home / ClassTable / Calendar VMs to the activity scope so they
    // exist from app open and survive tab switches. load() is called once
    // here on first composition; the per-screen LaunchedEffect that also
    // calls load() becomes a no-op via the VM's hasLoaded guard.
    val homeViewModel: HomeViewModel = hiltViewModel()
    val classTableViewModel: ClassTableViewModel = hiltViewModel()
    val calendarViewModel: CalendarViewModel = hiltViewModel()
    LaunchedEffect(Unit) {
        homeViewModel.load()
        classTableViewModel.load()
        calendarViewModel.load()
    }
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

    val context = LocalContext.current
    val backPressExitHint = stringResource(R.string.app_exit_confirm_toast)
    val nonTaipeiTimezoneHint = stringResource(R.string.app_non_taipei_timezone_hint)
    val bottomItems = configuredTabs + listOf(AppFeature.MORE)
    // NavHost startDestination must not change mid-session, so freeze it on
    // first composition. popUpTo, in contrast, needs the *current* first tab
    // so reordering via TabEditor doesn't pop to a removed route.
    val startDest = remember { configuredTabs.firstOrNull()?.toRoute() ?: Screen.Home.route }
    val popUpToDest = configuredTabs.firstOrNull()?.toRoute() ?: Screen.Home.route

    // Two-press-to-exit on the leftmost tab. Deeper BackHandlers (e.g., Home
    // edit mode) register later in composition and still win. startDest is
    // frozen per session (NavHost can't change it) while popUpToDest tracks
    // the current first tab; after a TabEditor reorder they diverge, so gate
    // on either so the frozen start destination keeps the guard.
    var lastBackPressMs by remember { mutableLongStateOf(0L) }
    BackHandler(enabled = currentRoute == popUpToDest || currentRoute == startDest) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBackPressMs < 2000L) {
            (context as? Activity)?.finish()
        } else {
            lastBackPressMs = now
            Toast.makeText(context, backPressExitHint, Toast.LENGTH_SHORT).show()
        }
    }

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
                        text = nonTaipeiTimezoneHint,
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
                        icon = { Icon(feature.icon, contentDescription = stringResource(feature.displayNameRes)) },
                        label = {
                            Text(
                                text = stringResource(feature.displayNameRes),
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        alwaysShowLabel = true,
                        selected = selectedTabRoute == route,
                        onClick = {
                            if (currentRoute == route) return@NavigationBarItem
                            performTabSwitchHaptic(context)
                            navController.navigate(route) {
                                popUpTo(popUpToDest) {
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
            composable(Screen.Home.route) {
                HomeScreen(appState = appState, viewModel = homeViewModel)
            }
            composable(Screen.ClassTable.route) {
                ClassTableScreen(viewModel = classTableViewModel)
            }
            composable(Screen.Calendar.route) {
                CalendarScreen(viewModel = calendarViewModel)
            }
            composable(Screen.Announcements.route) { PlaceholderScreen(AppFeature.ANNOUNCEMENTS) }
            composable(Screen.Library.route) { LibraryScreen() }
            composable(Screen.Score.route) { ScoreScreen() }
            composable(Screen.More.route) { MoreScreen(navController, appState) }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateToTabEditor = { navController.navigate(Screen.TabEditor.route) },
                    onNavigateToLanguagePicker = { navController.navigate(Screen.LanguagePicker.route) },
                    onNavigateToLiveActivity = { navController.navigate(Screen.LiveActivitySettings.route) },
                    onNavigateToNotificationSetup = { navController.navigate(Screen.NotificationSetup.route) },
                )
            }
            composable(Screen.LanguagePicker.route) {
                LanguagePickerScreen(onBack = { navController.popBackStack() })
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

// Intentionally lighter than the old HapticFeedbackType.TextHandleMove
// default but still a notch heavier than 時光機's PRIMITIVE_TICK @ 0.6 so the
// tab switch remains distinct from the slider drag.
private fun performTabSwitchHaptic(context: Context) {
    try {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)
        ) {
            val effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.75f)
                .compose()
            vibrator.vibrate(effect)
        } else {
            val amp = if (vibrator.hasAmplitudeControl()) 180 else VibrationEffect.DEFAULT_AMPLITUDE
            vibrator.vibrate(VibrationEffect.createOneShot(14, amp))
        }
    } catch (_: Exception) { }
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
