// The class table's selected course as iOS presents it: a sheet that opens at
// half height, drags to full and moves as iOS's does (DetentSheet). The Moodle
// pages it opens, and a member's mail, are pages of the app's own navigation
// (coursePages), full screen over the page the sheet is on: the sheet steps
// aside while they show, and a back to that page brings it back.

package org.ntust.app.tigerduck.ui.navigation

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import org.ntust.app.tigerduck.ui.component.DetentSheet
import org.ntust.app.tigerduck.ui.component.DetentSheetValue
import org.ntust.app.tigerduck.ui.component.rememberDetentSheetState
import org.ntust.app.tigerduck.ui.screen.classtable.ClassTableViewModel
import org.ntust.app.tigerduck.ui.screen.classtable.CourseDetailSheetPage
import org.ntust.app.tigerduck.ui.screen.coursemoodle.MoodleRoutes

/** What the course sheet does as the app's navigation moves. */
internal object CourseDetailSheetRules {
    /**
     * Whether the sheet shows on its page: while a course is [picked] and the page is the one on
     * top, so a page the sheet opens covers it, and a back to the page brings it back.
     */
    fun shows(picked: Boolean, pageOnTop: Boolean): Boolean = picked && pageOnTop
}

/** Whether [entry] is the page on top: false from the moment a page is opened over it. */
@Composable
internal fun NavController.isOnTop(entry: NavBackStackEntry): Boolean {
    // Seeded with the page on top now: the flow's first answer comes a frame late, and a sheet
    // open across a change of dark mode would close for that frame.
    val current by currentBackStackEntryFlow.collectAsState(initial = currentBackStackEntry)
    return current?.id == entry.id
}

/**
 * Holds the view models of the sheet's page: past the activity being recreated, as for a change
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

/**
 * Where the course sheet was left: its navigation, which holds its page's view models, how far it
 * was scrolled and whether it was pulled up. Remembered with the page the sheet is on, not with the
 * sheet, so it is saved with that page while a page opened from the sheet covers it.
 */
@Stable
internal class CourseSheetPlace(
    val navController: NavHostController,
    val scroll: ScrollState,
    expanded: MutableState<Boolean>,
) {
    var expanded by expanded
}

/** The [CourseSheetPlace] of [course]: another course starts afresh. */
@Composable
internal fun rememberCourseSheetPlace(course: Any): CourseSheetPlace = key(course) {
    val navController = rememberNavController()
    val scroll = rememberScrollState()
    val expanded = rememberSaveable { mutableStateOf(false) }
    remember(navController, scroll, expanded) { CourseSheetPlace(navController, scroll, expanded) }
}

/**
 * The selected course's sheet, while there is one and [pageOnTop]; closing it clears the selection.
 * [onOpenPage] opens a page the sheet links to, over the page the sheet is on, and the sheet comes
 * back from it as it was left.
 */
@Composable
internal fun CourseDetailSheetHost(
    classTable: ClassTableViewModel,
    browserPreference: String,
    pageOnTop: Boolean,
    onOpenPage: (String) -> Unit,
) {
    val models: CourseDetailSheetModels = viewModel()
    val selected by classTable.selectedCourse.collectAsStateWithLifecycle()
    LaunchedEffect(selected == null) { if (selected == null) models.close() }
    val course = selected ?: return
    val place = rememberCourseSheetPlace(course.courseNo)
    if (CourseDetailSheetRules.shows(picked = true, pageOnTop = pageOnTop)) {
        CourseDetailSheet(classTable, browserPreference, models, place, onOpenPage, onClosed = classTable::clearSelection)
    }
}

@Composable
private fun CourseDetailSheet(
    classTable: ClassTableViewModel,
    browserPreference: String,
    models: CourseDetailSheetModels,
    place: CourseSheetPlace,
    onOpenPage: (String) -> Unit,
    onClosed: () -> Unit,
) {
    val sheetState = rememberDetentSheetState()
    LaunchedEffect(sheetState) {
        snapshotFlow { sheetState.currentValue }.collect {
            if (it != DetentSheetValue.Hidden) place.expanded = it == DetentSheetValue.Full
        }
    }
    DetentSheet(
        onDismissed = onClosed,
        state = sheetState,
        halfHeight = true,
        // The full height whatever the page, so the sheet opens at half the screen as iOS's
        // medium detent does, and a short page doesn't shrink it.
        fillHeight = true,
        // Up as high as it was left.
        openAt = if (place.expanded) DetentSheetValue.Full else DetentSheetValue.Half,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        // A navigation of the course detail alone: its entry gives the page's view models a home
        // in [models], which goes with the sheet's course.
        CompositionLocalProvider(LocalViewModelStoreOwner provides models) {
            NavHost(
                navController = place.navController,
                startDestination = MoodleRoutes.COURSE_DETAIL,
                // The rest of the sheet's height whatever the page, so a short page doesn't leave
                // the sheet's lower part empty of it.
                modifier = Modifier.fillMaxSize(),
            ) {
                page(MoodleRoutes.COURSE_DETAIL) {
                    CourseDetailSheetPage(
                        viewModel = classTable,
                        browserPreference = browserPreference,
                        onOpenRoute = onOpenPage,
                        scrollState = place.scroll,
                    )
                }
            }
        }
    }
}
