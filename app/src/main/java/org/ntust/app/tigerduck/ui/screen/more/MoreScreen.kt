package org.ntust.app.tigerduck.ui.screen.more

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.data.model.FeatureCategory
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.component.ComingSoonDialog
import org.ntust.app.tigerduck.ui.component.PageHeader
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.navigation.Screen
import org.ntust.app.tigerduck.ui.navigation.toRoute

private val implementedFeatures = setOf(
    AppFeature.HOME, AppFeature.CLASS_TABLE, AppFeature.CALENDAR,
    AppFeature.LIBRARY,
    AppFeature.MORE, AppFeature.SETTINGS
)

@Composable
fun MoreScreen(navController: NavController, appState: AppState) {
    var showNotImplemented by remember { mutableStateOf(false) }

    val grouped = AppFeature.moreFeatures
        .filter { feature ->
            !feature.isLibraryRelated || appState.libraryFeatureEnabled
        }
        .groupBy { it.category }
        .toList()
        .sortedBy { (cat, _) -> cat?.ordinal ?: 99 }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            PageHeader(title = "更多") {
                IconButton(onClick = { navController.navigate(Screen.Settings.route) }) {
                    Icon(Icons.Filled.Settings, "設定")
                }
            }
        }

        grouped.forEachIndexed { index, (category, features) ->
            item {
                SectionHeader(
                    title = category?.displayName ?: "其他",
                    modifier = if (index > 0) Modifier.padding(top = 16.dp) else Modifier
                )
            }
            item {
                val rows = features.chunked(2)
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rows.forEach { rowFeatures ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            rowFeatures.forEach { feature ->
                                Card(
                                    onClick = {
                                        if (feature in implementedFeatures) {
                                            navController.navigate(feature.toRoute())
                                        } else {
                                            showNotImplemented = true
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1.6f),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(14.dp),
                                        verticalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Icon(
                                            imageVector = feature.icon,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Text(
                                            text = feature.displayName,
                                            style = MaterialTheme.typography.titleLarge.copy(
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        )
                                    }
                                }
                            }
                            // Pad with spacer if odd number
                            if (rowFeatures.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNotImplemented) {
        ComingSoonDialog(onDismiss = { showNotImplemented = false })
    }
}
