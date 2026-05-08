package org.ntust.app.tigerduck.wear

import androidx.compose.runtime.Composable
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
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
import org.ntust.app.tigerduck.wear.ui.TodayScreen
import org.ntust.app.tigerduck.wear.ui.theme.WearTheme
import org.ntust.app.tigerduck.wear.ui.theme.parseAccent

@Composable
fun WearApp() {
    val context = LocalContext.current
    val snapshot by ScheduleRepository.get(context).flow.collectAsState(
        initial = WatchSnapshot(emptyList(), SchedulePersistence.DEFAULT_ACCENT, null, false)
    )

    LaunchedEffect(Unit) {
        SyncRequester.maybeRequest(context, snapshot)
    }

    val navController = rememberSwipeDismissableNavController()

    WearTheme(accent = parseAccent(snapshot.accentHex)) {
        AppScaffold {
            SwipeDismissableNavHost(
                navController = navController,
                startDestination = "pager",
            ) {
                composable("pager") {
                    val pagerState = rememberPagerState(pageCount = { 2 })
                    HorizontalPagerScaffold(pagerState = pagerState) {
                        HorizontalPager(state = pagerState) { page ->
                            when (page) {
                                0 -> NowNextScreen(snapshot)
                                1 -> TodayScreen(
                                    snapshot = snapshot,
                                    onRowClick = { courseNo -> navController.navigate("detail/$courseNo") },
                                )
                            }
                        }
                    }
                }
                composable("detail/{courseNo}") { entry ->
                    val no = entry.arguments?.getString("courseNo")
                    val course = snapshot.courses.firstOrNull { it.courseNo == no }
                    if (course != null) CourseDetailScreen(course) else EmptyStateMessage("Not found")
                }
            }
        }
    }
}
