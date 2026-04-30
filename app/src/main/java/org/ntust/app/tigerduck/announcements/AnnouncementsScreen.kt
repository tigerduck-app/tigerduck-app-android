package org.ntust.app.tigerduck.announcements

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.component.EmptyStateView
import org.ntust.app.tigerduck.ui.component.PageHeader
import org.ntust.app.tigerduck.ui.component.TigerPullToRefresh

@Composable
fun AnnouncementsScreen(
    onOpenBulletin: (Int) -> Unit,
    onOpenSubscriptions: () -> Unit,
    viewModel: AnnouncementsViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.load() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pullProgress by remember { mutableFloatStateOf(0f) }

    Column(modifier = Modifier.fillMaxSize()) {
        PageHeader(title = stringResource(R.string.feature_announcements)) {
            IconButton(onClick = onOpenSubscriptions) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.bulletin_notifications_title))
            }
        }

        SearchBar(
            value = state.searchText,
            onValueChange = viewModel::setSearch,
        )

        FilterChipRow(
            taxonomy = state.taxonomy,
            selectedOrgs = state.selectedOrgs,
            selectedTags = state.selectedTags,
            onOrgsChange = viewModel::setOrgFilter,
            onTagsChange = viewModel::setTagFilter,
        )

        TigerPullToRefresh(
            isRefreshing = state.loadState is AnnouncementsViewModel.LoadState.Loading,
            onRefresh = viewModel::refresh,
            onDragProgress = { pullProgress = it },
            modifier = Modifier.fillMaxSize(),
            refreshingMessage = stringResource(R.string.refreshing_message),
        ) {
            when {
                state.filtered.isEmpty() && state.loadState is AnnouncementsViewModel.LoadState.Loaded -> {
                    EmptyStateView(
                        icon = Icons.Filled.Campaign,
                        title = stringResource(R.string.bulletin_no_bulletins_title),
                        message = stringResource(R.string.bulletin_no_bulletins_message),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                state.filtered.isEmpty() && state.loadState is AnnouncementsViewModel.LoadState.Failed -> {
                    EmptyStateView(
                        icon = Icons.Filled.Campaign,
                        title = stringResource(R.string.bulletin_load_failed_title),
                        message = (state.loadState as AnnouncementsViewModel.LoadState.Failed).message,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                else -> BulletinList(
                    items = state.filtered,
                    isPaginating = state.isPaginating,
                    onClick = onOpenBulletin,
                    onLastVisible = viewModel::loadMoreIfNeeded,
                )
            }
        }
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

@Composable
private fun FilterChipRow(
    taxonomy: TaxonomyResponse?,
    selectedOrgs: Set<String>,
    selectedTags: Set<String>,
    onOrgsChange: (Set<String>) -> Unit,
    onTagsChange: (Set<String>) -> Unit,
) {
    if (taxonomy == null) return
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        items(taxonomy.orgs) { org ->
            FilterChip(
                selected = org.id in selectedOrgs,
                onClick = {
                    onOrgsChange(
                        if (org.id in selectedOrgs) selectedOrgs - org.id
                        else selectedOrgs + org.id
                    )
                },
                label = { Text(org.label) },
            )
        }
        items(taxonomy.tags) { tag ->
            FilterChip(
                selected = tag.id in selectedTags,
                onClick = {
                    onTagsChange(
                        if (tag.id in selectedTags) selectedTags - tag.id
                        else selectedTags + tag.id
                    )
                },
                label = { Text(tag.label) },
            )
        }
    }
}

@Composable
private fun BulletinList(
    items: List<BulletinSummary>,
    isPaginating: Boolean,
    onClick: (Int) -> Unit,
    onLastVisible: (BulletinSummary) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.id }) { item ->
            LaunchedEffect(item.id) { onLastVisible(item) }
            BulletinCard(item = item, onClick = { onClick(item.id) })
        }
        if (isPaginating) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
        }
    }
}

@Composable
private fun BulletinCard(item: BulletinSummary, onClick: () -> Unit) {
    val container = MaterialTheme.colorScheme.surfaceVariant
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = container,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.importance == "high") {
                    AssistChip(
                        onClick = {},
                        label = { Text(stringResource(R.string.bulletin_importance_high_badge)) },
                    )
                    Spacer(Modifier.width(8.dp))
                }
                if (item.isDeleted) {
                    AssistChip(
                        onClick = {},
                        label = { Text(stringResource(R.string.bulletin_withdrawn_badge)) },
                    )
                }
            }
            Text(
                text = item.displayTitle,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            item.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
            }
            Spacer(Modifier.height(6.dp))
            val tagText = buildList {
                item.canonicalOrg?.let { add(it) }
                addAll(item.contentTags)
            }.joinToString(" · ")
            if (tagText.isNotEmpty()) {
                Text(
                    text = tagText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}
