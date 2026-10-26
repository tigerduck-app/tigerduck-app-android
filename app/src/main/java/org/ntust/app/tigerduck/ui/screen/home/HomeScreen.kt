// The Home tab: greeting, the reorderable section list, and the dialog
// it owns. Section bodies are in HomeSections.kt and the assignment list
// in HomeAssignmentList.kt. A course on the time slider opens the class
// table's course detail.

package org.ntust.app.tigerduck.ui.screen.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.model.HomeSection
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.component.ComingSoonDialog
import org.ntust.app.tigerduck.ui.component.PageHeader
import org.ntust.app.tigerduck.ui.component.ServerKind
import org.ntust.app.tigerduck.ui.component.SyncStatusDot
import org.ntust.app.tigerduck.ui.component.TigerPullToRefresh
import org.ntust.app.tigerduck.ui.component.rememberAppClockVersion
import org.ntust.app.tigerduck.ui.component.PageLeftEffect
import org.ntust.app.tigerduck.ui.screen.signin.AutoSignInFailedBanner
import org.ntust.app.tigerduck.ui.screen.signin.NtustSignInViewModel
import org.ntust.app.tigerduck.ui.screen.signin.bannerRejection
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    appState: AppState,
    viewModel: HomeViewModel = hiltViewModel(),
    onOpenSignInSettings: () -> Unit = {},
    /** Opens an assignment's page in the app; the page's own button is what opens Moodle. */
    onOpenAssignment: (Assignment) -> Unit = {},
    /** A course tapped on the time slider: the class table's course detail, as iOS opens it from Home. */
    onOpenCourseDetail: (CourseDetailSelection) -> Unit = {},
    /** A shipped feature's shortcut card tapped: its page, over Home, as iOS pushes it inside Home. */
    onOpenFeature: (AppFeature) -> Unit = {},
) {
    val resources = LocalResources.current
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    // Slow pulse (60s, plus every debug-clock flip) so the term gate below
    // flips on its own when wall-clock time crosses a term boundary
    // while Home stays mounted. Both boundaries land at midnight, so a minute
    // of latency is ample.
    val termClockVersion by rememberAppClockVersion()
    // Home's time slider is a "today" surface, so it is dropped outside the
    // term rather than left to scrub a day with no classes. Reorder is
    // unaffected — the drag handler resolves both ends by section id, never by
    // this list's indices — and the add-section dialog still sees the full
    // list, so a hidden section can't be added twice.
    val visibleSections = remember(sections, termClockVersion) {
        if (viewModel.isTermInSession()) sections
        else sections.filterNot { it.type == HomeSection.HomeSectionType.TODAY_COURSES }
    }
    // Re-read on the same pulse, so a holiday published or switched on another
    // device reaches the time slider while Home stays open. An equal read keeps
    // the old value, so the sections are not redrawn every minute.
    var quietDays by remember { mutableStateOf(viewModel.quietDays()) }
    LaunchedEffect(termClockVersion) { quietDays = viewModel.quietDays() }
    val allCourses by viewModel.allCourses.collectAsStateWithLifecycle()
    val upcomingAssignments by viewModel.upcomingAssignments.collectAsStateWithLifecycle()
    val assignmentFilter by viewModel.assignmentFilter.collectAsStateWithLifecycle()
    val ignoredAssignmentIds by viewModel.ignoredAssignmentIds.collectAsStateWithLifecycle()
    val markedCompletedIds by viewModel.markedCompletedIds.collectAsStateWithLifecycle()
    val courseIgnoredIds by viewModel.courseIgnoredIds.collectAsStateWithLifecycle()
    val ignoredTabPinned by viewModel.ignoredTabPinned.collectAsStateWithLifecycle()
    val hasIgnoredAssignments by viewModel.hasIgnoredAssignments.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val schoolDataSyncedAtMs by viewModel.schoolDataSyncedAtMs.collectAsStateWithLifecycle()
    val initialLoadComplete by viewModel.initialLoadComplete.collectAsStateWithLifecycle()
    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
    // 翹課 — see HomeViewModel._skippedDates. Lands after add-friend.
    // val skippedDates by viewModel.skippedDates.collectAsStateWithLifecycle()
    val syncConflicts by viewModel.syncConflicts.collectAsStateWithLifecycle()
    var showComingSoon by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val signInViewModel: NtustSignInViewModel = hiltViewModel()
    val passwordRejection by signInViewModel.rejection.collectAsStateWithLifecycle()
    val signedInForBanner by signInViewModel.signedIn.collectAsStateWithLifecycle()

    val editing = viewModel.editing
    val isEditing = editing.isEditing
    var showAddSectionDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    HomeEditingLeaveEffect(editing, listState)
    // Here rather than in the time slider: arranged, Home draws its outline in
    // its place, and a time the user picked would go with it.
    val timeSlider = remember { TimeSliderViewModel() }

    // Intercept the system back gesture while editing — exit edit mode
    // instead of navigating away. This matches Android-native expectations
    // for any screen with a distinct modal edit state.
    BackHandler(enabled = isEditing) {
        editing.finish()
    }

    LaunchedEffect(Unit) { 
        viewModel.load()
        // Each time the page enters composition: a return to it from
        // another page fetches if its RefreshPolicy asks for that.
        viewModel.onPageShown()
    }
    PageLeftEffect(viewModel::onPageLeft)

    // When the Home screen leaves the foreground (tab switch, background),
    // reset the filter away from 已忽略 if it ended up empty. This makes the
    // "stay pinned on IGNORED tab while the user might want to undo" behavior
    // only apply *during* the current visit.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.syncOnForeground()
                // The page's own resume, for the Play rating's moment; its
                // lifecycle answers, right before the launch, whether it
                // still is resumed.
                viewModel.onPageResumed { lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                viewModel.onHomePaused()
                // The moment the page offered is withdrawn with its pause.
                viewModel.onPagePaused()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Key on viewModel so a fresh collector is launched on each remount;
    // collecting on Unit means events emitted while the screen is in the back
    // stack stay attached to a stale collector and can be lost.
    LaunchedEffect(viewModel) {
        viewModel.noNetworkEvent.collect {
            snackbarHostState.showSnackbar(resources.getString(R.string.error_network_unavailable))
        }
    }

    // Stable lambda reference: a fresh `viewModel::hasUnfinishedAssignment`
    // bound-method allocation per recomposition would defeat skipping for
    // every HomeSectionContent below.
    val hasUnfinishedAssignment = remember(viewModel) {
        { id: String -> viewModel.hasUnfinishedAssignment(id) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        HomePage(
            sections = visibleSections,
            isEditing = isEditing,
            onStartEditing = editing::start,
            isRefreshing = isLoading,
            onRefresh = { viewModel.refresh() },
            onMoveSection = viewModel::moveSections,
            onRemoveSection = viewModel::removeSection,
            onRemoveWidget = viewModel::removeWidget,
            onMoveWidget = viewModel::moveWidget,
            onAddQuickFeature = { showAddSectionDialog = true },
            listState = listState,
            header = {
                PageHeader(title = greetingText()) {
                    if (isEditing) {
                        IconButton(onClick = { showAddSectionDialog = true }) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = stringResource(R.string.home_add_section_title)
                            )
                        }
                        TextButton(onClick = editing::finish) {
                            Text(
                                stringResource(R.string.action_done),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    } else {
                        SyncStatusDot(
                            servers = listOf(ServerKind.MOODLE, ServerKind.BACKEND),
                            isLoading = isLoading,
                            syncedAtMs = schoolDataSyncedAtMs,
                        )
                    }
                }
            },
            banner = if (bannerRejection(passwordRejection, signInViewModel.dismissedRejection, signedInForBanner) != null) {
                { AutoSignInFailedBanner(viewModel = signInViewModel) }
            } else {
                null
            },
        ) { section ->
            HomeSectionContent(
                section = section,
                allCourses = allCourses,
                timeSlider = timeSlider,
                quietDays = quietDays,
                upcomingAssignments = upcomingAssignments,
                assignmentFilter = assignmentFilter,
                showIgnoredTab = hasIgnoredAssignments || ignoredTabPinned,
                ignoredAssignmentIds = ignoredAssignmentIds,
                markedCompletedIds = markedCompletedIds,
                courseIgnoredIds = courseIgnoredIds,
                isLoggedIn = isLoggedIn,
                isLoading = isLoading,
                initialLoadComplete = initialLoadComplete,
                hasUnfinishedAssignment = hasUnfinishedAssignment,
                showAbsoluteTime = appState.showAbsoluteAssignmentTime,
                invertDirection = appState.invertSliderDirection,
                // Composed only while browsing (HomePage draws an arranged
                // section as its outline), so none of these checks for editing.
                onCourseClick = { slot -> onOpenCourseDetail(slot.detailSelection) },
                onAssignmentClick = onOpenAssignment,
                onToggleIgnore = viewModel::toggleIgnore,
                onMarkCompleted = viewModel::toggleMarkCompleted,
                onSelectFilter = { viewModel.setAssignmentFilter(it) },
                // onSkipCourse = { course, date -> viewModel.toggleSkip(course, date) },
                onOpenFeature = { feature ->
                    // A stored layout may still hold a shortcut to a
                    // feature that has not shipped (the first default
                    // had four); it says so, as the More page does.
                    if (feature.isImplemented) onOpenFeature(feature) else showComingSoon = true
                },
                onAddQuickFeature = { showAddSectionDialog = true },
                onOpenSignInSettings = onOpenSignInSettings,
            )
        }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    } // Box

    if (showAddSectionDialog) {
        AddSectionDialog(
            existingSections = sections,
            onAddBuiltin = { type ->
                viewModel.addSection(type, resources.getString(type.defaultTitleRes))
                showAddSectionDialog = false
            },
            onAddCustom = { title ->
                viewModel.addSection(HomeSection.HomeSectionType.CUSTOM, title)
                showAddSectionDialog = false
            },
            onAddQuickFeature = { feature ->
                viewModel.addQuickFeature(
                    feature,
                    resources.getString(HomeSection.HomeSectionType.QUICK_WIDGETS.defaultTitleRes),
                )
                showAddSectionDialog = false
            },
            onDismiss = { showAddSectionDialog = false },
        )
    }

    if (showComingSoon) {
        ComingSoonDialog(onDismiss = { showComingSoon = false })
    }

    if (syncConflicts.isNotEmpty()) {
        SyncConflictDialog(
            conflicts = syncConflicts,
            onKeepLocal = { viewModel.resolveSyncConflicts(keepLocal = true) },
            onKeepServer = { viewModel.resolveSyncConflicts(keepLocal = false) },
        )
    }

}
/**
 * Home's page under its pull to refresh: [header], [banner] when there is one, and [sections],
 * each drawn by [sectionContent]. A long press on a section starts editing, which makes the page
 * a layout editor, as iOS's is (tigerduck-app/tigerduck-app#852): each section is an
 * [EditableSection] outline whose handle moves it among the others, nothing else on the page
 * responds but [header], and the page still scrolls, so a section below the screen can be reached.
 */
@Composable
internal fun HomePage(
    sections: List<HomeSection>,
    isEditing: Boolean,
    onStartEditing: () -> Unit,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onMoveSection: (fromId: String, toId: String) -> Unit,
    onRemoveSection: (sectionId: String) -> Unit,
    onRemoveWidget: (sectionId: String, widgetId: String) -> Unit,
    onMoveWidget: (sectionId: String, widgetId: String, toIndex: Int) -> Unit,
    /** An arranged shortcut section's empty-grid button: the Add dialog. */
    onAddQuickFeature: () -> Unit,
    header: @Composable () -> Unit,
    banner: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    sectionContent: @Composable (HomeSection) -> Unit,
) {
    // Per-section measured height — needed for the drag-and-swap threshold
    // math. Position on screen is *not* tracked because layout changes during
    // reorder would pollute it; height is stable enough per section.
    val sectionHeights = remember { mutableStateMapOf<String, Float>() }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    // A swap moves the dragged section by its neighbor's height and the gap
    // between them.
    val sectionGapPx = with(LocalDensity.current) { SectionGap.toPx() }

    // If edit mode is cancelled (back gesture, etc.) mid-drag, clear drag state.
    LaunchedEffect(isEditing) {
        if (!isEditing) {
            draggingId = null
            dragOffsetY = 0f
        }
    }

    // A LazyColumn keeps its first visible item where it is, by key, when the
    // list changes, so a drag moving that item would scroll the page along
    // with it, out from under the finger. A move during a drag keeps the page
    // where it was by position instead. Asked for when the new order is
    // applied, which comes after the drag callback that caused it, because
    // the request holds for the next measure only.
    val order = sections.map { it.id }
    val appliedOrder = remember { arrayOf(order) }
    SideEffect {
        if (draggingId != null && order != appliedOrder[0]) {
            listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }
        appliedOrder[0] = order
    }

    TigerPullToRefresh(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
        refreshingMessage = stringResource(R.string.refreshing_message),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                // While arranging, a pull past the top stops here, short of
                // the refresh around the list: iOS refreshes only the page
                // being browsed, and nothing an outline shows would change.
                .then(if (isEditing) Modifier.nestedScroll(KeepPullDown) else Modifier),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(SectionGap),
        ) {
            item(key = "header") { header() }

            // As its own item only while shown: an empty one would still
            // take the list's spacing. Not while arranging, where its buttons
            // would be the one live thing left on the page.
            if (banner != null && !isEditing) {
                item(key = "autoSignInFailed") { banner() }
            }

            itemsIndexed(
                items = sections,
                key = { _, s -> s.id },
            ) { _, section ->
                if (!isEditing) {
                    BrowsedSection(section = section, onStartEditing = onStartEditing) {
                        sectionContent(section)
                    }
                    return@itemsIndexed
                }
                EditableSection(
                    section = section,
                    isDragging = draggingId == section.id,
                    dragOffsetY = if (draggingId == section.id) dragOffsetY else 0f,
                    onDragStart = {
                        draggingId = section.id
                        dragOffsetY = 0f
                    },
                    onDrag = { delta ->
                        dragOffsetY += delta
                        val fromIdx = sections.indexOfFirst { it.id == section.id }
                        if (fromIdx < 0) return@EditableSection

                        // Threshold-based swap: when the drag has crossed half of
                        // the step to the neighbor's place (its height and the gap
                        // after it), swap in the data model and compensate
                        // `dragOffsetY` by that step, so the card stays visually
                        // under the user's finger (no snap-back). The same step
                        // both ways, or a swap would land past the point that
                        // swaps it back.
                        if (dragOffsetY > 0 && fromIdx < sections.lastIndex) {
                            val next = sections[fromIdx + 1]
                            val step = (sectionHeights[next.id] ?: return@EditableSection) + sectionGapPx
                            if (dragOffsetY > step / 2f) {
                                onMoveSection(section.id, next.id)
                                dragOffsetY -= step
                            }
                        } else if (dragOffsetY < 0 && fromIdx > 0) {
                            val prev = sections[fromIdx - 1]
                            val step = (sectionHeights[prev.id] ?: return@EditableSection) + sectionGapPx
                            if (dragOffsetY < -step / 2f) {
                                onMoveSection(section.id, prev.id)
                                dragOffsetY += step
                            }
                        }
                    },
                    onDragEnd = {
                        draggingId = null
                        dragOffsetY = 0f
                    },
                    onMeasured = { height ->
                        sectionHeights[section.id] = height
                    },
                    onRemove = { onRemoveSection(section.id) },
                    onRemoveWidget = { onRemoveWidget(section.id, it) },
                    onMoveWidget = { widgetId, toIndex -> onMoveWidget(section.id, widgetId, toIndex) },
                    onAddQuickFeature = onAddQuickFeature,
                )
            }
        }
    }
}

/** Between the page's items, sections and header alike. */
private val SectionGap = 16.dp

/**
 * Takes what is left of a pull down past the top, the one thing the refresh around the list acts
 * on, so it never gets there. Whatever else is left over still stretches the list's own edge.
 */
private object KeepPullDown : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        Offset(0f, available.y.coerceAtLeast(0f))

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        Velocity(0f, available.y.coerceAtLeast(0f))
}

@Composable
private fun greetingText(): String {
    val hour = AppClock.calendar().get(Calendar.HOUR_OF_DAY)
    return when {
        hour < 6 -> stringResource(R.string.greeting_very_early)
        hour < 12 -> stringResource(R.string.greeting_morning)
        hour < 18 -> stringResource(R.string.greeting_afternoon)
        else -> stringResource(R.string.greeting_evening)
    }
}
@Composable
private fun SyncConflictDialog(
    conflicts: List<AssignmentSyncConflict>,
    onKeepLocal: () -> Unit,
    onKeepServer: () -> Unit,
) {
    @Composable
    fun statusLabel(s: String) = when (s) {
        "ignored", "archived" -> stringResource(R.string.sync_conflict_status_ignored)
        "locally_completed" -> stringResource(R.string.sync_conflict_status_completed)
        "none" -> stringResource(R.string.sync_conflict_status_none)
        else -> s
    }

    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.sync_conflict_title)) },
        text = {
            Column {
                Text(stringResource(R.string.sync_conflict_message), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                conflicts.forEach { c ->
                    Text(
                        stringResource(R.string.sync_conflict_item_header, c.kind, c.label),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.sync_conflict_item_detail, statusLabel(c.localStatus), statusLabel(c.serverStatus)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onKeepServer) {
                Text(stringResource(R.string.sync_conflict_use_server))
            }
        },
        dismissButton = {
            TextButton(onClick = onKeepLocal) {
                Text(stringResource(R.string.sync_conflict_use_local))
            }
        },
    )
}
