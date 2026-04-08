package org.ntust.app.tigerduck.ui.screen.announcements

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import org.ntust.app.tigerduck.data.model.Announcement
import org.ntust.app.tigerduck.ui.component.PageHeader
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun AnnouncementsScreen(
    navController: NavController,
    viewModel: AnnouncementsViewModel = hiltViewModel()
) {
    val announcements by viewModel.filteredAnnouncements.collectAsState()
    val departments by viewModel.departments.collectAsState()
    val selectedDepartments by viewModel.selectedDepartments.collectAsState()
    val searchText by viewModel.searchText.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var showSearch by remember { mutableStateOf(false) }
    var selectedAnnouncement by remember { mutableStateOf<Announcement?>(null) }

    LaunchedEffect(Unit) { viewModel.load() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                PageHeader(title = "公告") {
                    IconButton(onClick = {
                        showSearch = !showSearch
                        if (!showSearch) viewModel.setSearchText("")
                    }) {
                        Icon(
                            if (showSearch) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = "搜尋"
                        )
                    }
                }

                if (showSearch) {
                    OutlinedTextField(
                        value = searchText,
                        onValueChange = { viewModel.setSearchText(it) },
                        placeholder = { Text("搜尋公告") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, null) }
                    )
                }

                // Filter chips
                if (departments.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(departments) { dept ->
                            FilterChip(
                                selected = dept in selectedDepartments,
                                onClick = { viewModel.toggleDepartment(dept) },
                                label = { Text(dept) }
                            )
                        }
                    }
                }
            }
        }

        if (isLoading) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        } else if (announcements.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("沒有公告", color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY))
                }
            }
        } else {
            items(announcements, key = { it.announcementId }) { announcement ->
                AnnouncementCard(
                    announcement = announcement,
                    onClick = { selectedAnnouncement = announcement }
                )
            }
        }
    }

    selectedAnnouncement?.let { a ->
        AlertDialog(
            onDismissRequest = { selectedAnnouncement = null },
            title = { Text(a.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${a.department} · ${SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(a.publishDate)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(a.summary, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedAnnouncement = null }) { Text("關閉") }
            }
        )
    }
}

@Composable
private fun AnnouncementCard(announcement: Announcement, onClick: () -> Unit) {
    val dateFmt = remember { SimpleDateFormat("MM/dd", Locale.getDefault()) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = announcement.department,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = dateFmt.format(announcement.publishDate),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
                )
            }
            Text(
                text = announcement.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = announcement.summary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
            )
        }
    }
}
