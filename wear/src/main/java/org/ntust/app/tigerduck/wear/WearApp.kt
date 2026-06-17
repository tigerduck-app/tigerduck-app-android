package org.ntust.app.tigerduck.wear

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.HorizontalPageIndicator
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import org.ntust.app.tigerduck.wear.data.SchedulePersistence
import org.ntust.app.tigerduck.wear.data.SchedulePersistenceHolder
import org.ntust.app.tigerduck.wear.data.SyncRequester
import org.ntust.app.tigerduck.wear.data.WatchSnapshot
import org.ntust.app.tigerduck.wear.ui.CourseDetailScreen
import org.ntust.app.tigerduck.wear.ui.EmptyStateMessage
import org.ntust.app.tigerduck.wear.ui.LibraryQRScreen
import org.ntust.app.tigerduck.wear.ui.NowNextScreen
import org.ntust.app.tigerduck.wear.ui.PaddingSettingsScreen
import org.ntust.app.tigerduck.wear.ui.QRPaddingSettingsScreen
import org.ntust.app.tigerduck.wear.ui.SettingsListScreen
import org.ntust.app.tigerduck.wear.ui.TodayScreen
import org.ntust.app.tigerduck.wear.ui.theme.WearTheme
import org.ntust.app.tigerduck.wear.ui.theme.parseAccent

@Composable
fun WearApp() {
    val context = LocalContext.current
    val repo = SchedulePersistenceHolder.get(context)
    val snapshot by repo.flow.collectAsState(
        initial = WatchSnapshot(emptyList(), SchedulePersistence.DEFAULT_ACCENT, null, false, null)
    )
    val paddingDp by repo.paddingDpFlow.collectAsState(initial = SchedulePersistence.DEFAULT_PADDING_DP)

    LaunchedEffect(Unit) {
        // collectAsState seeds `snapshot` with an empty initial value before
        // the DataStore emits, so reading it here would always look like a
        // never-synced state and bypass SyncRequester's 10-minute guard.
        // Pull the first real DataStore emission instead.
        val firstSnapshot = repo.flow.first()
        SyncRequester.maybeRequest(context, firstSnapshot)
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
                    // Library QR is page 0 (the leftmost page) so a quick right-
                    // swipe from the home view surfaces the scannable code. The
                    // launch default stays Now & Next via initialPage = 1 so
                    // existing users don't see their home view shift.
                    val pagerState = rememberPagerState(initialPage = 1, pageCount = { 4 })
                    HorizontalPagerScaffold(
                        pagerState = pagerState,
                        pageIndicator = {
                            // Show the dots briefly on launch and whenever the
                            // user scrolls between pages, then fade them out
                            // after a beat of inactivity — keeps a stationary
                            // screen (e.g. the library QR) free of UI chrome.
                            var visible by remember { mutableStateOf(true) }
                            LaunchedEffect(pagerState) {
                                snapshotFlow {
                                    pagerState.isScrollInProgress to pagerState.currentPage
                                }.collectLatest { (scrolling, _) ->
                                    visible = true
                                    if (!scrolling) {
                                        delay(INDICATOR_HIDE_DELAY_MS)
                                        visible = false
                                    }
                                }
                            }
                            AnimatedVisibility(
                                visible = visible,
                                enter = fadeIn(),
                                exit = fadeOut(),
                            ) {
                                HorizontalPageIndicator(pagerState = pagerState)
                            }
                        },
                    ) {
                        HorizontalPager(state = pagerState) { page ->
                            when (page) {
                                0 -> LibraryQRScreen()
                                1 -> NowNextScreen(snapshot)
                                2 -> TodayScreen(
                                    snapshot = snapshot,
                                    onRowClick = { courseNo -> navController.navigate("detail/$courseNo") },
                                )

                                3 -> SettingsListScreen(
                                    snapshot = snapshot,
                                    onPaddingClick = { navController.navigate("settings/padding") },
                                    onQrPaddingClick = { navController.navigate("settings/qr_padding") },
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
                composable("settings/qr_padding") {
                    QRPaddingSettingsScreen(onBack = { navController.popBackStack() })
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

private const val INDICATOR_HIDE_DELAY_MS = 1500L
