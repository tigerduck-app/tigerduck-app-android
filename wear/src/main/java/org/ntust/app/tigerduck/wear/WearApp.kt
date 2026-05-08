package org.ntust.app.tigerduck.wear

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import org.ntust.app.tigerduck.wear.data.ScheduleRepository
import org.ntust.app.tigerduck.wear.data.SchedulePersistence
import org.ntust.app.tigerduck.wear.data.SyncRequester
import org.ntust.app.tigerduck.wear.data.WatchSnapshot
import org.ntust.app.tigerduck.wear.ui.CourseDetailScreen
import org.ntust.app.tigerduck.wear.ui.EmptyStateMessage
import org.ntust.app.tigerduck.wear.ui.NowNextScreen
import org.ntust.app.tigerduck.wear.ui.PaddingSettingsScreen
import org.ntust.app.tigerduck.wear.ui.SettingsListScreen
import org.ntust.app.tigerduck.wear.ui.TodayScreen
import org.ntust.app.tigerduck.wear.ui.theme.WearTheme
import org.ntust.app.tigerduck.wear.ui.theme.parseAccent

@Composable
fun WearApp() {
    val context = LocalContext.current
    val repo = ScheduleRepository.get(context)
    val snapshot by repo.flow.collectAsState(
        initial = WatchSnapshot(emptyList(), SchedulePersistence.DEFAULT_ACCENT, null, false, null)
    )
    val paddingDp by repo.paddingDpFlow.collectAsState(initial = SchedulePersistence.DEFAULT_PADDING_DP)

    LaunchedEffect(Unit) {
        SyncRequester.maybeRequest(context, snapshot)
    }

    // Re-apply the phone's chosen locale when it changes mid-session.
    // attachBaseContext on the next instance will read the persisted tag and
    // apply it; recreating the activity is the cleanest way to swap locale
    // without rebuilding compose state by hand.
    val activity = context.findActivity()
    LaunchedEffect(snapshot.languageTag) {
        val tag = snapshot.languageTag ?: return@LaunchedEffect
        val current = context.resources.configuration.locales[0]?.toLanguageTag() ?: ""
        if (current.equals(tag, ignoreCase = true)) return@LaunchedEffect
        activity?.recreate()
    }

    val navController = rememberSwipeDismissableNavController()

    WearTheme(accent = parseAccent(snapshot.accentHex), paddingDp = paddingDp) {
        AppScaffold {
            SwipeDismissableNavHost(
                navController = navController,
                startDestination = "pager",
            ) {
                composable("pager") {
                    val pagerState = rememberPagerState(pageCount = { 3 })
                    HorizontalPagerScaffold(pagerState = pagerState) {
                        HorizontalPager(state = pagerState) { page ->
                            when (page) {
                                0 -> NowNextScreen(snapshot)
                                1 -> TodayScreen(
                                    snapshot = snapshot,
                                    onRowClick = { courseNo -> navController.navigate("detail/$courseNo") },
                                )
                                2 -> SettingsListScreen(
                                    onPaddingClick = { navController.navigate("settings/padding") },
                                )
                            }
                        }
                    }
                }
                composable("detail/{courseNo}") { entry ->
                    val no = entry.arguments?.getString("courseNo")
                    val course = snapshot.courses.firstOrNull { it.courseNo == no }
                    if (course != null) CourseDetailScreen(course)
                    else EmptyStateMessage(stringResource(R.string.watch_not_found))
                }
                composable("settings/padding") {
                    PaddingSettingsScreen(onBack = { navController.popBackStack() })
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
