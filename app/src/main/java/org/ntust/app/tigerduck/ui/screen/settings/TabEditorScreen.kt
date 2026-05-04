package org.ntust.app.tigerduck.ui.screen.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

private const val MAX_CUSTOM_TABS = 4

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabEditorScreen(
    appState: AppState,
    onBack: () -> Unit
) {
    var activeTabs by remember { mutableStateOf(appState.configuredTabs) }

    val allPinnable = AppFeature.pinnableFeatures.filter { feature ->
        !feature.isLibraryRelated || appState.libraryFeatureEnabled
    }

    val availableTabs by remember(activeTabs, allPinnable) {
        derivedStateOf { allPinnable.filter { it !in activeTabs } }
    }

    fun save(tabs: List<AppFeature>) {
        activeTabs = tabs
        appState.configuredTabs = tabs
    }

    // Drag state
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var itemHeightPx by remember { mutableStateOf(0f) }
    val haptic = LocalHapticFeedback.current

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_editor_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { save(AppFeature.defaultTabs) }) {
                        Text(stringResource(R.string.tab_editor_reset_default))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp)
        ) {
            // ── Section: 目前的 Tab ──
            item {
                SectionHeader(title = stringResource(R.string.tab_editor_section_current_tabs))
            }

            item {
                ContentCard {
                    Column {
                        activeTabs.forEachIndexed { index, feature ->
                            key(feature) {
                                val isDragging = draggingIndex == index
                                val elevation by animateDpAsState(
                                    if (isDragging) 4.dp else 0.dp,
                                    label = "drag_elevation"
                                )

                                Box(
                                    modifier = Modifier
                                        .zIndex(if (isDragging) 1f else 0f)
                                        .graphicsLayer {
                                            translationY = if (isDragging) dragOffsetY else 0f
                                        }
                                        .shadow(elevation)
                                        .onSizeChanged {
                                            itemHeightPx = it.height.toFloat()
                                        }
                                ) {
                                    Column {
                                        ActiveTabRow(
                                            feature = feature,
                                            onRemove = { save(activeTabs - feature) },
                                            onDragStarted = {
                                                draggingIndex = index
                                                dragOffsetY = 0f
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            },
                                            onDrag = { delta ->
                                                dragOffsetY += delta
                                                val currentIdx =
                                                    draggingIndex ?: return@ActiveTabRow
                                                if (itemHeightPx > 0f) {
                                                    when {
                                                        dragOffsetY > itemHeightPx * 0.5f && currentIdx < activeTabs.lastIndex -> {
                                                            val list = activeTabs.toMutableList()
                                                            val item = list.removeAt(currentIdx)
                                                            list.add(currentIdx + 1, item)
                                                            activeTabs = list
                                                            draggingIndex = currentIdx + 1
                                                            dragOffsetY -= itemHeightPx
                                                            haptic.performHapticFeedback(
                                                                HapticFeedbackType.TextHandleMove
                                                            )
                                                        }

                                                        dragOffsetY < -itemHeightPx * 0.5f && currentIdx > 0 -> {
                                                            val list = activeTabs.toMutableList()
                                                            val item = list.removeAt(currentIdx)
                                                            list.add(currentIdx - 1, item)
                                                            activeTabs = list
                                                            draggingIndex = currentIdx - 1
                                                            dragOffsetY += itemHeightPx
                                                            haptic.performHapticFeedback(
                                                                HapticFeedbackType.TextHandleMove
                                                            )
                                                        }
                                                    }
                                                }
                                            },
                                            onDragEnded = {
                                                draggingIndex = null
                                                dragOffsetY = 0f
                                                appState.configuredTabs = activeTabs
                                            }
                                        )
                                        if (index < activeTabs.lastIndex) {
                                            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                                        }
                                    }
                                }
                            } // key(feature)
                        }

                        // 更多 — always present, locked at bottom
                        HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                AppFeature.MORE.icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.DISABLED),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                stringResource(AppFeature.MORE.displayNameRes),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = stringResource(R.string.tab_editor_locked),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.DISABLED),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    stringResource(R.string.tab_editor_max_items_hint, MAX_CUSTOM_TABS + 1),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // ── Section: 其他可用的 Tab ──
            if (availableTabs.isNotEmpty()) {
                item {
                    SectionHeader(title = stringResource(R.string.tab_editor_section_available_tabs))
                }

                item {
                    ContentCard {
                        Column {
                            availableTabs.forEachIndexed { index, feature ->
                                AvailableTabRow(
                                    feature = feature,
                                    canAdd = activeTabs.size < MAX_CUSTOM_TABS,
                                    onAdd = {
                                        save(activeTabs + feature)
                                    }
                                )
                                if (index < availableTabs.lastIndex) {
                                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveTabRow(
    feature: AppFeature,
    onRemove: () -> Unit,
    onDragStarted: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnded: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                Icons.Filled.RemoveCircle,
                contentDescription = stringResource(R.string.action_remove),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(Modifier.width(4.dp))

        Icon(
            feature.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(feature.displayNameRes),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )

        Box(
            modifier = Modifier
                .size(40.dp)
                .pointerInput(feature) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStarted() },
                        onDrag = { change, offset ->
                            change.consume()
                            onDrag(offset.y)
                        },
                        onDragEnd = { onDragEnded() },
                        onDragCancel = { onDragEnded() }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.DragHandle,
                contentDescription = stringResource(R.string.tab_editor_drag_reorder),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.DISABLED),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun AvailableTabRow(
    feature: AppFeature,
    canAdd: Boolean,
    onAdd: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Green circle plus button
        IconButton(
            onClick = onAdd,
            enabled = canAdd,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = stringResource(R.string.action_add),
                tint = if (canAdd) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(Modifier.width(4.dp))

        Icon(
            feature.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(feature.displayNameRes),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
