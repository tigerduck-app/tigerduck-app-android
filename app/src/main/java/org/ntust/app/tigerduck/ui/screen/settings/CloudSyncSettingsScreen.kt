package org.ntust.app.tigerduck.ui.screen.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudSyncSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    var syncEnabled by remember { mutableStateOf(viewModel.appState.cloudSyncEnabled) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_cloud_sync_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                ContentCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.settings_cloud_sync_toggle_label),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(R.string.settings_sync_brief_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                            )
                        }
                        Switch(
                            checked = syncEnabled,
                            onCheckedChange = {
                                syncEnabled = it
                                viewModel.appState.cloudSyncEnabled = it
                            },
                        )
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.settings_sync_data_section)) }
            item {
                ContentCard {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DataInfoRow(Icons.Filled.CheckCircle, Color(0xFF34C759), stringResource(R.string.onboarding_sync_shared_student_id))
                        DataInfoRow(Icons.Filled.CheckCircle, Color(0xFF34C759), stringResource(R.string.onboarding_sync_shared_moodle_token))
                        DataInfoRow(Icons.Filled.CheckCircle, Color(0xFF34C759), stringResource(R.string.onboarding_sync_shared_device_id))
                        DataInfoRow(Icons.Filled.CheckCircle, Color(0xFF34C759), stringResource(R.string.onboarding_sync_shared_courses))
                        DataInfoRow(Icons.Filled.CheckCircle, Color(0xFF34C759), stringResource(R.string.onboarding_sync_shared_assignments))
                        DataInfoRow(Icons.Filled.Cancel, Color(0xFFFF3B30), stringResource(R.string.onboarding_sync_not_shared_password))
                    }
                }
            }

            if (!syncEnabled) {
                item {
                    ContentCard {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Filled.Info,
                                contentDescription = null,
                                tint = Color(0xFFFF9500),
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                stringResource(R.string.settings_sync_disabled_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFF9500),
                            )
                        }
                    }
                }
            }

            item {
                ContentCard {
                    Column {
                        LinkRow(
                            label = stringResource(R.string.onboarding_privacy_policy_label),
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, "https://tigerduck.app/privacy-policy".toUri())
                                context.startActivity(intent)
                            },
                        )
                        LinkRow(
                            label = stringResource(R.string.onboarding_privacy_delete_account_label),
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, "https://tigerduck.app/delete-account".toUri())
                                context.startActivity(intent)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DataInfoRow(icon: ImageVector, color: Color, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .then(Modifier.padding(0.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .weight(1f)
                .then(Modifier.padding(0.dp)),
        )
        IconButton(onClick = onClick, modifier = Modifier.size(24.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
