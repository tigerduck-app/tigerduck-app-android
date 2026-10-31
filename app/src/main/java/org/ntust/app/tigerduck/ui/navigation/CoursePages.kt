package org.ntust.app.tigerduck.ui.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.navArgument
import org.ntust.app.tigerduck.ui.screen.classtable.CourseSyllabusScreen
import org.ntust.app.tigerduck.ui.screen.classtable.CourseSyllabusRoutes
import org.ntust.app.tigerduck.mail.ComposeMode
import org.ntust.app.tigerduck.mail.MailRoutes
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

/**
 * The Moodle pages the course sheet opens, full screen in the app's navigation, over the page the
 * sheet is on (see CourseDetailSheet). A member's email opens the School Mail compose, a page of
 * the same navigation.
 */
internal fun NavGraphBuilder.coursePages(navController: NavController, browserPreference: () -> String) {
    val openRoute: (String) -> Unit = { navController.navigate(it) }
    val moodleCourseArg = navArgument(MoodleCourseViewModel.ARG_COURSE_ID) { type = NavType.IntType }
    page(
        CourseSyllabusRoutes.SYLLABUS,
        arguments = listOf(
            navArgument(CourseSyllabusRoutes.ARG_SEMESTER) { type = NavType.StringType },
            navArgument(CourseSyllabusRoutes.ARG_COURSE_NO) { type = NavType.StringType },
        ),
    ) { entry ->
        CourseSyllabusScreen(browserPreference = browserPreference(), onBack = { navController.popIfTop(entry) })
    }
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
            browserPreference = browserPreference(),
            onBack = { navController.popIfTop(entry) },
            onOpenRoute = openRoute,
        )
    }
    page(MoodleRoutes.MATERIALS, arguments = listOf(moodleCourseArg)) { entry ->
        MoodleMaterialsScreen(
            browserPreference = browserPreference(),
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
            browserPreference = browserPreference(),
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
            browserPreference = browserPreference(),
            onBack = { navController.popIfTop(entry) },
        )
    }
    page(MoodleRoutes.GRADES, arguments = listOf(moodleCourseArg)) { entry ->
        MoodleGradesScreen(
            browserPreference = browserPreference(),
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
}
