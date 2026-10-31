package org.ntust.app.tigerduck.ui.screen.coursemoodle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.moodle.MoodleCourseStore
import org.ntust.app.tigerduck.data.moodle.MoodleCurrentWeek
import org.ntust.app.tigerduck.data.moodle.MoodleDownloadState
import org.ntust.app.tigerduck.data.moodle.MoodleFileDownloads
import org.ntust.app.tigerduck.data.moodle.MoodleMaterialRow
import org.ntust.app.tigerduck.data.moodle.MoodleMaterials
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseMaterials
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseSummary
import org.ntust.app.tigerduck.network.model.moodle.MoodleMaterialSection
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.ui.component.rememberAppClockVersion
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

/**
 * A course's materials in the order of its Moodle page, which teachers arrange by week or topic,
 * so the screen keeps Moodle's sections rather than sorting by date or name: a card per section
 * of its files, folders (a heading row with the files under it) and links. A file downloads on a
 * tap and opens in another app; opening a file or link logs a view on Moodle. The bar's "Open in
 * Moodle" opens the course page. Ported from iOS `MoodleMaterialsView`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodleMaterialsScreen(
    browserPreference: String,
    onBack: () -> Unit,
    viewModel: MoodleCourseViewModel = hiltViewModel(),
) {
    val store by viewModel.store.collectAsStateWithLifecycle()
    MoodleSignedOutEffect(viewModel, onBack)
    val files = store?.let { rememberMoodleFileOpener(it, browserPreference) }
    Scaffold(
        topBar = {
            MoodleTopBar(
                title = stringResource(R.string.course_detail_files_label),
                onBack = onBack,
                moodlePath = store?.let { MoodlePage.course(it.courseId) },
                willOpenPage = { files?.forgetPendingOpen() },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val current = store
            if (current == null || files == null) {
                MoodleFirstLoad(failure = null, onRetry = {})
            } else {
                MaterialsList(current, files, browserPreference)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MaterialsList(store: MoodleCourseStore, files: MoodleFileOpener, browserPreference: String) {
    val state by store.materials.state.collectAsStateWithLifecycle()
    val summary by store.summary.state.collectAsStateWithLifecycle()
    val snapshot = store.downloads?.snapshot?.collectAsStateWithLifecycle()?.value
    MoodleAutoRefresh(store, store.materials.ttlMs, store::awaitCache, { store.materials.state.value.fetchedAtMs }) {
        store.refreshMaterials()
    }
    // The course page fetches the summary too, but a fetch it failed would leave this page
    // without the format and start date the week comes from.
    MoodleAutoRefresh(store, store.summary.ttlMs, store::awaitCache, { store.summary.state.value.fetchedAtMs }) {
        store.refreshSummary()
    }
    var pulling by remember { mutableStateOf(false) }
    val refresher = rememberRefresher { store.refreshMaterials() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val currentWeek = rememberCurrentWeek(state.value, summary.value)
    // The list's index of this week's section, after the failure note when one shows.
    val currentWeekIndex = state.value?.sections?.indexOfFirst { it.id == currentWeek }
        ?.takeIf { it >= 0 }
        ?.let { it + if (state.failureBesideCopy != null) 1 else 0 }
    // The keys of the list's items, as the list below gives them.
    val itemKeys = state.value?.let { materials ->
        listOfNotNull(FAILURE_KEY.takeIf { state.failureBesideCopy != null }) + materials.sections.map { it.id }
    }
    ScrollToWeekOnce(store, listState, itemKeys, currentWeekIndex)
    PullToRefreshBox(
        isRefreshing = pulling,
        onRefresh = { pulling = true; refresher { pulling = false } },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val materials = state.value
            if (materials == null) {
                item(key = FIRST_LOAD_KEY) {
                    MoodleFirstLoad(
                        failure = if (state.isRefreshing) null else state.failure,
                        onRetry = { scope.launch { store.refreshMaterials() } },
                    )
                }
            } else {
                state.failureBesideCopy?.let { failure ->
                    item(key = FAILURE_KEY) { MoodleRefreshFailure(failure, onRetry = { scope.launch { store.refreshMaterials() } }) }
                }
                items(materials.sections, key = { it.id }) { section ->
                    MaterialSection(store, section, section.id == currentWeek, snapshot, files, browserPreference)
                }
            }
        }
    }
}

/**
 * Scrolls [listState] to this week's section once, when the page opens. Without a copy of the
 * summary, the summary's format decides how the week is found, so the scroll waits for a fetch
 * of it to end, answered or failed. It is the page's one chance: a week found later, as by a
 * summary that comes after a failed fetch, does not move the list; nor does a week found after
 * the reader has moved it, by touch, keys or TalkBack alike.
 */
@Composable
private fun ScrollToWeekOnce(
    store: MoodleCourseStore,
    listState: LazyListState,
    /** The keys of the list's items; null before the materials show. */
    itemKeys: List<Any>?,
    currentWeekIndex: Int?,
) {
    val summary by store.summary.state.collectAsStateWithLifecycle()
    var done by rememberSaveable { mutableStateOf(false) }
    val fetchesAtOpen = remember(store) { store.summary.state.value.fetchesEnded }
    val keys by rememberUpdatedState(itemKeys)
    // The reader has moved the list once its top is anything but the item at the top when the
    // materials first showed, from its start. A section or a failure note that comes in above that
    // item leaves it at the top, as the list holds its first item by key, which no reader did; only
    // when a refresh takes that item away does the item now at the top take its place.
    LaunchedEffect(listState) {
        if (done) return@LaunchedEffect
        fun topKey(): Any? = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == listState.firstVisibleItemIndex }?.key
        var anchor = snapshotFlow { topKey().takeIf { keys != null && (it is Int || it == FAILURE_KEY) } }
            .filterNotNull().first()
        snapshotFlow { Triple(listState.firstVisibleItemScrollOffset, topKey(), keys.orEmpty()) }
            .first { (offset, top, shown) ->
                when {
                    offset != 0 -> true
                    top == null || top == anchor -> false
                    anchor in shown -> true
                    else -> {
                        anchor = top
                        false
                    }
                }
            }
        done = true
    }
    val summaryTried = summary.value != null || summary.fetchesEnded > fetchesAtOpen
    val ready = itemKeys != null && summary.cacheLoaded && summaryTried
    val weekIndex by rememberUpdatedState(currentWeekIndex)
    LaunchedEffect(ready) {
        if (!ready || done) return@LaunchedEffect
        done = true
        weekIndex?.let { listState.scrollToItem(it) }
    }
}

private const val FAILURE_KEY = "failure"

// Not left to the list's default, so the first load never passes for a section, whose key is its id.
private const val FIRST_LOAD_KEY = "first-load"

/**
 * This week's section; null for none. The course's format and start date come from its summary,
 * which the course page keeps fresh.
 */
@Composable
private fun rememberCurrentWeek(materials: MoodleCourseMaterials?, summary: MoodleCourseSummary?): Int? {
    val clockVersion by rememberAppClockVersion()
    return remember(materials, summary, clockVersion) {
        materials?.let {
            MoodleCurrentWeek.sectionId(
                it.sections, summary?.format, summary?.startDate, AppClock.instant(), AppConstants.TAIPEI_ZONE,
            )
        }
    }
}

@Composable
private fun MaterialSection(
    store: MoodleCourseStore,
    section: MoodleMaterialSection,
    isCurrentWeek: Boolean,
    snapshot: MoodleFileDownloads.Snapshot?,
    files: MoodleFileOpener,
    browserPreference: String,
) {
    val context = LocalContext.current
    val rows = remember(section) { MoodleMaterials.rows(section) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (section.name.isNotEmpty()) {
            Text(
                text = section.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (isCurrentWeek) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .semantics {
                        heading()
                        if (isCurrentWeek) selected = true
                    },
            )
        }
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column {
                rows.forEachIndexed { index, row ->
                    if (index > 0) {
                        val indent = if ((row as? MoodleMaterialRow.File)?.inFolder == true) MoodleFolderIndent else 0.dp
                        HorizontalDivider(Modifier.padding(start = MoodleRowTextInset + indent), color = cardDividerColor())
                    }
                    val material = row.material
                    val openInMoodle = {
                        files.forgetPendingOpen()
                        openMoodlePage(context, MoodlePage.module(material.module, material.id))
                    }
                    when (row) {
                        is MoodleMaterialRow.File -> {
                            val state = snapshot?.let { store.downloadState(row.ref, it) } ?: MoodleDownloadState.NotDownloaded
                            MoodleFileRow(
                                title = if (row.inFolder) row.file.name else material.name.ifEmpty { row.file.name },
                                file = row.file,
                                state = state,
                                indent = if (row.inFolder) MoodleFolderIndent else 0.dp,
                                onTap = { files.tap(row.ref, state) { store.open(material) } },
                                onShare = { files.share(row.ref) },
                                onOpenInMoodle = openInMoodle,
                            )
                        }
                        is MoodleMaterialRow.Link -> MoodleLinkRow(
                            title = material.name,
                            url = row.url,
                            onOpen = {
                                files.forgetPendingOpen()
                                openMoodleContentLink(context, row.url, browserPreference)
                                store.open(material)
                            },
                            onShare = {
                                files.forgetPendingOpen()
                                shareMoodleLink(context, material.name, row.url)
                            },
                            onOpenInMoodle = openInMoodle,
                        )
                        is MoodleMaterialRow.Folder -> MoodleFolderRow(material.name, row.fileCount)
                    }
                }
            }
        }
    }
}
