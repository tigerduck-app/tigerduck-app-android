package org.ntust.app.tigerduck.ui.screen.more

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.component.ComingSoonDialog
import org.ntust.app.tigerduck.ui.component.PageHeader
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.navigation.Screen
import org.ntust.app.tigerduck.ui.navigation.toRoute

private val implementedFeatures = setOf(
    AppFeature.HOME, AppFeature.CLASS_TABLE, AppFeature.CALENDAR,
    AppFeature.ANNOUNCEMENTS,
    AppFeature.LIBRARY, AppFeature.SCORE,
    AppFeature.MORE, AppFeature.SETTINGS
)

@Composable
fun MoreScreen(navController: NavController, appState: AppState) {
    var showNotImplemented by remember { mutableStateOf(false) }

    val pageFeatures = listOf(AppFeature.HOME, AppFeature.CLASS_TABLE, AppFeature.CALENDAR)

    val grouped = AppFeature.moreFeatures
        .filter { feature ->
            feature !in pageFeatures &&
                (!feature.isLibraryRelated || appState.libraryFeatureEnabled)
        }
        .groupBy { it.category }
        .toList()
        .sortedBy { (cat, _) -> cat?.ordinal ?: 99 }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            PageHeader(title = stringResource(R.string.feature_more)) {
                IconButton(
                    onClick = { navController.navigate(Screen.Settings.route) }
                ) {
                    Icon(
                        Icons.Filled.Settings, stringResource(R.string.feature_settings),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        // 頁面 section (first section)
        item { SectionHeader(title = stringResource(R.string.more_section_pages)) }
        item {
            FeatureGrid(
                features = pageFeatures,
                implementedFeatures = implementedFeatures,
                navController = navController,
                onNotImplemented = { showNotImplemented = true }
            )
        }

        grouped.forEachIndexed { index, (category, features) ->
            item {
                SectionHeader(
                    title = category?.let { stringResource(it.displayNameRes) }
                        ?: stringResource(R.string.more_section_other),
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
            item {
                FeatureGrid(
                    features = features,
                    implementedFeatures = implementedFeatures,
                    navController = navController,
                    onNotImplemented = { showNotImplemented = true }
                )
            }
        }
    }

    if (showNotImplemented) {
        ComingSoonDialog(onDismiss = { showNotImplemented = false })
    }
}

@Composable
private fun FeatureGrid(
    features: List<AppFeature>,
    implementedFeatures: Set<AppFeature>,
    navController: NavController,
    onNotImplemented: () -> Unit
) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val columns = when {
        screenWidthDp >= 840 -> 4
        screenWidthDp >= 600 -> 3
        else -> 2
    }
    val rows = features.chunked(columns)
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
                    val interactionSource = remember { MutableInteractionSource() }
                    val isPressed by interactionSource.collectIsPressedAsState()
                    val scale by animateFloatAsState(
                        targetValue = if (isPressed) 0.92f else 1f,
                        label = "cardScale"
                    )
                    Card(
                        onClick = {
                            if (feature in implementedFeatures) {
                                // Tapping the same tile twice would otherwise stack two
                                // copies of the destination on the back stack.
                                navController.navigate(feature.toRoute()) {
                                    launchSingleTop = true
                                }
                            } else {
                                onNotImplemented()
                            }
                        },
                        interactionSource = interactionSource,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1.6f)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
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
                                modifier = Modifier.size(32.dp)
                            )
                            Text(
                                text = stringResource(feature.displayNameRes),
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }
                    }
                }
                repeat(columns - rowFeatures.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
