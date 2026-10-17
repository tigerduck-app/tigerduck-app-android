// The class table's selected course as iOS presents it: a sheet that opens at
// half height and drags to full, with a navigation of its own, so the Moodle
// screens and a member's mail push inside it and a back goes through them
// before it closes the sheet.

package org.ntust.app.tigerduck.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.ntust.app.tigerduck.mail.ComposeMode
import org.ntust.app.tigerduck.mail.MailRoutes
import org.ntust.app.tigerduck.ui.screen.classtable.ClassTableViewModel
import org.ntust.app.tigerduck.ui.screen.classtable.CourseDetailSheetPage
import org.ntust.app.tigerduck.ui.screen.coursemoodle.MoodleAnnouncementScreen
import org.ntust.app.tigerduck.ui.screen.coursemoodle.MoodleAnnouncementsScreen
import org.ntust.app.tigerduck.ui.screen.coursemoodle.MoodleAssignmentScreen
import org.ntust.app.tigerduck.ui.screen.coursemoodle.MoodleAssignmentsScreen
import org.ntust.app.tigerduck.ui.screen.coursemoodle.MoodleCourseViewModel
import org.ntust.app.tigerduck.ui.screen.coursemoodle.MoodleDiscussionScreen
import org.ntust.app.tigerduck.ui.screen.coursemoodle.MoodleForumScreen
import org.ntust.app.tigerduck.ui.screen.coursemoodle.MoodleForumsScreen
import org.ntust.app.tigerduck.ui.screen.coursemoodle.MoodleGradesScreen
import org.ntust.app.tigerduck.ui.screen.coursemoodle.MoodleMaterialsScreen
import org.ntust.app.tigerduck.ui.screen.coursemoodle.MoodleMemberScreen
import org.ntust.app.tigerduck.ui.screen.coursemoodle.MoodleMembersScreen
import org.ntust.app.tigerduck.ui.screen.coursemoodle.MoodleRoutes
import org.ntust.app.tigerduck.ui.screen.mail.SchoolMailComposeScreen

/** What the sheet does with the routes of its back stack: its graph's, which is null, then its pages'. */
internal object CourseDetailSheetRules {
    /** A page pushed over the course detail is a list or an article, which half the screen would cut. */
    fun expands(routes: List<String?>): Boolean = routes.any { it != null && it != MoodleRoutes.COURSE_DETAIL }

    /** A mail being written closes with its own Save or Discard, never with the sheet under it. */
    fun mayClose(routes: List<String?>): Boolean = MailRoutes.COMPOSE !in routes

    /**
     * Whether a link to [target], from a reminder or the Live Update, closes the sheet before its
     * page opens: an assignment's page, which the sheet would cover again on the way back, unless
     * the sheet holds a mail being written, which stays with the page opened over it, as on iOS.
     */
    fun closesForLink(target: String?, routes: List<String?>): Boolean =
        Screen.Assignment.isRoute(target) && mayClose(routes)
}

/**
 * Holds the view models of the sheet's pages: past the activity being recreated, as for a change
 * of dark mode, which keeps the sheet open, but not past the sheet. A NavHost keeps its pages' view models with the owner around it, and one that
 * leaves the screen without popping them leaves them there, so the class table page's own would
 * keep every closed sheet's.
 */
internal class CourseDetailSheetModels : ViewModel(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    /** Once the sheet has closed: the next one builds a navigation of its own. */
    fun close() = viewModelStore.clear()

    override fun onCleared() = viewModelStore.clear()
}

/** The selected course's sheet, while there is one; closing it clears the selection. */
@Composable
internal fun CourseDetailSheetHost(classTable: ClassTableViewModel, browserPreference: String) {
    val models: CourseDetailSheetModels = viewModel()
    val selected by classTable.selectedCourse.collectAsStateWithLifecycle()
    val open = selected != null
    LaunchedEffect(open) { if (!open) models.close() }
    if (open) {
        CourseDetailSheet(classTable, browserPreference, models, onClosed = classTable::clearSelection)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CourseDetailSheet(
    classTable: ClassTableViewModel,
    browserPreference: String,
    models: CourseDetailSheetModels,
    onClosed: () -> Unit,
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStack.collectAsStateWithLifecycle()
    val routes = backStack.map { it.destination.route }
    val mayClose = rememberUpdatedState(CourseDetailSheetRules.mayClose(routes))
    // Told to the class table, which a link's arrival asks whether the sheet may close first.
    LaunchedEffect(routes) { classTable.setCourseSheetRoutes(routes) }
    DisposableEffect(classTable) { onDispose { classTable.setCourseSheetRoutes(emptyList()) } }
    // Remembered once: the sheet state is remembered against this lambda.
    val confirmValueChange = remember { { value: SheetValue -> value != SheetValue.Hidden || mayClose.value } }
    val sheetState = rememberModalBottomSheetState(confirmValueChange = confirmValueChange)
    // On each move while a page is pushed, as iOS takes its sheet to the large detent whenever its
    // path changes.
    LaunchedEffect(routes.size) {
        if (CourseDetailSheetRules.expands(routes)) sheetState.expand()
    }
    val tabTap = remember { TabTap() }
    val noTabs = emptySet<String>()

    ModalBottomSheet(
        onDismissRequest = onClosed,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        CompositionLocalProvider(LocalViewModelStoreOwner provides models) {
            NavHost(
                navController = navController,
                startDestination = MoodleRoutes.COURSE_DETAIL,
                // The full height whatever the page, so the sheet opens at half the screen as iOS's
                // medium detent does, and a short page does not shrink it.
                modifier = Modifier.fillMaxSize(),
                enterTransition = { pushEnter(noTabs, tabTap) },
                exitTransition = { pushExit(noTabs, tabTap) },
                popEnterTransition = { popEnter(noTabs, tabTap) },
                popExitTransition = { popExit(noTabs, tabTap) },
                predictivePopEnterTransition = { popEnter(noTabs, tabTap) },
                predictivePopExitTransition = { popExit(noTabs, tabTap) },
            ) {
                val openRoute: (String) -> Unit = { navController.navigate(it) }
                page(MoodleRoutes.COURSE_DETAIL) {
                    CourseDetailSheetPage(
                        viewModel = classTable,
                        browserPreference = browserPreference,
                        onOpenRoute = openRoute,
                    )
                }
                val moodleCourseArg = navArgument(MoodleCourseViewModel.ARG_COURSE_ID) { type = NavType.IntType }
                page(MoodleRoutes.ANNOUNCEMENTS, arguments = listOf(moodleCourseArg)) { entry ->
                    MoodleAnnouncementsScreen(
                        onBack = { navController.popIfTop(entry) },
                        onOpenRoute = openRoute,
                    )
                }
                page(
                    MoodleRoutes.ANNOUNCEMENT,
                    arguments = listOf(
                        moodleCourseArg,
                        navArgument(MoodleRoutes.ARG_DISCUSSION_ID) { type = NavType.IntType },
                    ),
                ) { entry ->
                    MoodleAnnouncementScreen(
                        discussionId = entry.arguments?.getInt(MoodleRoutes.ARG_DISCUSSION_ID) ?: 0,
                        browserPreference = browserPreference,
                        onBack = { navController.popIfTop(entry) },
                        onOpenRoute = openRoute,
                    )
                }
                page(MoodleRoutes.MATERIALS, arguments = listOf(moodleCourseArg)) { entry ->
                    MoodleMaterialsScreen(
                        browserPreference = browserPreference,
                        onBack = { navController.popIfTop(entry) },
                    )
                }
                page(MoodleRoutes.FORUMS, arguments = listOf(moodleCourseArg)) { entry ->
                    MoodleForumsScreen(
                        onBack = { navController.popIfTop(entry) },
                        onOpenRoute = openRoute,
                    )
                }
                val moodleForumArg = navArgument(MoodleRoutes.ARG_FORUM_ID) { type = NavType.IntType }
                page(MoodleRoutes.FORUM, arguments = listOf(moodleCourseArg, moodleForumArg)) { entry ->
                    MoodleForumScreen(
                        forumId = entry.arguments?.getInt(MoodleRoutes.ARG_FORUM_ID) ?: 0,
                        onBack = { navController.popIfTop(entry) },
                        onOpenRoute = openRoute,
                    )
                }
                page(
                    MoodleRoutes.DISCUSSION,
                    arguments = listOf(
                        moodleCourseArg,
                        moodleForumArg,
                        navArgument(MoodleRoutes.ARG_DISCUSSION_ID) { type = NavType.IntType },
                    ),
                ) { entry ->
                    MoodleDiscussionScreen(
                        forumId = entry.arguments?.getInt(MoodleRoutes.ARG_FORUM_ID) ?: 0,
                        discussionId = entry.arguments?.getInt(MoodleRoutes.ARG_DISCUSSION_ID) ?: 0,
                        browserPreference = browserPreference,
                        onBack = { navController.popIfTop(entry) },
                    )
                }
                page(MoodleRoutes.ASSIGNMENTS, arguments = listOf(moodleCourseArg)) { entry ->
                    MoodleAssignmentsScreen(
                        onBack = { navController.popIfTop(entry) },
                        onOpenRoute = openRoute,
                    )
                }
                page(
                    MoodleRoutes.ASSIGNMENT,
                    arguments = listOf(
                        moodleCourseArg,
                        navArgument(MoodleRoutes.ARG_ASSIGNMENT_ID) { type = NavType.StringType },
                    ),
                ) { entry ->
                    MoodleAssignmentScreen(
                        browserPreference = browserPreference,
                        onBack = { navController.popIfTop(entry) },
                    )
                }
                page(MoodleRoutes.GRADES, arguments = listOf(moodleCourseArg)) { entry ->
                    MoodleGradesScreen(
                        browserPreference = browserPreference,
                        onBack = { navController.popIfTop(entry) },
                    )
                }
                page(MoodleRoutes.MEMBERS, arguments = listOf(moodleCourseArg)) { entry ->
                    MoodleMembersScreen(
                        onBack = { navController.popIfTop(entry) },
                        onOpenRoute = openRoute,
                    )
                }
                page(
                    MoodleRoutes.MEMBER,
                    arguments = listOf(
                        moodleCourseArg,
                        navArgument(MoodleRoutes.ARG_MEMBER_ID) { type = NavType.IntType },
                    ),
                ) { entry ->
                    MoodleMemberScreen(
                        memberId = entry.arguments?.getInt(MoodleRoutes.ARG_MEMBER_ID) ?: 0,
                        onBack = { navController.popIfTop(entry) },
                        onComposeSchoolMail = { address ->
                            navController.navigate(MailRoutes.compose(ComposeMode.NEW, to = address))
                        },
                    )
                }
                // As iOS stacks the compose sheet over the member's: written here, it returns to the
                // member when sent, saved or discarded.
                page(
                    MailRoutes.COMPOSE,
                    arguments = listOf(
                        navArgument("mode") { type = NavType.StringType; defaultValue = ComposeMode.NEW.name },
                        navArgument("folder") { type = NavType.StringType; defaultValue = "" },
                        navArgument("uid") { type = NavType.LongType; defaultValue = -1L },
                        navArgument("to") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) { entry ->
                    SchoolMailComposeScreen(onDone = { navController.popIfTop(entry) })
                }
            }
        }
    }
}
