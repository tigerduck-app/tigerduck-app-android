package org.ntust.app.tigerduck.ui.screen.informationsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.network.model.PortalCategory
import org.ntust.app.tigerduck.network.model.PortalLink
import org.ntust.app.tigerduck.ui.component.EmptyStateView
import org.ntust.app.tigerduck.ui.component.PageHeader
import org.ntust.app.tigerduck.ui.component.TigerPullToRefresh

@Composable
fun InformationSystemScreen(
    onOpenLink: (title: String, url: String) -> Unit,
    onOpenSignInSettings: () -> Unit = {},
    viewModel: InformationSystemViewModel = hiltViewModel(),
) {
    // Only zh vs. en variants exist on NTUST's own portal — every other app
    // locale falls back to English, the same choice GuideWebView-adjacent
    // screens make for locale-specific embeds.
    val useEnglish = LocalConfiguration.current.locales[0].language != "zh"
    LaunchedEffect(useEnglish) { viewModel.load(useEnglish) }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
    val isLoading = state.loadState is InformationSystemViewModel.LoadState.Loading

    LaunchedEffect(state.pendingLink) {
        state.pendingLink?.let { link ->
            onOpenLink(link.name, link.url)
            viewModel.consumePendingLink()
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.openLinkError) {
        state.openLinkError?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.consumeOpenLinkError()
        }
    }

    Box(Modifier.fillMaxSize()) {
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
        TigerPullToRefresh(
            isRefreshing = isLoading,
            onRefresh = { viewModel.refresh(useEnglish) },
            modifier = Modifier.fillMaxSize(),
            refreshingMessage = stringResource(R.string.refreshing_message),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                PageHeader(title = stringResource(R.string.feature_information_system))

                when {
                    !isLoggedIn -> EmptyStateView(
                        icon = Icons.Filled.Lock,
                        title = stringResource(R.string.common_not_signed_in),
                        message = null,
                        modifier = Modifier.padding(top = 32.dp),
                        onIconClick = onOpenSignInSettings,
                    )

                    state.items.isEmpty() && state.loadState is InformationSystemViewModel.LoadState.Failed -> {
                        val message = (state.loadState as InformationSystemViewModel.LoadState.Failed).message
                        EmptyStateView(
                            icon = Icons.Filled.Public,
                            title = stringResource(R.string.information_system_load_failed),
                            message = message,
                            modifier = Modifier.padding(top = 32.dp),
                        )
                    }

                    else -> {
                        SearchField(value = state.searchText, onValueChange = viewModel::setSearch)
                        CategoryChipRow(
                            selected = state.selectedCategory,
                            onSelect = viewModel::setCategory,
                        )
                        if (state.displayed.isEmpty() && !isLoading) {
                            EmptyStateView(
                                icon = Icons.Filled.Search,
                                title = stringResource(R.string.information_system_search_empty),
                                message = null,
                                modifier = Modifier.padding(top = 32.dp),
                            )
                        } else {
                            PortalLinkList(links = state.displayed, groupByCategory = state.selectedCategory == null, onOpen = { link ->
                                viewModel.openLink(link)
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        placeholder = { Text(stringResource(R.string.information_system_search_hint)) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
    )
}

@Composable
private fun CategoryChipRow(
    selected: PortalCategory?,
    onSelect: (PortalCategory?) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.information_system_all_services)) },
            )
        }
        items(PortalCategory.entries) { category ->
            FilterChip(
                selected = selected == category,
                onClick = { onSelect(if (selected == category) null else category) },
                label = { Text(stringResource(category.displayNameRes)) },
            )
        }
    }
}

@Composable
private fun PortalLinkList(
    links: List<PortalLink>,
    groupByCategory: Boolean,
    onOpen: (PortalLink) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (groupByCategory) {
            val grouped = links.groupBy { it.category }
            PortalCategory.entries.forEach { category ->
                val items = grouped[category] ?: return@forEach
                item(key = "header-${category.serviceId}") {
                    Text(
                        text = stringResource(category.displayNameRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                    )
                }
                itemsIndexed(items, key = { index, link -> "${category.serviceId}-$index-${link.url}" }) { _, link ->
                    PortalLinkRow(link = link, onClick = { onOpen(link) })
                }
            }
        } else {
            // NTUST's own portal page repeats the same URL under more than one link
            // (e.g. two menu entries both pointing at courseselection.ntust.edu.tw/),
            // so (category, url) alone is not a unique key — the index is what makes
            // it one, same as the grouped branch above.
            itemsIndexed(links, key = { index, link -> "${link.category.serviceId}-$index-${link.url}" }) { _, link ->
                PortalLinkRow(link = link, onClick = { onOpen(link) })
            }
        }
    }
}

@Composable
private fun PortalLinkRow(link: PortalLink, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = link.name,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
