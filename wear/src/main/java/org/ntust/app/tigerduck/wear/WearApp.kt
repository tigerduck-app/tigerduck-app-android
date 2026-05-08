package org.ntust.app.tigerduck.wear

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
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

@OptIn(ExperimentalFoundationApi::class)
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
        SwipeDismissableNavHost(
            navController = navController,
            startDestination = "pager",
        ) {
            composable("pager") {
                val pagerState = rememberPagerState(pageCount = { 2 })
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
            composable("detail/{courseNo}") { entry ->
                val no = entry.arguments?.getString("courseNo")
                val course = snapshot.courses.firstOrNull { it.courseNo == no }
                if (course != null) CourseDetailScreen(course) else EmptyStateMessage("Not found")
            }
        }
    }
}
