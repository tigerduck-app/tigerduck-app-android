// 訂閱社團, the class table menu's club subscriptions, as the iPhone's ClubSubscribeSheet: the
// subscribed clubs in priority order, which drag to reorder, swipe to unsubscribe and take a color
// from their square, then every club by type and name, and a search by Chinese or English name.

package org.ntust.app.tigerduck.ui.screen.classtable.clubs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.clubs.ClubCard
import org.ntust.app.tigerduck.clubs.ClubDirectoryState
import org.ntust.app.tigerduck.ui.component.DetentSheet
import org.ntust.app.tigerduck.ui.component.EmptyStateView
import org.ntust.app.tigerduck.ui.component.SearchFieldIcon
import org.ntust.app.tigerduck.ui.component.rememberDetentSheetState
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

/** A club whose color the color sheet is open for. */
internal data class ClubColorTarget(val id: Int, val name: String)

@Composable
internal fun ClubSubscribeSheet(viewModel: ClassTableClubsViewModel, onDismiss: () -> Unit) {
    var colorTarget by remember { mutableStateOf<ClubColorTarget?>(null) }
    DetentSheet(onDismissed = onDismiss, state = rememberDetentSheetState(), halfHeight = true) {
        ClubSubscribeContent(viewModel, onClose = onDismiss, onPickColor = { colorTarget = it })
    }
    colorTarget?.let { target ->
        ClubColorSheet(
            name = target.name,
            color = viewModel.color(target.id),
            onApply = { viewModel.setColor(target.id, it); colorTarget = null },
            onDismiss = { colorTarget = null },
        )
    }
}

@Composable
private fun ClubSubscribeContent(
    viewModel: ClassTableClubsViewModel,
    onClose: () -> Unit,
    onPickColor: (ClubColorTarget) -> Unit,
) {
    val directory by viewModel.directoryState.collectAsStateWithLifecycle()
    val document by viewModel.document.collectAsStateWithLifecycle()
    var searchText by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(directory.clubs.isEmpty()) }
    LaunchedEffect(Unit) {
        loading = viewModel.directoryState.value.clubs.isEmpty()
        viewModel.refreshDirectory()
        loading = false
    }
    val query = searchText.trim()
    val matches = remember(query, directory) { if (query.isEmpty()) emptyList() else viewModel.search(query) }
    val subscribedIds = document.subscribedIds.toSet()
    val toggle = { club: ClubCard -> if (club.id in subscribedIds) viewModel.unsubscribe(club.id) else viewModel.subscribe(club.id) }
    Column(modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.class_table_subscribe_clubs),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it },
            placeholder = { Text(stringResource(R.string.class_table_club_search_prompt)) },
            leadingIcon = { SearchFieldIcon(searching = loading && query.isNotEmpty()) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
        ) {
            item { ClubListStatus(directory, loading) }
            if (query.isEmpty()) {
                val subscribed = document.subscribedIds.mapNotNull { id -> directory.clubs.firstOrNull { it.id == id } }
                if (subscribed.isNotEmpty()) {
                    item { ClubSectionHeader(stringResource(R.string.class_table_club_section_subscribed)) }
                    item {
                        SubscribedClubs(
                            clubs = subscribed,
                            name = viewModel::displayName,
                            color = viewModel::color,
                            onReorder = { shown -> viewModel.reorder(reorderedOrder(document.subscribedIds, shown)) },
                            onUnsubscribe = { viewModel.unsubscribe(it.id) },
                            onPickColor = { onPickColor(ClubColorTarget(it.id, viewModel.displayName(it))) },
                        )
                    }
                }
                if (directory.clubs.isNotEmpty()) {
                    item { ClubSectionHeader(stringResource(R.string.class_table_club_section_all)) }
                    items(directory.clubs, key = { "all-${it.id}" }) { ClubToggleRow(viewModel.displayName(it), it, it.id in subscribedIds, toggle) }
                }
            } else {
                items(matches, key = { "match-${it.id}" }) { ClubToggleRow(viewModel.displayName(it), it, it.id in subscribedIds, toggle) }
            }
        }
    }
}

/**
 * Above the list: the nightly maintenance when it left no clubs to show, else, with none yet,
 * the search's progress or why it failed.
 */
@Composable
private fun ClubListStatus(directory: ClubDirectoryState, loading: Boolean) {
    if (directory.clubs.isNotEmpty()) return
    when {
        !loading && directory.underMaintenance -> EmptyStateView(
            icon = Icons.Filled.Build,
            title = stringResource(R.string.class_table_club_maintenance_title),
            message = stringResource(R.string.class_table_club_maintenance_message),
            modifier = Modifier.padding(vertical = 32.dp),
        )
        loading -> ClubStatusText(stringResource(R.string.class_table_club_searching))
        directory.failure != null -> ClubStatusText(
            stringResource(R.string.class_table_club_search_failed, directory.failure.localizedMessage.orEmpty()),
        )
    }
}

@Composable
private fun ClubStatusText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
internal fun ClubSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp)
            .semantics { heading() },
    )
}

/** A club of 所有社團 or of a search: ⊕ subscribes it, ✓ unsubscribes it. */
@Composable
private fun ClubToggleRow(name: String, club: ClubCard, isSubscribed: Boolean, onToggle: (ClubCard) -> Unit) {
    val hint = stringResource(
        if (isSubscribed) R.string.class_table_club_unsubscribe_action else R.string.class_table_club_subscribe_action,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = hint, role = Role.Button) { onToggle(club) }
            .semantics { selected = isSubscribed }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ClubNameLabel(name, club.tagline, Modifier.weight(1f))
        Icon(
            if (isSubscribed) Icons.Filled.CheckCircle else Icons.Filled.AddCircleOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/** A club's name, and its tagline on one line under it. */
@Composable
internal fun ClubNameLabel(name: String, tagline: String?, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        if (!tagline.isNullOrBlank()) {
            Text(
                tagline,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The subscriptions' order once the shown clubs moved to [shown]: a subscribed club the directory
 * no longer lists, and so the list leaves out, keeps its place among the rest.
 */
internal fun reorderedOrder(order: List<Int>, shown: List<Int>): List<Int> {
    val moved = shown.iterator()
    val shownSet = shown.toSet()
    return order.map { if (it in shownSet && moved.hasNext()) moved.next() else it }
}
