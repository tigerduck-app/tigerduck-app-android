package org.ntust.app.tigerduck.ui.screen.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.data.model.FeatureCategory
import org.ntust.app.tigerduck.ui.navigation.Screen
import org.ntust.app.tigerduck.ui.navigation.toRoute

private val implementedFeatures = setOf(
    AppFeature.HOME, AppFeature.CLASS_TABLE, AppFeature.CALENDAR,
    AppFeature.ANNOUNCEMENTS, AppFeature.LIBRARY,
    AppFeature.MORE, AppFeature.SETTINGS
)

@Composable
fun MoreScreen(navController: NavController) {
    var showNotImplemented by remember { mutableStateOf(false) }

    val grouped = AppFeature.moreFeatures
        .groupBy { it.category }
        .toList()
        .sortedBy { (cat, _) -> cat?.ordinal ?: 99 }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "更多",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { navController.navigate(Screen.Settings.route) }) {
                    Icon(Icons.Filled.Settings, "設定")
                }
            }
        }

        grouped.forEach { (category, features) ->
            item {
                Text(
                    text = category?.displayName ?: "其他",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    features.forEachIndexed { index, feature ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (feature in implementedFeatures) {
                                        navController.navigate(feature.toRoute())
                                    } else {
                                        showNotImplemented = true
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = feature.icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = feature.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                Icons.Filled.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        if (index < features.lastIndex) {
                            HorizontalDivider(modifier = Modifier.padding(start = 50.dp))
                        }
                    }
                }
            }
        }
    }

    if (showNotImplemented) {
        AlertDialog(
            onDismissRequest = { showNotImplemented = false },
            title = { Text("快了快了") },
            text = { Text("此功能尚未實現，敬請期待～") },
            confirmButton = {
                TextButton(onClick = { showNotImplemented = false }) { Text("收到！") }
            }
        )
    }
}
