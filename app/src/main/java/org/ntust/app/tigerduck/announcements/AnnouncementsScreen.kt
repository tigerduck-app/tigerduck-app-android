package org.ntust.app.tigerduck.announcements

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.component.EmptyStateView
import org.ntust.app.tigerduck.ui.component.PageHeader
import org.ntust.app.tigerduck.ui.component.SyncIndicator
import org.ntust.app.tigerduck.ui.component.TigerPullToRefresh
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AnnouncementsScreen(
    onOpenBulletin: (Int) -> Unit,
    onOpenSubscriptions: () -> Unit,
    viewModel: AnnouncementsViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.load() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pullProgress by remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState()
    val isLoading = state.loadState is AnnouncementsViewModel.LoadState.Loading
    var showCheckmark by remember { mutableStateOf(false) }
    var sawLoading by remember { mutableStateOf(false) }
    LaunchedEffect(state.loadState) {
        when (state.loadState) {
            is AnnouncementsViewModel.LoadState.Loading -> sawLoading = true
            is AnnouncementsViewModel.LoadState.Loaded -> {
                if (sawLoading) {
                    sawLoading = false
                    showCheckmark = true
                    delay(2000)
                    showCheckmark = false
                }
            }

            else -> sawLoading = false
        }
    }
    LaunchedEffect(state.unreadOnly, state.selectedOrgs, state.selectedTags, state.searchText) {
        listState.scrollToItem(0)
    }

    // Pagination trigger uses the LazyColumn index (not the bulletin id) so
    // the lookup into `displayed` stays O(1) regardless of list length. The
    // sticky-header item occupies index 0; bulletins occupy 1..displayed.size.
    // We filter to Int-keyed items so spinner/spacer (String keys) don't
    // drive the trigger.
    val currentDisplayed by rememberUpdatedState(state.displayed)
    val currentOnLastVisible by rememberUpdatedState(viewModel::loadMoreIfNeeded)
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.lastOrNull { it.key is Int }?.index
        }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { lastIndex ->
                // headers are item 0; bulletinIndex = lastIndex - 1.
                currentDisplayed.getOrNull(lastIndex - 1)?.let(currentOnLastVisible)
            }
    }

    // Empty-state height = viewport - header. Both are measured via
    // onSizeChanged, and we hold off rendering the empty state until both
    // measurements have arrived — otherwise the first composition uses 0
    // for the header and sizes the empty state to the full viewport,
    // briefly making the LazyColumn scrollable past the bottom.
    var headerHeightPx by remember { mutableIntStateOf(0) }
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val emptyStateHeight = with(density) {
        (viewportHeightPx - headerHeightPx).coerceAtLeast(0).toDp()
    }
    val canShowEmptyState = viewportHeightPx > 0 && headerHeightPx > 0

    // Plain Box (not BoxWithConstraints) avoids SubcomposeLayout: the latter
    // re-subcomposes its content lambda whenever incoming constraints change
    // (IME open/close, rotation, system-bar inset animations), which would
    // tear down and rebuild the entire LazyColumn each time. onSizeChanged
    // also can't fire with an infinite value, so this survives unbounded
    // vertical constraints from any future host.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewportHeightPx = it.height },
    ) {
        TigerPullToRefresh(
            isRefreshing = state.loadState is AnnouncementsViewModel.LoadState.Loading,
            onRefresh = viewModel::refresh,
            onDragProgress = { pullProgress = it },
            modifier = Modifier.fillMaxSize(),
            refreshingMessage = stringResource(R.string.refreshing_message),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
            ) {
                // stickyHeader keeps the page title, search field, and filter
                // chips pinned at the top while the bulletin list scrolls —
                // matching the always-visible chrome the old non-LazyColumn
                // layout provided, and preventing the search field from being
                // disposed (losing IME focus) when it scrolls off.
                stickyHeader(key = "headers") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                            .onSizeChanged { headerHeightPx = it.height },
                    ) {
                        PageHeader(title = stringResource(R.string.feature_announcements)) {
                            SyncIndicator(
                                isLoading = isLoading,
                                showCheckmark = showCheckmark,
                                dragProgress = pullProgress,
                            )
                            if (state.unreadOnly && state.hasUnread) {
                                IconButton(onClick = viewModel::markAllRead) {
                                    Icon(
                                        Icons.Filled.DoneAll,
                                        contentDescription = stringResource(R.string.bulletin_mark_all_read_action),
                                    )
                                }
                            }
                            val unreadOnlyLabel =
                                stringResource(R.string.bulletin_show_unread_only_action)
                            Box(
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .toggleable(
                                        value = state.unreadOnly,
                                        role = Role.Switch,
                                        onValueChange = { viewModel.setUnreadOnly(it) },
                                    )
                                    .semantics {
                                        contentDescription = unreadOnlyLabel
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    if (state.unreadOnly) Icons.Filled.FilterAlt else Icons.Filled.FilterAltOff,
                                    contentDescription = null,
                                )
                            }
                            IconButton(onClick = onOpenSubscriptions) {
                                Icon(
                                    Icons.Filled.Settings,
                                    contentDescription = stringResource(R.string.bulletin_notifications_title),
                                )
                            }
                        }
                        SearchBar(
                            value = state.searchText,
                            onValueChange = viewModel::setSearch,
                        )
                        FilterSection(
                            taxonomy = state.taxonomy,
                            selectedOrgs = state.selectedOrgs,
                            selectedTags = state.selectedTags,
                            onOrgsChange = viewModel::setOrgFilter,
                            onTagsChange = viewModel::setTagFilter,
                        )
                    }
                }

                val displayed = state.displayed
                val loadState = state.loadState
                when {
                    displayed.isEmpty() && loadState is AnnouncementsViewModel.LoadState.Loaded -> {
                        if (canShowEmptyState) {
                            item(key = "empty-state") {
                                CenteredEmptyState(
                                    height = emptyStateHeight,
                                    title = stringResource(
                                        if (state.unreadOnly) R.string.bulletin_no_unread_title
                                        else R.string.bulletin_no_bulletins_title
                                    ),
                                    message = stringResource(R.string.bulletin_no_bulletins_message),
                                )
                            }
                        }
                    }

                    displayed.isEmpty() && loadState is AnnouncementsViewModel.LoadState.Failed -> {
                        if (canShowEmptyState) {
                            item(key = "failed-state") {
                                CenteredEmptyState(
                                    height = emptyStateHeight,
                                    title = stringResource(R.string.bulletin_load_failed_title),
                                    message = loadState.message,
                                )
                            }
                        }
                    }

                    else -> {
                        items(displayed, key = { it.id }) { item ->
                            // 8dp gap above each card stands in for the
                            // verticalArrangement.spacedBy on the old list-only
                            // LazyColumn; horizontal padding moved off the
                            // (now mixed) LazyColumn's contentPadding so the
                            // header items keep their edge-to-edge layout.
                            SwipeableBulletinCard(
                                item = item,
                                taxonomy = state.taxonomy,
                                isRead = item.id in state.readIds,
                                onClick = { onOpenBulletin(item.id) },
                                onToggleRead = { viewModel.toggleRead(item.id) },
                                modifier = Modifier.padding(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 8.dp,
                                ),
                            )
                        }
                        if (state.isPaginating) {
                            item(key = "pagination-spinner") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center,
                                ) { CircularProgressIndicator() }
                            }
                        }
                        item(key = "bottom-spacer") { Spacer(Modifier.height(8.dp)) }
                    }
                }
            }
        }
    }
}

/**
 * Empty / failed state sized to fit exactly the area below the sticky
 * header, so the icon lands at the visual center of the available space
 * and the LazyColumn doesn't become scrollable past the viewport.
 */
@Composable
private fun CenteredEmptyState(
    height: androidx.compose.ui.unit.Dp,
    title: String,
    message: String,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
        contentAlignment = Alignment.Center,
    ) {
        EmptyStateView(
            icon = Icons.Filled.Campaign,
            title = title,
            message = message,
        )
    }
}

@Composable
private fun SearchBar(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        placeholder = { Text(stringResource(R.string.bulletin_search_prompt)) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
    )
}

/**
 * Two labeled chip rows mirroring iOS `BulletinFilterBar` — one for 處室
 * (department / org), one for 類別 (category / tag). Keeping the dimensions
 * visually separate is what tells users that the same bulletin can carry both
 * a department and one or more categories.
 */
@Composable
private fun FilterSection(
    taxonomy: TaxonomyResponse?,
    selectedOrgs: Set<String>,
    selectedTags: Set<String>,
    onOrgsChange: (Set<String>) -> Unit,
    onTagsChange: (Set<String>) -> Unit,
) {
    if (taxonomy == null) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (taxonomy.orgs.isNotEmpty()) {
            ChipRow(
                title = stringResource(R.string.bulletin_filter_dept),
                items = taxonomy.orgs.map { it.id to it.label },
                selected = selectedOrgs,
                onToggle = { id ->
                    onOrgsChange(if (id in selectedOrgs) selectedOrgs - id else selectedOrgs + id)
                },
            )
        }
        if (taxonomy.tags.isNotEmpty()) {
            ChipRow(
                title = stringResource(R.string.bulletin_filter_tag),
                items = taxonomy.tags.map { it.id to it.label },
                selected = selectedTags,
                onToggle = { id ->
                    onTagsChange(if (id in selectedTags) selectedTags - id else selectedTags + id)
                },
            )
        }
    }
}

@Composable
private fun ChipRow(
    title: String,
    items: List<Pair<String, String>>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            items(items) { (id, label) ->
                FilterChip(
                    selected = id in selected,
                    onClick = { onToggle(id) },
                    label = { Text(label) },
                )
            }
        }
    }
}

/**
 * Card layout mirrors iOS `BulletinCardView`:
 *  - Top row: unread dot, **filled accent badge for org (處室)**, importance
 *    badge, withdrawn badge, posted date.
 *  - Title row, semibold when unread.
 *  - Optional summary.
 *  - Bottom-right hashtag strip for content tags (類別).
 *
 * The org badge and tag strip are intentionally different visual styles so
 * 處室 reads as the primary source attribution while 類別 reads as
 * secondary metadata. The earlier mash-everything-into-one-line layout was
 * what made the user say "department is mixing with category".
 */
@Composable
private fun BulletinCard(
    item: BulletinSummary,
    taxonomy: TaxonomyResponse?,
    isRead: Boolean,
    onClick: () -> Unit,
) {
    val container = MaterialTheme.colorScheme.surfaceVariant
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = container,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // --- Top row: unread dot + org + importance + withdrawn + date.
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!isRead) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(cs.primary),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                item.canonicalOrg?.let { orgId ->
                    OrgBadge(label = taxonomy?.orgLabel(orgId) ?: orgId)
                    Spacer(Modifier.width(6.dp))
                }
                if (item.importance == "high") {
                    ImportanceBadge()
                    Spacer(Modifier.width(6.dp))
                }
                if (item.isDeleted) {
                    WithdrawnBadge()
                }
                Spacer(Modifier.weight(1f))
                item.postedAt?.let {
                    Text(
                        text = formatShortDate(it),
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.outline,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = item.displayTitle,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = if (isRead) FontWeight.Normal else FontWeight.SemiBold
                ),
                color = cs.onSurface,
                maxLines = 2,
            )
            item.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            // --- Bottom-right: hashtag strip for category tags.
            if (item.contentTags.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                TagStrip(
                    tags = item.contentTags,
                    taxonomy = taxonomy,
                )
            }
        }
    }
}

/**
 * Bulletin row with bidirectional swipe-to-toggle-read, mirroring the
 * SwipeableAssignmentRow pattern on the home screen (100dp threshold,
 * 0.6× drag damping, fling-out + snap-reset on commit, spring-back
 * otherwise). Either swipe direction toggles read state — the leading or
 * trailing icon flips between Check (will mark read) and Undo (will mark
 * unread) based on current state.
 */
@Composable
private fun SwipeableBulletinCard(
    item: BulletinSummary,
    taxonomy: TaxonomyResponse?,
    isRead: Boolean,
    onClick: () -> Unit,
    onToggleRead: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestOnToggleRead by rememberUpdatedState(onToggleRead)
    val density = LocalDensity.current
    val thresholdPx = with(density) { 100.dp.toPx() }
    val swipeOffset = remember(item.id) { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val actionColor = Color(0xFF34C759)
    val icon = if (isRead) Icons.Filled.Check else Icons.AutoMirrored.Filled.Undo
    val iconDesc = stringResource(
        if (isRead) R.string.bulletin_mark_as_unread_action
        else R.string.bulletin_mark_as_read_action
    )

    Box(modifier = modifier.fillMaxWidth()) {
        val progress = (abs(swipeOffset.value) / thresholdPx).coerceIn(0f, 1f)

        if (swipeOffset.value > 0f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(start = 20.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = iconDesc,
                    tint = actionColor,
                    modifier = Modifier
                        .size(26.dp)
                        .alpha(progress)
                        .scale(0.5f + 0.5f * progress),
                )
            }
        }
        if (swipeOffset.value < 0f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(end = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = iconDesc,
                    tint = actionColor,
                    modifier = Modifier
                        .size(26.dp)
                        .alpha(progress)
                        .scale(0.5f + 0.5f * progress),
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(swipeOffset.value.roundToInt(), 0) }
                .pointerInput(item.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            coroutineScope.launch {
                                when {
                                    swipeOffset.value <= -thresholdPx -> {
                                        swipeOffset.animateTo(
                                            -2000f,
                                            animationSpec = tween(durationMillis = 200),
                                        )
                                        latestOnToggleRead()
                                        swipeOffset.snapTo(0f)
                                    }

                                    swipeOffset.value >= thresholdPx -> {
                                        swipeOffset.animateTo(
                                            2000f,
                                            animationSpec = tween(durationMillis = 200),
                                        )
                                        latestOnToggleRead()
                                        swipeOffset.snapTo(0f)
                                    }

                                    else -> swipeOffset.animateTo(0f, animationSpec = spring())
                                }
                            }
                        },
                        onDragCancel = {
                            coroutineScope.launch {
                                swipeOffset.animateTo(0f, animationSpec = spring())
                            }
                        },
                        onHorizontalDrag = { _, delta ->
                            val signedDelta = if (isRtl) -delta else delta
                            coroutineScope.launch {
                                swipeOffset.snapTo(swipeOffset.value + signedDelta * 0.6f)
                            }
                        },
                    )
                },
        ) {
            BulletinCard(
                item = item,
                taxonomy = taxonomy,
                isRead = isRead,
                onClick = onClick,
            )
        }
    }
}

@Composable
private fun OrgBadge(label: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun ImportanceBadge() {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color(0xFFFFA500).copy(alpha = 0.18f),
    ) {
        Text(
            text = stringResource(R.string.bulletin_importance_high_badge),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFFF9500),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun WithdrawnBadge() {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
    ) {
        Text(
            text = stringResource(R.string.bulletin_withdrawn_badge),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun TagStrip(tags: List<String>, taxonomy: TaxonomyResponse?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        val visible = tags.take(3)
        val overflow = tags.size - visible.size
        val ctx = androidx.compose.ui.platform.LocalContext.current
        visible.forEach { id ->
            Text(
                text = "#${taxonomy.localizedTagLabel(id, ctx)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        if (overflow > 0) {
            Text(
                text = "+$overflow",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

private fun formatShortDate(raw: String): String {
    // Server emits ISO-8601 with Z; show just the date portion. Falling back
    // to the raw string preserves whatever the server sent if parsing fails.
    return try {
        val instant = java.time.Instant.parse(raw)
        java.time.format.DateTimeFormatter
            .ofPattern("M/d")
            .withZone(java.time.ZoneId.systemDefault())
            .format(instant)
    } catch (_: Exception) {
        raw.substringBefore('T')
    }
}
