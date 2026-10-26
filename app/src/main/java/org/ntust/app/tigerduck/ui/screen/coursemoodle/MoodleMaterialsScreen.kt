package org.ntust.app.tigerduck.ui.screen.coursemoodle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.moodle.MoodleCourseStore
import org.ntust.app.tigerduck.data.moodle.MoodleDownloadState
import org.ntust.app.tigerduck.data.moodle.MoodleFileDownloads
import org.ntust.app.tigerduck.data.moodle.MoodleMaterialRow
import org.ntust.app.tigerduck.data.moodle.MoodleMaterials
import org.ntust.app.tigerduck.network.model.moodle.MoodleMaterialSection
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
private fun MaterialsList(store: MoodleCourseStore, files: MoodleFileOpener, browserPreference: String) {
    val state by store.materials.state.collectAsStateWithLifecycle()
    val snapshot = store.downloads?.snapshot?.collectAsStateWithLifecycle()?.value
    MoodleAutoRefresh(store, store.materials.ttlMs, store::awaitCache, { store.materials.state.value.fetchedAtMs }) {
        store.refreshMaterials()
    }
    var pulling by remember { mutableStateOf(false) }
    val refresher = rememberRefresher { store.refreshMaterials() }
    val scope = rememberCoroutineScope()
    PullToRefreshBox(
        isRefreshing = pulling,
        onRefresh = { pulling = true; refresher { pulling = false } },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val materials = state.value
            if (materials == null) {
                item {
                    MoodleFirstLoad(
                        failure = if (state.isRefreshing) null else state.failure,
                        onRetry = { scope.launch { store.refreshMaterials() } },
                    )
                }
            } else {
                state.failureBesideCopy?.let { failure ->
                    item(key = "failure") { MoodleRefreshFailure(failure, onRetry = { scope.launch { store.refreshMaterials() } }) }
                }
                items(materials.sections, key = { it.id }) { section ->
                    MaterialSection(store, section, snapshot, files, browserPreference)
                }
            }
        }
    }
}

@Composable
private fun MaterialSection(
    store: MoodleCourseStore,
    section: MoodleMaterialSection,
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
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .semantics { heading() },
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
