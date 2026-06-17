package org.ntust.app.tigerduck.ui.navigation

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.ntust.app.tigerduck.analytics.AnalyticsLogger
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.screen.announcements.AnnouncementDetailScreen
import org.ntust.app.tigerduck.ui.screen.announcements.AnnouncementsScreen
import org.ntust.app.tigerduck.ui.screen.announcements.SubscriptionSettingsScreen
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.component.PermissionWarningDialogHost
import org.ntust.app.tigerduck.ui.component.TigerDuckDialog
import org.ntust.app.tigerduck.ui.haptics.HapticScenario
import org.ntust.app.tigerduck.ui.haptics.Haptics
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
import org.ntust.app.tigerduck.ui.screen.settings.LanguagePickerScreen
import org.ntust.app.tigerduck.ui.screen.settings.AssignmentReminderSettingsScreen
import org.ntust.app.tigerduck.ui.screen.settings.CourseNameSizeSettingsScreen
import org.ntust.app.tigerduck.ui.screen.settings.LiveActivitySettingsScreen
import org.ntust.app.tigerduck.ui.screen.settings.NotificationSetupScreen
import org.ntust.app.tigerduck.ui.screen.settings.OtherSettingsScreen
import org.ntust.app.tigerduck.ui.screen.settings.ServerPushScreen
import org.ntust.app.tigerduck.ui.screen.settings.SettingsScreen
import org.ntust.app.tigerduck.ui.screen.settings.SourceCodePickerScreen
import org.ntust.app.tigerduck.ui.screen.settings.TabEditorScreen
import org.ntust.app.tigerduck.ui.screen.settings.VibrationSettingsScreen
import org.ntust.app.tigerduck.widget.LibraryShortcutWidget

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object ClassTable : Screen("classTable")
    object Calendar : Screen("calendar")
    object Announcements : Screen("announcements")
    object AnnouncementDetail : Screen("announcements/detail/{id}") {
        fun route(id: Int) = "announcements/detail/$id"
    }

    object AnnouncementSubscriptions : Screen("announcements/subscriptions")
    object Library : Screen("library")
    object Score : Screen("score")
    object More : Screen("more")
    object Settings : Screen("settings")
    object TabEditor : Screen("tabEditor")
    object LanguagePicker : Screen("languagePicker")
    object LiveActivitySettings : Screen("liveActivitySettings")
    object AssignmentReminderSettings : Screen("assignmentReminderSettings")
    object NotificationSetup : Screen("notificationSetup")
    object SourceCodePicker : Screen("sourceCodePicker")
    object OtherSettings : Screen("otherSettings")
    object CourseNameSizeSettings : Screen("courseNameSizeSettings")
    object ServerPush : Screen("serverPush")
    object VibrationSettings : Screen("vibrationSettings")
    object Debug : Screen("debug")
    object NotificationDebug : Screen("notificationDebug")
    object ApiEndpointDebug : Screen("apiEndpointDebug")
    object TriggersDebug : Screen("triggersDebug")
}

@Composable
fun AppNavigation(
    appState: AppState,
    analyticsLogger: AnalyticsLogger,
    widgetStartRoute: String? = null,
    onStartRouteConsumed: () -> Unit = {},
) {
    if (!appState.hasCompletedOnboarding) {
        OnboardingScreen()
    } else {
        MainNavigation(
            appState = appState,
            analyticsLogger = analyticsLogger,
            widgetStartRoute = widgetStartRoute,
            onStartRouteConsumed = onStartRouteConsumed,
        )
        PermissionWarningDialogHost(appState.systemPermissions)
    }

    val needsReset by appState.needsUserReset.collectAsStateWithLifecycle()
    if (needsReset) {
        // Non-dismissable: the app is in an unrecoverable data state, so the
        // only way forward is to reset and walk through onboarding again.
        TigerDuckDialog(
            onDismissRequest = {},
            dismissable = false,
            title = stringResource(R.string.app_reset_required_title),
            message = stringResource(R.string.app_reset_required_message),
            confirmText = stringResource(R.string.app_reset_required_action),
            onConfirm = { appState.performFullReset() },
        )
    }
}

@Composable
fun MainNavigation(
    appState: AppState,
    analyticsLogger: AnalyticsLogger,
    widgetStartRoute: String? = null,
    onStartRouteConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    FlipToLibraryEffect(navController = navController, appState = appState)
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
        // Cold-start path: this effect can fire in the same composition that
        // first declares the NavHost (further down). navigate() before the
        // graph is registered silently no-ops, so block on the first back-stack
        // entry — which only appears once NavHost has installed its graph and
        // routed to startDestination.
        snapshotFlow { navController.currentBackStackEntry }
            .filterNotNull()
            .first()
        navController.navigate(target) {
            launchSingleTop = true
        }
        // Consume so that re-tapping the same notification (which delivers the
        // identical route string) re-fires this LaunchedEffect instead of
        // being deduped by the same key.
        onStartRouteConsumed()
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
    LaunchedEffect(currentRoute) {
        val route = currentRoute ?: return@LaunchedEffect
        val screenName = route.substringBefore("/")
        analyticsLogger.log("screen_view", mapOf("screen_name" to screenName))
    }
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

    // Reactive: a frozen `remember {}` would miss timezone changes while the
    // app is backgrounded. Listen for ACTION_TIMEZONE_CHANGED so the banner
    // appears/clears when the user travels.
    var isNonTaipeiTz by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val recompute = {
            val now = AppClock.instant()
            isNonTaipeiTz = java.util.TimeZone.getDefault().getOffset(now.toEpochMilli()) !=
                    AppConstants.TAIPEI_TZ.getOffset(now.toEpochMilli())
        }
        recompute()
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: android.content.Intent?) {
                recompute()
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            android.content.IntentFilter(android.content.Intent.ACTION_TIMEZONE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
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
                            icon = {
                                Icon(
                                    feature.icon,
                                    contentDescription = stringResource(feature.displayNameRes)
                                )
                            },
                            label = {
                                Text(
                                    text = stringResource(feature.shortDisplayNameRes),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall
                                        .copy(fontSize = 11.sp),
                                )
                            },
                            alwaysShowLabel = true,
                            selected = selectedTabRoute == route,
                            onClick = {
                                if (currentRoute == route) return@NavigationBarItem
                                Haptics.perform(
                                    context,
                                    HapticScenario.TabSwitch,
                                )
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
            // Shared by every screen that renders the signed-out lock empty
            // state: tapping the lock should land the user on Settings with
            // the NTUST account row pulsing. Defined once here so each
            // screen needs only a single callback parameter.
            val openSignInSettings: () -> Unit = {
                appState.pendingNtustSignInHighlight = true
                navController.navigate(Screen.Settings.route)
            }
            composable(Screen.Home.route) {
                HomeScreen(
                    appState = appState,
                    viewModel = homeViewModel,
                    onOpenSignInSettings = openSignInSettings,
                )
            }
            composable(Screen.ClassTable.route) {
                ClassTableScreen(
                    viewModel = classTableViewModel,
                    onOpenSignInSettings = openSignInSettings,
                )
            }
            composable(Screen.Calendar.route) {
                CalendarScreen(
                    viewModel = calendarViewModel,
                    onOpenSignInSettings = openSignInSettings,
                )
            }
            composable(Screen.Announcements.route) {
                AnnouncementsScreen(
                    onOpenBulletin = { id ->
                        navController.navigate(Screen.AnnouncementDetail.route(id))
                    },
                    onOpenSubscriptions = {
                        navController.navigate(Screen.AnnouncementSubscriptions.route)
                    },
                )
            }
            composable(
                Screen.AnnouncementDetail.route,
                arguments = listOf(navArgument("id") { type = NavType.IntType }),
            ) {
                AnnouncementDetailScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.AnnouncementSubscriptions.route) {
                SubscriptionSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.Library.route) { LibraryScreen() }
            composable(Screen.Score.route) {
                ScoreScreen(onOpenSignInSettings = openSignInSettings)
            }
            composable(Screen.More.route) { MoreScreen(navController, appState) }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateToTabEditor = { navController.navigate(Screen.TabEditor.route) },
                    onNavigateToLanguagePicker = { navController.navigate(Screen.LanguagePicker.route) },
                    onNavigateToLiveActivity = { navController.navigate(Screen.LiveActivitySettings.route) },
                    onNavigateToAssignmentReminders = { navController.navigate(Screen.AssignmentReminderSettings.route) },
                    onNavigateToServerPush = { navController.navigate(Screen.ServerPush.route) },
                    onNavigateToOtherSettings = { navController.navigate(Screen.OtherSettings.route) },
                    // Debug-route navigation is no-op in release builds:
                    // the composables themselves are registered only inside
                    // the `if (BuildConfig.DEBUG)` block below, so an
                    // unguarded call here would throw IllegalArgumentException
                    // ('destination cannot be found') if the SettingsScreen
                    // row gating ever drifts.
                    onNavigateToDebug = {
                        if (BuildConfig.DEBUG) navController.navigate(Screen.Debug.route)
                    },
                    onNavigateToNotificationDebug = {
                        if (BuildConfig.DEBUG) navController.navigate(Screen.NotificationDebug.route)
                    },
                    onNavigateToApiEndpointDebug = {
                        if (BuildConfig.DEBUG) navController.navigate(Screen.ApiEndpointDebug.route)
                    },
                    onNavigateToTriggersDebug = {
                        if (BuildConfig.DEBUG) navController.navigate(Screen.TriggersDebug.route)
                    },
                )
            }
            if (BuildConfig.DEBUG) {
                composable(Screen.Debug.route) {
                    org.ntust.app.tigerduck.ui.screen.debug.DebugScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Screen.NotificationDebug.route) {
                    org.ntust.app.tigerduck.ui.screen.debug.NotificationDebugScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Screen.ApiEndpointDebug.route) {
                    org.ntust.app.tigerduck.ui.screen.debug.ApiEndpointDebugScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Screen.TriggersDebug.route) {
                    org.ntust.app.tigerduck.ui.screen.debug.TriggersDebugScreen(
                        appState = appState,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            composable(Screen.OtherSettings.route) {
                OtherSettingsScreen(
                    onBack = { navController.popBackStack() },
                    onNavigateToNotificationSetup = { navController.navigate(Screen.NotificationSetup.route) },
                    onNavigateToSourceCode = { navController.navigate(Screen.SourceCodePicker.route) },
                    onNavigateToVibration = { navController.navigate(Screen.VibrationSettings.route) },
                    onNavigateToCourseNameSize = { navController.navigate(Screen.CourseNameSizeSettings.route) },
                )
            }
            composable(Screen.VibrationSettings.route) {
                VibrationSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.CourseNameSizeSettings.route) {
                CourseNameSizeSettingsScreen(onBack = { navController.popBackStack() })
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
            composable(Screen.AssignmentReminderSettings.route) {
                AssignmentReminderSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.ServerPush.route) {
                ServerPushScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.SourceCodePicker.route) {
                SourceCodePickerScreen(onBack = { navController.popBackStack() })
            }
            composable(
                "placeholder/{feature}",
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
